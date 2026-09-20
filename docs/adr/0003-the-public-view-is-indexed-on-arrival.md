# ADR 0003 — The public view is indexed on arrival

**Status:** accepted · 2026-09-11 · S22 · [addendum](#addendum--the-front-door-changed-and-the-account-refused-that-too-2026-09-21)

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

## Addendum — the front door changed, and the account refused that too (2026-09-21)

**The last alternative rejected above was built, applied, and is still refused.** The blocker is not
this design. It is the account.

### What was blocked, and what was tried

Creating a distribution is refused with `AccessDenied: Your account must be verified before you can
add new CloudFront resources`. A support case was filed on 2026-09-13; AWS replied on 2026-09-15 that
it had gone to a "Specialized Service Team" and left it in *Pending Amazon Action*, which does not
resolve on its own. Eight days later nothing had moved, and the Support API is not available on this
account's plan, so even the case's state can only be read in a browser.

So the front door was rebuilt on a Lambda function URL, which is HTTPS with a certificate of its own:
the lookup function serves the dashboard's files as well as answering its questions, and
`cloudfront_enabled` in `infra/cloud/variables.tf` — default `false` — picks between the two.

### What happened when it was applied

**The public function URL is refused as well.** With `AuthType: NONE` and AWS's own documented
public-access policy in place (`lambda:InvokeFunctionUrl`, principal `*`, conditioned on
`lambda:FunctionUrlAuthType = NONE`), every request returns:

```
HTTP/1.1 403 Forbidden
x-amzn-ErrorType: AccessDeniedException
{"Message":"Forbidden. For troubleshooting Function URL authorization issues, see: ..."}
```

CloudWatch shows **no invocation** for any of those requests: the refusal happens at the URL layer,
before the function runs. It persisted for half an hour after the change, so it is not propagation.

The conclusion is that the verification gate is not about CloudFront. It is about **exposing a public
endpoint at all**, and a public Lambda function URL is one. That makes the fallback a fallback in
name only: it moves the block, it does not clear it.

### What is nonetheless verified

The design works; only the door is shut. Invoked directly, the deployed function does both jobs
correctly:

- `/api/meta` returns 147 tracked shipments, 373 open incidents, archived through
  `2026-09-17T09:44:35Z`, read from the real table.
- `/` returns `index.html` as `text/html; charset=utf-8` with the `max-age=60` the publish script put
  on the object, and `/assets/index-*.js` comes back as JavaScript with its year-long immutable
  header — the cache rules carried from S3 rather than restated in code.

Served locally from the same uploaded bundle against that same deployed function, **the archive build
renders in a browser**: 93 tile responses all 200, 147 markers, no console errors, the incident list
and the "archived through" stamp populated, and clicking a truck fetches `/api/shipments/{id}` and
draws its plan, geofences and travelled line — with the manifest panel correctly saying it is not
part of the public archive view. That had never been checked before; it is the one thing a green
build genuinely cannot tell you.

### What this costs, and what it does not

The ADR's title still holds and is untouched: **an archive file is still read exactly once, when it
lands.** S3's request count still grows with what the laptop produced and never with who is looking.
The table, the indexer, the fold and the lookup's answers are unchanged.

What the fallback gives up — every page view an invocation, a URL open to the internet rather than
reachable by one distribution alone, cache-control as advice rather than enforcement — is currently
**latent rather than real**, because no public traffic reaches it. It becomes real the moment AWS
verifies the account, and at that point CloudFront becomes available too, which is the better answer.
So the open function URL should be treated as a decision to revisit on the day verification lands,
not as the settled end state.

### Going back

Set `cloudfront_enabled = true` and apply. The distribution, the two origin access controls and the
API cache policy are recreated, the function URL returns to `AWS_IAM`, the site bucket grants reads
to the distribution instead of the Lambda's role, and `SITE_BUCKET` disappears from the function's
environment — which is what makes the code stop serving the page, with no code change.
