# Performance and resilience

**Measured:** 2026-09-15/16 · S23 · `./scripts/load-test.sh`

What this platform does under load, where it stops keeping up and why, and whether a crash loses a
position. Every figure below came from one command against the Kind cluster on one laptop. None of
them is a claim about production hardware. They are a claim about this design, measured honestly,
with the reason for each ceiling identified rather than guessed.

## The machine

| | |
|---|---|
| CPU | Intel Core i5-12500H, 12 cores / 16 threads |
| Memory | 15.7 GB; the Docker VM may use 11 GB |
| Disk | One NVMe SSD (Micron 2400) |
| Cluster | Kind v0.32.0, one node, Kubernetes v1.36.1, Docker 29.7.2 on WSL2 |
| Platform | One broker (Kafka 4.3.1, KRaft), one MongoDB 8.0 with a 256 MB cache, one gateway, 1-4 tracking processors (KEDA), one each of the other services |

Everything shares that one node: the load generator, the broker, the database and nine JVMs.

## How the load is made

The load generator is the fleet simulator, run as a pod beside the platform, so every message is a
real feed's payload (telematics JSON, mobile JSON, EDI 214 text, reefer readings) for a truck the
gateway can resolve. Nothing downstream is bypassed: identity resolution, both Kafka writes,
geofencing, estimates and the exception rules all do their real work.

- A tick every 100 ms, each covering 30 simulated seconds, and each telematics unit on its default
  30-second cadence: one report per truck per tick. The phone, the reefer probe and the EDI batches
  bring it to **about eleven messages a second per truck** (measured), so the offered rate is set by
  the size of the fleet. 1,000/s is 91 trucks.
- In real-time terms a simulated truck is compressed 300 times, so 1,000/s is the traffic of about
  27,000 trucks reporting on these cadences in real time.
- Trucks are on the road within about 20 seconds of a step starting, so a step sees driving,
  arriving and dwelling in proportion (see the third mistake below).
- 128 senders post concurrently, each owning a fixed set of devices, so no device's messages are
  reordered in flight. A sender that cannot keep up drops rather than waits and counts what it
  dropped, which is what a device with a full buffer does.
- Before the steps, 60 unrecorded seconds of load warm the JVMs up.
- During a sweep the tracking processor's autoscaler **scales up as usual but does not scale
  down**, so no pod leaves mid-step. The capacity runs pin it at a fixed number of pods instead.

The demo simulator and the archiver are paused for the run, and the archiver is moved past
everything the test produced before it restarts, so none of it reaches S3 (ADR 0001).

## What is measured, and where

| Figure | Where it comes from |
|---|---|
| Accepted rate, HTTP p50/p99/max | The load generator times every request it sends. A 202 means the gateway has resolved the message and the broker has acknowledged it. Percentiles from a histogram with 0.1 ms buckets, reported as the bucket's upper edge |
| Positions in / stored per second | The broker's own offsets: the position topic's end offsets, and the tracking processor's committed offsets, before and after the step |
| Lag | `kafka-consumer-groups.sh --describe`, every few seconds: how many positions the tracking processor has not yet read |
| Drain | Seconds from the end of a step until the processor's lag reaches zero |
| Stored p50/p99 | Time from the gateway receiving a position to the tracking processor having stored it. Counted by each processor pod into fixed buckets (5 ms to 300 s, about 1.5x apart), read from every pod before the step and after its backlog has drained, and summed. So the figure covers every position the step offered, including the ones that waited in the backlog, and is reported as the upper bound of its bucket |
| MongoDB read/write | The server's own `opLatencies`: average time per read and per write operation during the step |

Three measurement mistakes were made and corrected on the way. None of the figures below comes from
a run that had any of them.

- **The first fleet never left the depot.** Every lane opens with one to two hours of loading at its
  origin, and the first sweeps ran at 5 simulated seconds a tick, at which that dwell outlasted a
  two-minute step. A parked truck is the cheapest message the tracking processor sees: no estimate,
  a geofence it is already inside. Those sweeps measured the easy path and overstated capacity. At
  30 simulated seconds a tick the fleet is moving within 20 seconds, the same 250/s costs twice the
  stored p99 and half as much again per MongoDB write, and that is the load measured here.
- **A windowed percentile went stale.** The stored p99 was first computed inside each pod by a
  Micrometer timer. Its window only moves when something is recorded, so a pod that had gone quiet
  reported the p99 of its last busy minute, 53 seconds, through a step in which nothing waited more
  than a few. Monotonic counters replaced it; they cannot go stale, and they add across pods. The
  same sweep showed the second half of the problem: a pod that the autoscaler removed mid-step took
  its counters with it, which is why scale-down is now held during a sweep.
