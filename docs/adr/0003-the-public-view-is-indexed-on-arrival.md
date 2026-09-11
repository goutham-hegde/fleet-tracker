# ADR 0003 — The public view is indexed on arrival

**Status:** accepted · 2026-09-11 · S22

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
