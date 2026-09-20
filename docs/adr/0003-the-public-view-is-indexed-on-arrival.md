# ADR 0003 — The public view is indexed on arrival

**Status:** accepted · 2026-09-11 · S22 · [addendum](#addendum--the-front-door-changed-2026-09-21)

## Context

M8 asks for a public HTTPS address serving the dashboard, and a Lambda lookup returning real data,
at $0.00.

The live dashboard cannot be that address. Its API reads MongoDB and Kafka on a Kind cluster on a
laptop behind a home router, which has no public address. That is the same fact that made deployment
pulled rather than pushed in S19 (ADR 0001's first consequence). So whatever is public has to be fed
by something AWS already holds, and the one real data set there is S21's archive: the four canonical
topics as gzipped NDJSON, one file per topic per UTC hour, in S3.

Two facts about the bill shape the choice:

- **S3 is paid from credits on this account, and the budget alerts at one cent of gross cost.** A
  cent buys about 25,000 reads or 2,000 writes and listings. A listing costs twelve and a half times
  what a read does.
- **A public address has visitors nobody chose**: crawlers, link previews, anybody. Any cost that
  rises with visitors is a cost this project does not control.

## Decision

**The archive is indexed once, when each file lands, into a small DynamoDB table. The public page
reads only that table.**

```
archiver -> S3 archive/ --(ObjectCreated)--> index function --> DynamoDB (one partition per shipment)
browser  -> CloudFront -+- /*     -> private bucket: the dashboard, built in archive mode
                        +- /api/* -> lookup function URL, signed by CloudFront -> DynamoDB
```

- **S3 is read once per archived file.** Its request count grows with what the laptop produced,
  which is a few hundred files a month, and never with visitors.
- **Visitors cost CloudFront, Lambda and DynamoDB requests**, all inside allowances that do not
  expire: CloudFront's 1 TB and 10M requests, Lambda's 1M invocations and 400,000 GB-seconds, and
  DynamoDB's 25 read and 25 write capacity units.
- **The table's capacity is provisioned**, ten units each way. On-demand capacity bills per request
  with no free allowance. Provisioned capacity inside the allowance is free, and exceeding it throttles
  instead of billing, so a flood of visitors slows the page down rather than running up a charge. The
  account's Lambda concurrency limit of ten does the same for the functions.
- **The index folds before it writes**: an hour of positions (tens of thousands of lines) becomes at
  most one row per shipment, with one row per stop and per incident. Every write is conditional on
  being newer, or touches only the attributes its own event carries, so a file indexed twice, or out
  of order, or concurrently with its neighbour, leaves the same table.
- **The lookup answers in the live API's wire shapes**, so one dashboard serves both pages. It leaves
  out what an archive cannot honestly say: estimates (a forecast from a run that may have ended days
  ago), manifests (not archived) and trails.

## Consequences

- The table outlives the archive's own retention. Positions leave S3 after three days, and the last
  fix per shipment stays on the public page for thirty (rows carry a time-to-live), which is the
  "blank after a long weekend" problem S21 anticipated, solved.
- The public view is as fresh as the archive: an hour behind at best, and frozen while the laptop is
  off. The page says "archived through" with the instant, rather than letting a snapshot pass for a
  live feed.
- The archive can hold two runs over the same shipment id, and the live view never sees that, since a
  demonstration reset drops the geofence state. So the lookup counts a stop only from the latest
  arrival at the plan's origin. It is the one judgement the public view makes that the live dashboard
  does not.
- Which stop a truck is at and whether it is delivered are decided as the dashboard API decides
  them, in a copy of that logic rather than a call to it: the API's assembler takes MongoDB documents,
  and a Lambda that can reach no MongoDB should not ship a Mongo driver to reuse it.
- The one cost still driven by visitors is a stream of distinct uncached URLs, each an invocation.
  CloudFront caches 404s for a minute and drops every query parameter but one from the cache key, and
  the concurrency limit caps the rate; past a million a month the price is $0.20 per million, and the
  budget alert is the backstop.

## Alternatives rejected

- **The lookup reads the archive per request, cached at CloudFront and in memory.** Fewest parts. But
  every cache miss pays for listings, and a cold function re-reads the whole window, so S3 requests
  rise with visitors. Worked through on this account: a cold miss over a month of archive is about 300
  reads and a few listings, so roughly seventy of them reach the one-cent alert.
- **A job in the cluster publishes the dashboard API's snapshot to S3 every few minutes.** The public
  page would then show everything the live one does, ETAs and manifests included. But it adds a third
  identity able to write to AWS from the laptop, spends the same write budget the archiver lives
  within, leaves the Lambda as a thin filter, and publishes an ETA as though it were current long
  after the run that produced it has ended.
- **Serve the public view straight from the Lambda function URL, with no CloudFront.** It is HTTPS and
  free. But with no cache in front, every page view is an invocation and the function is open to
  anybody, and it is the design that makes cost grow with visitors again.

## Addendum — the front door changed (2026-09-21)

**The last alternative rejected above is what is deployed, and the public view is live at**
`https://v5s7czprqtpeavdr7hgibilyxy0uwtho.lambda-url.ap-south-1.on.aws`.

Not because the reasoning above changed, but because CloudFront turned out not to be available.
Creating a distribution is refused with `AccessDenied: Your account must be verified before you can
add new CloudFront resources`. A support case was filed on 2026-09-13; AWS replied on 2026-09-15
that it had gone to a "Specialized Service Team" and left it in *Pending Amazon Action*, which does
not resolve on its own. Eight days later nothing had moved, and the Support API needs a paid support
plan, so even the case's state can only be read in a browser.

A Lambda function URL is HTTPS with a certificate of its own, so the lookup function serves the
dashboard's files as well as answering its questions, reading them from the same S3 site bucket
CloudFront would have read. `cloudfront_enabled` in `infra/cloud/variables.tf` — default `false` —
picks between the two.

### What survives, and what does not

The title of this ADR still holds, and that is the point of taking this route rather than the first
alternative. **An archive file is still read exactly once, when it lands.** S3's request count still
grows with what the laptop produced and never with who is looking, which is the cost this account's
one-cent alert actually watches. The table, the indexer, the fold and the lookup's answers are
untouched.

What is given up is the edge:

- **Every page view is an invocation**, and so is every file within it. A page is roughly a dozen
  requests where CloudFront would have served eleven from a cache.
- **The function URL is open to the internet.** Under the original design nothing could reach the
  function except one distribution, and that was a guarantee rather than a hope. It is now a door.
- **Cache-control is advice rather than enforcement.** The headers are unchanged and browsers honour
  them, but there is no shared cache making a second viewer free.

It stays at $0.00 because Lambda's million invocations a month do not expire, the account's
concurrency ceiling of ten caps the rate at which the open door can be walked through, the lookup's
role can read one table and one bucket and write nothing anywhere, and the table's provisioned
capacity throttles rather than bills.

### The cache moved inside the function

Each execution environment holds the files it has fetched for five minutes, bounded by bytes rather
than entries because a build's files are so unequal in size. **Misses are remembered too** — a miss
costs an S3 GET exactly as a hit does, and this ADR names a stream of distinct uncached URLs as the
one cost a public page cannot control. CloudFront answered that by caching 404s for a minute; with
nothing in front, this cache is what does it.

This is deliberately a weaker claim than CloudFront's. A cold environment refetches, several can run
at once, and nothing coordinates them. It is the difference between a few hundred S3 GETs a day and
a few thousand — worth having, and not the same thing as an edge.

### Two things that cost a day between them

Both are recorded here because both are invisible until they happen, and both looked like something
else.

**A public function URL needs two permissions, not one.** `AuthType: NONE` plus
`lambda:InvokeFunctionUrl` — AWS's own headline example — produces **403 on every request**, with no
invocation and nothing in the function's log. It is indistinguishable from the URL being closed, or
from the account being barred from public endpoints, which is what it was first taken for. Since
October 2025 a function URL also checks `lambda:InvokeFunction`, and that statement is conditioned
on `lambda:InvokedViaFunctionUrl` rather than `lambda:FunctionUrlAuthType` — passing the latter is
rejected outright, which makes the wrong guess look like a dead end. The same trap is already
documented in `public-view.tf` for the CloudFront pair; it applies to `NONE` just as much.

**Without `s3:ListBucket`, S3 answers 403 for a key that is not there.** It will not confirm the
absence of an object to a caller that may not list. The lookup deliberately has no `ListBucket` — it
fetches keys the request names and never lists — so a miss arrives as `AccessDenied`, not
`NoSuchKey`, and the first deployment answered 503 to every missing path. `Site` reads 403 and 404
alike as "missing" and logs when it does, because a genuine permissions mistake arrives in exactly
the same shape and would otherwise present as an empty site rather than a failure. Granting
`ListBucket` for a tidier 404 would widen the function's reach over the bucket to buy nothing.

### Going back

Set `cloudfront_enabled = true` and apply. The distribution, the two origin access controls and the
API cache policy are recreated, the function URL returns to `AWS_IAM`, the site bucket grants reads
to the distribution instead of the Lambda's role, and `SITE_BUCKET` disappears from the function's
environment — which is what makes the code stop serving the page, with no code change.