- **Rates measured over a pod's lifetime understate.** The generator produces nothing while its JVM
  starts, so rates are taken over the step's configured length.

## Results: throughput and latency

One sweep, five steps of 120 seconds each, in `target/load/20260916T065328Z/`. The tracking
processor ran at **four pods throughout**: the cluster was already at four when the sweep began and
the scale-down hold kept it there, so this table is a fixed four-pod platform rather than a
demonstration of the autoscaler.

| Offered | Accepted | HTTP p50 | HTTP p99 | HTTP max | Dropped by the sender | Positions in | Positions stored | Peak lag | Drain | Stored p50 | Stored p99 | Mongo read | Mongo write |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100/s | 118.4/s | 11.7 ms | 24.4 ms | 172 ms | 0 | 115.0/s | 115.0/s | 12 | 3 s | 20 ms | 75 ms | 0.26 ms | 0.59 ms |
| 250/s | 268.0/s | 14.7 ms | 40.3 ms | 299 ms | 0 | 260.6/s | 260.6/s | 26 | 3 s | 50 ms | 100 ms | 0.28 ms | 0.65 ms |
| 500/s | 521.8/s | 21.7 ms | 72.7 ms | 539 ms | 0 | 507.5/s | 507.5/s | 1,158 | 3 s | 300 ms | 7.5 s | 0.31 ms | 0.65 ms |
| 1000/s | 1003.8/s | 27.2 ms | 105.2 ms | 718 ms | 0 | 976.9/s | 585.5/s | 54,770 | 88 s | 60 s | 120 s | 0.35 ms | 0.65 ms |
| 2000/s | 1687.7/s | 32.4 ms | 119.7 ms | 2,211 ms | 32,751 | 1655.0/s | 510.6/s | 144,570 | 392 s | >300 s | >300 s | 0.40 ms | 0.63 ms |

Nothing was refused, dead-lettered or unreachable at any rate.

**The gateway does not bend.** Twenty times the load moves its p99 from 24 ms to 120 ms, and it
accepts every message offered. Each of those milliseconds includes resolving the truck's identity
against MongoDB and waiting for the broker to acknowledge the write, because the gateway answers
only once the event is durable. Whatever the ceiling of this platform is, the front door is not it.

**MongoDB is not the constraint either.** Its own average write latency is 0.59 ms at 100 messages
a second and 0.63 ms at 2,000 — flat across the whole sweep, while the rate through it grew
twentyfold. The database is not what runs out.

**The tracking processor is the ceiling,** and it arrives between 500 and 1,000 positions a second.
At 500/s it still keeps up: it stores exactly what arrives, 507.5/s, and clears its backlog three
seconds after the load stops. At 1,000/s it takes 976.9/s and stores 585.5/s, and the difference
becomes a backlog growing at about 440 positions a second for the whole step.

**Where the tail comes from.** Stored p99 goes 75 ms, 100 ms, 7.5 s, 120 s. The first two are the
work itself; the last two are queueing, which is why the figure tracks the size of the backlog
rather than the cost of a write. The 500/s step is the one worth looking at twice, because its
average is healthy and its tail is not: 507.5/s stored against 507.5/s arriving, a three-second
drain, and a p99 of 7.5 seconds with a peak lag of 1,158. A backlog forms and clears inside the
step. Reporting the average alone would have hidden that completely.

**The exception service falls behind first,** and by more: its peak lag exceeds the tracking
processor's at every rate, ending at 158,432 against 144,570. It reads three topics in its own
group and is a single pod with no autoscaler, deliberately — its rules are about SLA breaches,
which are conclusions about hours, not seconds.

## Results: what one pod is worth

The sweep says where the platform stops keeping up. It does not say what a pod is worth, because a
consumer that is keeping up is measuring its offered rate, not its capacity. These runs pin the
tracking processor at a fixed number of pods and offer it more than it can take, so that what it
stores is its capacity.

| Processor pods | Offered | Positions stored | Peak lag | Drain | vs. one pod |
|---|---|---|---|---|---|
| 1 | 1000/s | 151.5/s | 100,872 | 343 s | — |
| 2 | 2000/s | 384.0/s | 194,327 | 303 s | 2.53× |
| 4 | 2000/s | 582.9/s | 134,627 | 150 s | 3.85× |

The four-pod figure, 582.9/s, was measured independently of the sweep's 585.5/s at a different
offered rate, which is the closest thing here to a repeated experiment.

**Scaling is real but sublinear, and the reason is visible in the same runs.** Everything shares one
node. At two pods the load generator delivered 2,011.7/s and dropped 226 messages; at four pods, on
the same machine at the same offered rate, it delivered 1,668.2/s and dropped 35,517. The extra
processor pods take CPU the generator needs. That is also why the sweep's 2,000/s step stored *less*
than its 1,000/s step, 510.6/s against 585.5/s: nothing gained capacity, one laptop lost it. On this
hardware the twelve partitions are not the binding limit — the node is.

