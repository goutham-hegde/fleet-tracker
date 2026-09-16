# ADR 0005 — One gateway, over HTTP, that dead-letters rather than refuses

**Status:** accepted · 2026-08-31 · S6 (extended in S7; recorded here in S24)

## Context

Four feeds bring the platform its facts, and they have almost nothing in common:

| Feed | Shape | Awkward because |
|---|---|---|
| Vendor telematics | Nested JSON per vehicle, imperial units, a GPS quality figure | Identifies a vehicle, not a shipment |
| Driver app | Terse JSON, epoch milliseconds, metres per second | Arrives out of order and repeats itself after a dead zone |
| Carrier EDI 214 | Batched X12 text, city and state | Many shipments per file, no coordinates, hours late |
| Reefer sensor | Device id and temperature | No position at all |

Three of the four are systems the platform does not control, such as a vendor's webhook or a
carrier's back office. None of them will be given Kafka credentials or a client library. Some of
their messages will be malformed, and the platform has to keep those rather than lose them, because
corrupt freight data is still evidence.

## Decision

**A single service, the ingest gateway, is the only writer to the source topics. Feeds reach it over
HTTP. It answers `202` for everything it has taken responsibility for, including messages it could
not use.**

- **One HTTP endpoint and one normalizer per feed.** The normalizers share no parsing code on
  purpose. A normalizer returns *normalized*, *rejected* or *partial* and never throws. Validation of
  the result happens once, centrally, so no normalizer's output can reach Kafka unchecked.
- **Everything comes out as one of two envelopes**, a position event or a status event, keyed by
  shipment id. Identity is resolved *before* the envelope exists, using the instant the source
  stated, because the shipment id is the partition key and so the platform's one ordering guarantee.
  A message that cannot be resolved cannot be represented, and is dead-lettered.
- **Request bodies are read as plain text.** Parsing happens in the normalizer, where a failure can
  still reach the dead-letter topic. The original bytes travel with the event as `raw`.
- **`202 ACCEPTED` or `202 DEAD_LETTERED`, never `400`.** A dead-lettered message is durably stored
  with its source and the reason in headers. `503` is kept for the two cases where sending the same
  request again can succeed: the broker did not acknowledge, or the feed has no normalizer yet.
- **The gateway waits for the broker's acknowledgement before it answers.** The response is the
  moment responsibility for a message changes hands.

## Consequences

- Every consumer reads two envelope shapes and never learns that EDI exists. A fifth feed is one new
  normalizer, and the rest of the platform does not change.
- The gateway holds no per-message state, so it can restart or run as several copies without
  changing behaviour. De-duplication belongs to consumers (ADR 0004).
- A damaged EDI batch publishes the shipments that parsed **and** dead-letters the whole original.
  That is only safe because a replay regenerates the same ids for what already went through.
- The gateway does not bend under load. In S23 twenty times the load moved its p99 from 24 ms to
  120 ms and it refused nothing; the ceiling was downstream.
- Feeds that talk Kafka natively still come through HTTP, which costs a hop they would not need.
  This platform has none.

## Alternatives rejected

- **Raw ingest topics** (sources, or an adapter, write payloads to Kafka and a stream job normalizes
  them). It adds a second copy of every message and a second place that can write malformed data,
  and it still needs something outside Kafka to accept an HTTP webhook. S6 chose the gateway as the
  single writer so that "everything on a source topic has been validated" is enforced in one place.
- **`400 Bad Request` for input that cannot be parsed.** Corrupt bytes are corrupt on every retry, so
  a `400` either loses the message or starts a retry loop that never ends. Either way the
  dead-letter topic would be empty of exactly the messages it exists for.
- **Binding bodies to typed records.** A malformed message would fail inside the framework before
  any of this code ran, and the caller would get a `400` the service never saw.
- **A shared parsing layer across feeds.** The feeds only look similar in a diagram. Code shared
  between the EDI parser and the driver-app parser would bend both, and a change for one would
  retest all four.
- **One dead-letter topic per feed.** Nothing reads the dead letters in order or in isolation, and
  each message names its source in a header, so filtering one feed takes a header check, not a
  separate subscription.
- **Making the input fit: a generic event for an unknown status code, or a reefer reading given the
  truck's last known position.** An unknown code should appear in the dead-letter topic as work to
  do, not blend into the canonical stream. Joining two feeds would have the gateway invent a fact, in
  the one component whose job is to normalize each feed faithfully. Joining is a consumer's job
  (S7).
