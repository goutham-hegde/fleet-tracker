# ADR 0004 — At least once, made harmless by derived ids

**Status:** accepted · 2026-09-03 · S10 (the rule since S6; recorded here in S24)

## Context

Every service on this platform does two things with each message it handles: it tells Kafka
something (an event it derived) and it tells MongoDB something (the state that led to it, or the
fact itself). A pod can die between any two of those steps, and on this laptop pods die for real,
from out-of-memory kills, rescheduling, and `kill -9` in S23's test.

Three facts rule out the usual ways of making the two writes atomic:

- **Nothing spans Kafka and MongoDB.** Kafka's transactions cover Kafka writes and consumer offsets.
  They do not cover a document in a database.
- **MongoDB here is a standalone node.** It runs with no replica set, so it has no multi-document
  transactions and no change streams. A replica set of one would enable both, and it would also
  double the memory of the most expensive pod in a cluster that has 11 GB for everything.
- **Sources send the same thing twice anyway.** The driver app resends every message it did not see
  acknowledged, and any HTTP client retries a lost response. Even perfect atomicity inside the
  platform would not remove duplicates that arrive from outside it.

So duplicates will happen whatever the platform does. The question is whether they cost anything.

## Decision

**Every service processes messages at least once, and every id is derived from the facts it
describes, so a repeat is byte-identical to the original.**

- **Ids are name-based UUIDs, never random.** A source event's id comes from the feed, the device and
  the instant *the source stated*, never from when the gateway received it. An arrival's id comes
  from the shipment, the stop and the instant the truck crossed the fence. An estimate's comes from
  the fix that caused it, and an incident's from its type, shipment, stop and onset.
- **Publish to Kafka, then record in MongoDB.** If the process dies between the two, the restarted
  consumer sees the input again and publishes the same event with the same id. Recording first
  would lose it: the state would say the job was done, and nothing would ever revisit it.
- **"Exactly one arrival" is a statement about distinct event ids**, not about how many records
  carry them. That form of the claim is the only one that survives a power cut.
- **Consumers absorb repeats where it is cheap.** The partition guard skips a redelivered run at the
  start of an assignment, and a bounded in-memory set catches a phone's resend seconds later. Where
  a consumer's state is keyed by the derived id, a repeat overwrites itself.

## Consequences

- Retries, a batch that is partly valid (publish what parsed, dead-letter the whole original),
  archive replay and consumer restarts are all safe for the same reason. None of them needs its own
  de-duplication scheme.
- **A crash can cost a duplicate and never a loss.** S23's test killed six pods with `kill -9`,
  including the broker and the database: 148,349 event ids were produced and 148,349 stored. One
  document was stored twice, a phone's resend 187 ms after the original that reached a pod whose
  in-memory set had been emptied by the rebalance. That is the trade this record describes,
  observed exactly once, and the test reports it as a figure rather than a failure.
- `position.history` is a MongoDB time-series collection, and those cannot carry a unique index. A
  repeat that gets past the in-memory set is stored twice and differs only in `receivedAt`. Readers
  of raw history must tolerate that, and the ones on this platform do.
- Ids must never depend on anything that differs between two deliveries of the same fact: arrival
  time, a sequence counter that restarts on reinstall, a random value. S7 rejected the driver app's
  own `seq` for exactly this reason.

## Alternatives rejected

- **Kafka exactly-once (transactions with offsets committed in the same transaction).** It makes
  "consume, then produce to Kafka" atomic, and every service here also writes to MongoDB, which the
  transaction cannot include. It would add a transaction coordinator's cost and still leave the
  interesting gap open.
- **A transactional outbox** (write the event to a MongoDB table in the same transaction as the
  state, and have a relay publish it). This is the textbook answer, and it needs multi-document
  transactions, so a replica set, and a relay process on every service. That is a lot of machinery
  to prevent a duplicate that derived ids already make harmless.
- **Record first, publish second.** It makes duplicates rarer and losses possible. A loss is
  silent: the state is correct, the event never existed, and no consumer can tell.
- **Random ids with de-duplication by content.** Two records that happen to share every field are
  then indistinguishable from a repeat. The id stops identifying anything, and every consumer has to
  compare payloads instead.
- **A unique index on position history.** It is not available on a time-series collection. Using an
  ordinary collection to get one gives up bucketing and compression on the largest collection the
  platform has, to prevent a duplicate S23 measured at one in 148,000.