## Results: a crash loses nothing

500 messages a second for five minutes, while six processes are killed outright. Not deleted —
`kill -9` to the container's first process, sent from the Kind node, so no shutdown hook runs,
nothing is flushed and no offset is committed on the way out. Deleting the pod would send `SIGTERM`
and allow a clean exit, which is exactly what this is not testing.

| t | Killed |
|---|---|
| 44 s | tracking-processor |
| 81 s | ingest-gateway |
| 134 s | tracking-processor |
| 164 s | **mongodb** |
| 210 s | **kafka** |
| 242 s | tracking-processor |

Afterwards every position event the run appended is read back off the topic and every document in
`position.history` is read out of MongoDB, and the two sets are compared by event id.

```
records appended to position.events.v1   148659
records read back                        148659      <- the comparison is complete
distinct event ids produced              148349
measurements in position.history         148350
distinct event ids persisted             148349
produced but not persisted                    0      <- nothing lost
persisted but never produced                  0
stored more than once                         1
set aside on tracking.dlq.v1                  0
processor caught up after                  202s
```

**Nothing was lost.** Every one of the 148,349 distinct events that reached the topic is in the
database, after the broker and the database themselves were killed mid-write. Nothing was
dead-lettered. The sender recorded 678 requests as unreachable — the gateway vanishing under them,
which is a device's problem and correctly counted as such rather than silently dropped.

The run before it, `20260916T075159Z`, is the same story at a different size: 149,735 produced,
149,735 persisted, 0 missing, 0 dead-lettered.

### The one repeat, in both runs

Each run stored exactly one document twice. Both are worth explaining rather than rounding away,
because the cause is the design, not a defect.

In both cases the duplicate came from the **mobile feed**, which resends a fix it never saw
acknowledged — a property that feed owns on purpose. Both copies reached Kafka at separate offsets
carrying the same event id, because ids are derived from the feed, the device and the instant the
source stated, never generated. And in both cases the resend landed shortly after a tracking
processor was killed — 14 seconds after in the first run, 18 seconds after in the second — so the
consumer group had just rebalanced and the pod that saw the second copy had an empty
`RecentEventIds`, the in-memory set that catches source duplicates. Nothing below that can catch it
either: `position.history` is a time-series collection, and those cannot carry a unique index.

The two documents differ only in `receivedAt` — 187 ms apart in the first run. Every measured field
is identical, which is the point of deriving the id: a repeat is a byte-identical repeat.
`shipment.position` is unaffected, because it upserts only if strictly newer in event time.

This is the trade the platform chose. Publishing to Kafka before recording in MongoDB, with no
transaction spanning both, means a crash costs duplicates and never losses; derived ids are what
make a duplicate harmless. So the test asserts what the platform promises — nothing lost, nothing
unexpected, nothing dead-lettered — and reports repeats as a figure beside it. A duplicate that was
*not* byte-identical would be a different matter, and would appear as an id persisted that was never
produced.

The first pod-kill run asserted zero duplicates as well, and failed on that one document. The
assertion was wrong, not the platform; it has been corrected, and this section is the record of why.

## Reproducing

```bash
./scripts/stack-up.sh --deploy          # images from this tree
./scripts/load-test.sh sweep            # 100 250 500 1000 2000 messages/s, 180 s each

# What was run here
LOAD_STEP=120 ./scripts/load-test.sh sweep
LOAD_STEP=120 LOAD_PROCESSORS=1 ./scripts/load-test.sh sweep 1000
LOAD_STEP=120 LOAD_PROCESSORS=2 ./scripts/load-test.sh sweep 2000
LOAD_STEP=120 LOAD_PROCESSORS=4 ./scripts/load-test.sh sweep 2000
./scripts/load-test.sh pod-kill         # 500/s for 300 s with six crashes
```

Results land in `target/load/<UTC timestamp>/`: `sweep.csv`, `samples.csv` (lag and replicas every
few seconds), each load generator's log, and for the pod-kill run `pod-kill.txt` beside the id lists
it compares — `produced.txt`, `persisted.txt`, `missing.txt`, `unexpected.txt`, `stored-twice.txt`.

The runs behind this page, in order: `20260916T065328Z` the sweep, `20260916T071700Z`,
`20260916T073002Z` and `20260916T074506Z` the three capacity runs, `20260916T075159Z` and
`20260916T081043Z` the two pod-kill runs.

A caveat on repeating this: the figures assume nothing else is competing for the machine. The
capacity runs above show how sharp that is — the same load generator lost a fifth of its throughput
to two extra pods on the same node. Building, browsing or running the dashboard during a sweep will
change the numbers.
