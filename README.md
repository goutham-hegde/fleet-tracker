# Fleet Tracking

[![CI](https://github.com/goutham-hegde/fleet-tracker/actions/workflows/ci.yml/badge.svg)](https://github.com/goutham-hegde/fleet-tracker/actions/workflows/ci.yml)

Real-time shipment and fleet tracking platform. Ingests location and status events from four
dissimilar sources, normalizes them into a canonical Kafka stream, and tracks shipments end to end
against SLA rules — with a live map dashboard.

> **Status:** in development — milestone M7 of M9 complete, M8 in progress, session 22 of 24.
> See **[PROGRESS.md](PROGRESS.md)** for the build log, decisions taken, and what is next.
> Architecture decision records land in `docs/adr/` as they are written.

## Why this exists

Location and status data in logistics arrives from vendor telematics units, driver phone apps,
carrier EDI feeds, and IoT sensors — each with a different shape, cadence, and reliability profile.
This platform normalizes all of it into one stream and one live view.

## Stack

| Concern | Choice |
|---|---|
| Services | Java 21 · Spring Boot 4.1.1 |
| Event stream | Apache Kafka (KRaft, no ZooKeeper) |
| Shipments & manifests | MongoDB (polymorphic documents + time-series collections) |
| Orchestration | Kubernetes (Kind locally) |
| CI/CD | GitHub Actions → ghcr.io → ArgoCD |
| Cloud | AWS free tier only — S3, CloudFront, Lambda, IAM/OIDC |
| Dashboard | React · Vite · MapLibre GL |

## Layout

```
services/       Spring Boot services: ingest, tracking, shipments, exceptions, dashboard API
functions/      AWS Lambda code: the public view's indexer and lookup
tools/          fleet-simulator — the synthetic data source
libs/events/    canonical event model shared by everything
libs/reference/ scheduled stops and distance maths, shared by two consumers
dashboard/      React + MapLibre live map
.github/        the CI workflow: tests on every pull request, images on every merge
deploy/         kustomize manifests + ArgoCD applications
infra/          Terraform for the AWS free-tier pieces
docs/adr/       architecture decision records
docs/schemas/   per-customer manifest JSON Schemas, loaded by a seed script
```

## Build

```bash
./mvnw verify        # unit tests (*Test) + Testcontainers integration tests (*IT)
```

No Maven installation required — the wrapper fetches it.

## Local cluster

```bash
./scripts/preflight.sh      # verify every prerequisite, with versions
./scripts/cluster-up.sh     # create the cluster (idempotent)
./scripts/cluster-stop.sh   # stop it, keeping data  <- use this between sessions
./scripts/cluster-start.sh  # resume it (~6s)
./scripts/cluster-down.sh   # destroy it
```

**End a work session with `cluster-stop.sh`, not `cluster-down.sh`.** Stopping releases all the
RAM and CPU (an idle cluster still burns ~660 MB and ~24% of a core) while keeping your data,
deployments and port mappings. Resuming takes about six seconds; recreating from scratch takes
90+ and loses everything inside.

Host ports are deliberately **not** the defaults, because 8080 and 27017 were
already taken on the development machine (the latter by a local `mongod`).
Using distinct ports also makes it impossible to connect to the wrong database
by accident:

| Service | Host address |
|---|---|
| Dashboard | `http://localhost:18080` |
| Ingest gateway | `http://localhost:18081` |
| Shipment service | `http://localhost:18082` |
| Dashboard API | `http://localhost:18083` |
| Kafka | `localhost:19092` |
| MongoDB (cluster) | `mongodb://localhost:37017` |

## Platform

Kafka and MongoDB run inside the cluster, in the `fleet` namespace.

```bash
./scripts/platform-up.sh    # deploy Kafka + MongoDB + the canonical topics (idempotent)
./scripts/seed-identity.sh  # load dispatch reference data the gateway resolves against (idempotent)
./scripts/smoke.sh          # prove both are reachable from the host and round-trip data
./scripts/platform-down.sh  # delete the namespace -- DESTROYS all Kafka and Mongo data
```

`platform-up.sh` is a wrapper around `kubectl apply -k deploy/base/platform` that also waits for
both readiness probes and for the topic-creation Job. It deploys Kafka and MongoDB and nothing
else: the manifests are split into a durable half (`deploy/base/platform`) and a disposable half
(`deploy/base/services`) precisely so that this script cannot touch the second one. Kafka and MongoDB together add roughly **625 MB**
on top of the idle cluster.

To free memory between sessions use `cluster-stop.sh`, not `platform-down.sh` — stopping the
cluster keeps the volumes, deleting the namespace does not.

### Topics

Created by a Job, never auto-created. Partition count is effectively permanent: it can be raised
but never lowered, and raising it changes which partition a key hashes to — which would break the
per-shipment ordering the design depends on.

| Topic | Partitions | Carries |
|---|---|---|
| `position.events.v1` | 12 | Every normalized position ping. The high-volume topic. |
| `shipment.derived.v1` | 6 | Arrivals, departures, ETA updates. |
| `status.events.v1` | 3 | Status changes — per stop, not per second. |
| `exceptions.v1` | 3 | SLA exceptions raised and cleared. |
| `ingest.dlq.v1` | 3 | Messages the gateway could not normalize, with the original payload intact. |

All topics are keyed by `shipmentId`, which is what guarantees per-shipment ordering without
paying for global ordering. The dead-letter topic is the exception and is deliberately unkeyed: a
message that failed to parse usually has no readable shipment id, and nothing consumes that topic
in order.

### Addresses

| From | Kafka | MongoDB |
|---|---|---|
| Inside the cluster | `kafka.fleet.svc.cluster.local:9092` | `mongodb.fleet.svc.cluster.local:27017` |
| From the host | `localhost:19092` | `mongodb://localhost:37017` |

Kafka advertises a different address on each listener because the two callers cannot use the same
one: a pod resolving `localhost` would find itself, and the host cannot resolve a `.svc` name at
all.

Kafka's console tools are not installed system-wide. `scripts/kafka-cli.sh` downloads the
distribution into a gitignored `.tools/` on first use and runs any tool from it:

```bash
./scripts/kafka-cli.sh kafka-topics.sh --bootstrap-server localhost:19092 --describe
./scripts/kafka-cli.sh kafka-console-consumer.sh --bootstrap-server localhost:19092     --topic position.events.v1 --from-beginning
```

## Simulator

The synthetic fleet. It drives trucks along four real freight lanes with plausible movement
physics, and it needs nothing else running — no cluster, no Kafka, no MongoDB.

```bash
./mvnw -pl tools/fleet-simulator -am package
java -jar tools/fleet-simulator/target/fleet-simulator-0.1.0-SNAPSHOT.jar
```

| Setting | Default | What it does |
|---|---|---|
| `fleet.simulator.tick-interval` | `1s` | Real time between ticks |
| `fleet.simulator.time-scale` | `1.0` | Simulated seconds per real second. `1.0` is real time; raise it to compress a twelve-hour lane into minutes |
| `fleet.simulator.trucks` | `8` | Trucks, spread round-robin across the lanes |
| `fleet.simulator.seed` | `20260829` | Master seed. The same seed replays the same run exactly |
| `fleet.simulator.repeat-routes` | `true` | Replace a truck with a fresh one when it finishes, so a demo never runs dry |

Time scale and tick interval are independent knobs: the tick interval sets how *often* events
happen, the time scale sets how much ground each tick covers. To watch four trucks run their whole
routes to completion in about two minutes:

```bash
java -jar tools/fleet-simulator/target/fleet-simulator-0.1.0-SNAPSHOT.jar \
  --fleet.simulator.time-scale=3000 \
  --fleet.simulator.tick-interval=5ms \
  --fleet.simulator.trucks=4 \
  --fleet.simulator.repeat-routes=false
```

Lanes are Delhi→Mumbai on NH-48 (long-haul), Hyderabad→Bengaluru (refrigerated pharma),
Bengaluru→Chennai (multi-stop LTL) and Nhava Sheva→Pune (port drayage). They differ in shape
on purpose — leg lengths, stop counts and dwell patterns are all different, so a bug cannot
hide behind uniformity.

### Output

The simulator emits four dissimilar wire formats — nested imperial telematics JSON, a terse mobile
app payload, X12 EDI 214 interchanges and reefer probe readings. Each goes to a *sink*, and sinks
compose: the console, capture files, and the ingest gateway over HTTP.

```bash
# Capture contract fixtures to disk (see docs/samples/README.md for the exact commands).
--fleet.simulator.emit.capture-dir=docs/samples

# Post everything to a running ingest gateway, the way real devices reach it.
--fleet.simulator.emit.http.enabled=true
--fleet.simulator.emit.http.base-url=http://localhost:18081
```

Faults — GPS noise, dropped and duplicated messages, corrupted payloads — are independently
switchable and drawn from the run's seed, so they replay. `--spring.profiles.active=chaos` turns
them all on. Captured samples of every feed, clean and corrupted, are committed under
[`docs/samples/`](docs/samples/).

## Ingest gateway

The platform's front door. Four external feeds arrive as HTTP requests in four dissimilar formats;
each becomes one of the two canonical envelopes and is published to Kafka, or — if it cannot be —
is published to the dead-letter topic with the original bytes and a reason attached.

```bash
./mvnw -pl services/ingest-gateway -am package
java -jar services/ingest-gateway/target/ingest-gateway-0.1.0-SNAPSHOT.jar
```

All four feeds normalize. Each breaks a different assumption, and the differences are the point:

| Endpoint | Feed | Names | Produces | Has to reconcile |
|---|---|---|---|---|
| `POST /ingest/telematics` | In-cab unit, nested imperial JSON | vehicle | position | mph and miles to metric; satellite geometry to a radius in metres |
| `POST /ingest/mobile` | Driver's phone, terse and unreliable | shipment | position or status | epoch millis; metres per second; duplicates and out-of-order backlogs |
| `POST /ingest/edi214` | Carrier back office, batch X12 text | many shipments | one status **per shipment** | positional text; no coordinates, only a city; hours of filing lag |
| `POST /ingest/reefer` | Trailer temperature probe | device | status | a device id is all it has — no shipment, no vehicle, no position |

An endpoint whose normalizer is not written yet answers `503`, not a rejection — the data is fine
and the gateway is unfinished, and dead-lettering good messages would bury the bad ones. No endpoint
is in that state today; the behaviour remains for the next feed added.

Four response outcomes, all with the body naming what happened:

| Status | Meaning |
|---|---|
| `202 ACCEPTED` | Normalized and durably on a canonical topic. |
| `202 PARTIAL` | Some of a batch became events and some did not. Only an EDI interchange can do this: the readable shipment statuses are published **and** the original interchange goes to `ingest.dlq.v1` whole, so neither the surviving events nor the fact of the damage is lost. Safe to replay, because event ids are derived from the payload rather than random — a replayed interchange regenerates the ids it produced before. |
| `202 DEAD_LETTERED` | Could not be normalized; the original is durably on `ingest.dlq.v1`, with the reason. Not a `400`, because resending identical bytes cannot produce a different result — a `400` would either lose the message or invite an infinite retry loop. |
| `503` | This platform is at fault: the broker did not acknowledge, or the feed has no normalizer. The only case where a retry can help. |

Identity resolution is what lets a feed that names only a vehicle produce an event keyed by
shipment. It reads **dispatch reference data from MongoDB**: an assignment says that a tractor
pulled a load, wearing a given set of devices, over a stated period. Load it with
`./scripts/seed-identity.sh` — an unseeded database is the one failure that looks like something
else entirely, because the gateway starts cleanly and then dead-letters all four feeds as
unresolvable.

The period is what a configuration file could not express. Every lookup asks *as of the instant the
source stated*, never as of now, so a trailer swapped onto a different tractor at noon and an EDI
batch filed four hours after the fact both resolve to what was actually true at the time. A message
that cannot be resolved is dead-lettered rather than guessed at: a wrongly attributed position is
worse than a missing one, because it moves a real shipment to the wrong place on a real map.

To watch the whole path end to end, start the gateway and point the simulator at it:

```bash
./scripts/seed-identity.sh            # once per cluster, before the first run
java -jar services/ingest-gateway/target/ingest-gateway-0.1.0-SNAPSHOT.jar &
java -jar tools/fleet-simulator/target/fleet-simulator-0.1.0-SNAPSHOT.jar   --fleet.simulator.emit.http.enabled=true --fleet.simulator.time-scale=60

./scripts/kafka-cli.sh kafka-console-consumer.sh --bootstrap-server localhost:19092   --topic position.events.v1 --from-beginning
```

## Tracking processor

The first service that *reads* a topic. It consumes `position.events.v1` as a consumer group and
turns a stream of measurements into state you can ask questions of: where a shipment is, where it
has been, which of its stops it has cleared, and when it will reach the next one.

```bash
./scripts/seed-identity.sh            # both, once per cluster
./scripts/seed-itinerary.sh
./mvnw -pl services/tracking-processor -am package
java -jar services/tracking-processor/target/tracking-processor-0.1.0-SNAPSHOT.jar
```

No HTTP port — it is driven by a topic, not by callers. A line every thirty seconds says what it has
done, because a consumer that is working perfectly is otherwise completely silent, which looks
exactly like one that has stalled.

It writes four collections and publishes three kinds of event:

| Collection | Holds |
|---|---|
| `position.history` | Every measurement, in a MongoDB **time-series collection**. Created explicitly at startup: inserting into a missing one silently produces an ordinary collection, with no buckets, no compression and no error. |
| `shipment.position` | One document per shipment, updated only by an event that is strictly newer *in event time*. Without that condition the mobile feed's out-of-order backlog would make the map jump backwards. |
| `geofence.state` | One document per shipment and stop: whether the vehicle is inside, when it crossed, and whether each event has been announced. In the database rather than in memory, because "exactly one arrival" has to survive a restart. |
| `shipment.eta` | One document per shipment: the current estimate and the learned travel speed behind it. |

**Arrivals are rediscovered, not reported.** No source sends "arrived". The processor watches
positions cross a circle drawn around each scheduled stop, with three independent defences against
GPS noise: entering and leaving use *different* thresholds, so no amount of wobble can flip the
state; a crossing is not believed until the vehicle has stayed on that side for three minutes of
event time; and a fix whose own stated accuracy is a large fraction of the fence is not consulted at
all. The radius belongs to the stop — a 400 m distribution yard and a 120 m kerbside dock are not
the same size, and one value would either miss a truck parked at the far fence or catch traffic
passing the dock on the street.

**The ETA is deliberately quiet.** It is published only when the estimate has moved more than two
minutes from the last one announced, so a truck holding its pace says nothing for hours and every
message that appears is news. Two things make that possible without the estimate going stale:
distance is measured afresh on every fix and never smoothed, and speed is a time-decayed average of
how fast the truck goes *while it is moving* — averaging in the zeros would let a five-minute red
light add an hour to a three-hour estimate and then take twenty minutes to unwind. Distance is the
straight line inflated by 18%, because roads bend; that figure is a stated assumption, and is where
a routing service would go.

Everything the processor concludes is published to `shipment.derived.v1`, keyed by shipment like
every other topic here. Event ids are **derived** — an arrival from the shipment, stop and crossing
instant; an estimate from the position that caused it — so a redelivered message regenerates the id
it had the first time. That is what makes it safe to publish before recording that you published:
the failure that survives a power cut is a duplicate that every consumer already recognises, rather
than an arrival that silently never happened.

To watch the whole platform at once, run all three:

```bash
java -jar services/ingest-gateway/target/ingest-gateway-0.1.0-SNAPSHOT.jar &
java -jar services/tracking-processor/target/tracking-processor-0.1.0-SNAPSHOT.jar &

# Full routes end to end in about five minutes. Four trucks keeps the HTTP sink under its
# ceiling, and repeat-routes=false lets the run finish.
java -jar tools/fleet-simulator/target/fleet-simulator-0.1.0-SNAPSHOT.jar   --fleet.simulator.emit.http.enabled=true --fleet.simulator.time-scale=300   --fleet.simulator.tick-interval=200ms --fleet.simulator.trucks=4 --fleet.simulator.repeat-routes=false

./scripts/kafka-cli.sh kafka-console-consumer.sh --bootstrap-server localhost:19092   --topic shipment.derived.v1 --from-beginning
```

## Shipment service

Everything above is about where a truck is. This is about what is in it.

A **manifest** is the paperwork for a load, and its shape is a function of the freight. A pharma
cold-chain consignment carries a custody chain, a batch number and the temperature band it must
stay inside. A retail replenishment carries purchase orders, a pallet count and a booked delivery
window that it must not arrive *before*. A part-load carries a GST e-way bill and a freight class.
A parcel carries a tracking number, a service level and a PIN code. Between the four of them there
is not one shared field.

All four live in **one MongoDB collection**. A manifest is a small typed envelope — shipment,
customer, freight mode, schema version, timestamp — wrapped around a body this service never
interprets:

```bash
./scripts/seed-manifest-schemas.sh          # load the four customer contracts
./mvnw -pl services/shipment-service -am package
java -jar services/shipment-service/target/shipment-service-0.1.0-SNAPSHOT.jar

curl -X POST localhost:18082/manifests -H "Content-Type: application/json" -d '{
  "shipmentId": "SHP-BOM-0004",
  "customerId": "QUICKSHIP",
  "mode": "PARCEL",
  "body": {
    "trackingNumber": "QS4471028893IN",
    "serviceLevel": "NEXT_DAY",
    "weightKg": 1.85,
    "recipient": { "name": "A. Krishnan", "pincode": "600028" }
  }
}'
```

The body is open, but it is not unchecked. Every customer has a **JSON Schema** on file, kept in
`docs/schemas/manifests/` and loaded into the database rather than compiled into the service, and
every write is held to it:

```json
{
  "reason": "The manifest does not satisfy the schema on file for this customer",
  "schemaVersion": "2026-09-04",
  "violations": [
    { "field": "/temperature/maxC", "message": "must have a maximum value of 25", "constraint": "maximum" }
  ]
}
```

That distinction is the reason this data is in MongoDB rather than in a relational table.
Flattened, the four shapes are roughly forty mostly-null columns; normalised into an
attribute-value table they lose their types and need a join per field. Kept as documents they stay
queryable — including on fields the service was never told about:

```javascript
db.manifests.find({ "body.freightClass": "85" })
db.manifests.find({ "body.recipient.pincode": "600028" })
db.manifests.find({ "body.temperature.maxC": { $lte: 8 } })
```

And because the schemas are data, onboarding a customer or accepting a new field from an existing
one is an insert, not a release. What prevents that openness from becoming a junk drawer is that
every schema sets `additionalProperties: false`: a manifest carrying `servicelevel` next to
`serviceLevel` is rejected, naming both.

Two layers do the checking, because neither can do the other's job. The per-customer schema is
applied by the service, which is the only place that knows which customer sent what. The shared
envelope is enforced by MongoDB itself, which is the only thing still watching when a migration
script or a `mongosh` prompt writes to the collection directly:

```
> db.manifests.insertOne({ _id: "SHP-ROGUE", mode: "TELEPORTATION", ... })
MongoServerError: Document failed validation
```

## Exception service

Everything above describes freight. This judges it.

The service reads positions, reefer readings and the platform's own derived events, and applies
five SLA rules. Each rule needs a different kind of evidence, which is why there are five rather
than one mechanism repeated:

| Exception | Raised when | Evidence it needs |
|---|---|---|
| `TEMPERATURE_EXCURSION` | a cold-chain load sits outside its agreed band for longer than the customer's tolerance | the band, read out of the manifest |
| `UNPLANNED_STOP` | stationary away from any scheduled stop past a threshold | the itinerary, to know where stopping is legitimate |
| `ROUTE_DEVIATION` | further from the planned path than tolerance allows, and staying there | the planned path as geometry |
| `LATE_ARRIVAL` | the projected arrival passes the booked delivery window | an ETA — this one fires *before* anything has gone wrong |
| `SIGNAL_LOSS` | nothing has been heard from a shipment for too long | a timer, because no message will ever arrive to trigger it |

```bash
./scripts/seed-manifests.sh    # the rules read customer commitments from manifests
java -jar services/exception-service/target/exception-service-0.1.0-SNAPSHOT.jar
```

**Every exception clears.** An alert that can only ever fire is one that gets muted, so each rule
detects the recovery as well as the breach, and both are published:

```
raised TEMPERATURE_EXCURSION for SHP-HYD-0002: 31.7C is outside the agreed 2.0 to 8.0C
band, peaking at 24.3C outside it, and has been for 30 minutes
cleared TEMPERATURE_EXCURSION for SHP-HYD-0002 after PT1H40M (RECOVERED)
```

The two messages share an **incident id**, derived from the type, the shipment, the stop and the
instant the condition began — never from the current time and never random. That is what pairs a
clear with its raise even when the two are published by different processes either side of a
restart, and it is why "exactly one incident" survives a crash.

**The SLA lives in the manifest, not in this service.** The temperature band above is MediVault's,
and so is the thirty-minute tolerance — their judgement about their own product. The platform
reserves a couple of locations inside the otherwise-untyped manifest body; a customer who wants a
term enforced puts it there, and one who does not simply has no such field and is never judged
against it. Of the four seeded customers only the retail lane books a delivery window and only the
pharma lane is refrigerated, so two of the rules apply to one lane each. A carrier cannot be late
against a deadline nobody set.

Severity follows from what is known rather than from a table: the same excursion is critical when a
customer stated a band and merely a warning when the platform is only comparing against the
refrigeration unit's own setpoint, and the same breakdown is more serious when the trailer is
refrigerated.

**This is also where the argument for Kafka stops being theoretical.** The service reads the same
position stream the tracking processor reads, from the beginning, in its own consumer group — and
the tracking processor is entirely unaffected and does not know it exists. Adding a second reader
was configuration. With a queue, it would have been a change to the first consumer.

To see the rules fire, run the fleet with things going wrong with the **trucks** rather than with
the messages:

```bash
java -jar tools/fleet-simulator/target/fleet-simulator-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=disrupted --fleet.simulator.emit.http.enabled=true \
  --fleet.simulator.time-scale=150 --fleet.simulator.tick-interval=200ms \
  --fleet.simulator.trucks=4 --fleet.simulator.repeat-routes=false

mongosh mongodb://localhost:37017 --quiet \
  --eval 'db.getSiblingDB("fleet").exceptions.find().toArray()'
```

That profile breaks down trucks, slows them, sends them on detours, fails refrigeration units and
takes devices off the air — one problem per truck at a time, each of which resolves. It is
deliberately *not* the `chaos` profile, which damages messages instead: every message a disrupted
run produces is perfectly formed and completely accurate, and simply describes something bad.

## Dashboard API

The read side. Everything above writes; this is the only component that answers questions, and the
only one a browser talks to. It stores nothing and produces to no topic — everything it serves can
be rebuilt from what the other four services hold.

```bash
./mvnw -pl services/dashboard-api -am package
java -jar services/dashboard-api/target/dashboard-api-0.1.0-SNAPSHOT.jar
```

Needs the same Kafka and MongoDB as everything else, and serves on **18083**. With nothing else
running it starts cleanly and reports an empty fleet, which is the correct answer to "no trucks are
reporting" rather than an error.

| Endpoint | Answers |
|---|---|
| `GET /api/shipments` | Every shipment with a known position — one marker each, with its next stop, estimate, progress and open exceptions |
| `GET /api/shipments/{id}` | One shipment in full: the plan with what happened at each stop, the manifest, every incident, and a recent trail |
| `GET /api/exceptions?open=true` | The exceptions panel. `open=false` includes what has already cleared |
| `GET /api/meta` | What this service can see. The first thing to check when a map opens empty |
| `GET /api/stream` | Server-sent events: positions, arrivals, departures, estimates and exceptions as they happen |

```bash
curl -s localhost:18083/api/shipments | jq '.[0]'

# The live stream. -N matters: without it curl buffers and the stream looks dead.
curl -N localhost:18083/api/stream
```

A client loads a snapshot and then follows the stream. The stream deliberately carries no history —
it begins at the moment of subscription — because replaying a retained topic into a browser would
draw hours-old positions as though they were happening now.

Two things about it are the opposite of every other consumer in this platform, and both follow from
the same fact: each instance can only forward what it has itself received. Its Kafka consumer group
is unique **per instance** rather than shared, so two replicas do not each get half the fleet; and it
starts from the newest offset rather than the oldest.

## Dashboard

The map. React, Vite and MapLibre GL against the dashboard API, and the first part of this project
a person rather than a terminal is meant to look at.

```bash
cd dashboard
npm install
npm run dev            # http://localhost:5173, the origin the API already allows
```

```bash
npm run build && npm run preview   # the built assets, on http://localhost:18080
npm test                           # the store's update logic; no browser needed
```

Nothing needs configuring: the API's address defaults to `http://localhost:18083` and the basemap
is OpenStreetMap raster tiles, so a checkout runs against a locally started platform as it stands.
`.env.example` documents the two variables that change either.

**Start the platform first.** With no API reachable the page loads and says so in the status bar
rather than showing an empty map; with the API up but no simulator running it says that too, which
is a different problem with a different fix.

A marker carries four independent facts, which is what makes a fleet readable at a glance:

| On the marker | Means |
|---|---|
| Fill colour | What the truck is doing — moving, stopped, at a stop, delivered |
| Ring | The worst SLA exception currently open against it |
| Fade | Nothing heard from it for ten minutes. Old, not wrong |
| Arrow | Its heading |

`stopped` and `at a stop` are the same speed and completely different situations. The difference is
the platform's geofenced arrival rather than a threshold applied a second time in the browser, and
it is the one distinction on this map that the dashboard could not work out for itself.

Clicking a truck draws its plan: the route as booked (dashed, because a straight line between two
stops is not a road), the geofences as filled circles at their real radii, and the trail of where it
has actually been. The card beside it carries the live summary, names the reason when there is no
estimate — a truck parked at a dock has none by design, and a blank would look like a fault — and
ends with the manifest.

**The manifest panel has no idea whose manifest it is.** The four seeded customers share no fields
at all: a pharma consignment carries a drug licence, a batch and a chain of custody; a parcel
carries a tracking number and a recipient's pincode. One renderer draws both, deciding what to do
with each part of the body from its shape rather than from its name — an object becomes a titled
group, an array of objects becomes a table, an array of scalars becomes chips. That is the
polymorphic-manifest design reaching a screen: a fifth customer costs an inserted schema document
and no release, and a dashboard with a component per customer would have quietly taken that back.

The two exceptions are contract rather than customer knowledge. `temperature` and `deliveryWindow`
are the paths this platform reserves and the exception service reads, so those sections are marked
**enforced** — a temperature band the platform will raise a breach over and one that is decoration
otherwise look identical.

The panel on the right is every SLA exception across the fleet, fed by the same stream the markers
are, so a breach appears a Kafka hop and an SSE frame behind the rule that judged it. Resolved
incidents move down into their own short list with the duration the platform measured, because
every rule here raises *and* clears and a panel that only grew would show half of that.

**Delivered loads are hidden by default**, with a toggle in the status bar that always shows the
count. A delivered shipment keeps its last position for ever, so a repeating run stacks finished
markers on the depots they finished at — twenty minutes of one left 21 of them against 3 moving
trucks. Nothing is discarded: they stay in the store, stay counted, and come back with one click.

The page loads a snapshot and then follows the stream, which is the shape the API is built around.
It also re-fetches the snapshot every twenty seconds and on every reconnection, because the stream
carries no history: a browser that was away for thirty seconds has missed exactly the updates it can
no longer ask for.

## Running it in the cluster

Everything above runs the services as jars on the host, against a Kafka and a MongoDB inside Kind.
They can also run *as pods*, which is what the manifests in `deploy/` describe and what the one
command below applies.

```bash
./scripts/stack-up.sh         # cluster, platform, KEDA, images, seed data, deploy. ~5.5 min
```

The step it exists to make possible is the last one:

```bash
kubectl apply -k deploy/overlays/local
```

Twelve pods, ready in about half a minute on a warm cluster: Kafka, MongoDB, the five services, the
dashboard, and a simulated fleet. Every service answers on exactly the port it always has, because
the Kind cluster forwards 18081, 18082 and 18083 to the node in the same way it has forwarded
18080, 19092 and 37017 since M0.

```bash
kubectl get pods -n fleet
kubectl get hpa -n fleet -w        # the autoscaler, live
kubectl logs -n fleet deployment/tracking-processor -f
kubectl delete -k deploy/overlays/local    # remove the workloads; Kafka and Mongo data survive
```

### How the images are built

The five Java services are built by **Jib**, a Maven plugin that assembles an image from the
compiled classes with no Dockerfile and no Docker daemon of its own. All of its configuration lives
once in the root POM, so the five images cannot drift apart in base layer, JVM flags or user; a
service module declares the plugin and states nothing. The dashboard is the exception — static
files and an nginx, with no main class to point Jib at — so it has a two-stage Dockerfile.

```bash
./scripts/images.sh    # build all eight, then load them into the Kind node
```

`kind load` is the part that is easy to forget. Kind runs Kubernetes inside a container with its own
image store, so an image sitting in the host's Docker daemon is invisible to it. The local overlay
sets `imagePullPolicy: Never` for exactly this reason: a forgotten load then fails immediately with
`ErrImageNeverPull`, instead of spending a minute trying to pull from a registry that has nothing to
do with the mistake.

Images are tagged `0.1.0-SNAPSHOT` and nothing produces `latest`. A moving tag is the wrong thing
for a cluster to hold — two nodes can disagree about what it means, and a rollback has nothing to
roll back to.

### Probes

Every workload answers two questions, and they are deliberately different questions.

| Probe | Endpoint | Contains |
|---|---|---|
| liveness | `/actuator/health/liveness` | the application's own state, and nothing external |
| readiness | `/actuator/health/readiness` | that, plus MongoDB |

Liveness decides whether to *restart* a container; readiness decides whether to *send it traffic*.
Answering both with one number is the classic way to turn a slow database into an outage: the
combined check fails, every pod is restarted at once, and the restart storm outlasts the blip.

The ingest gateway is the case that shows why readiness includes the database. Without MongoDB it
still parses and still answers — but identity resolution fails for every message, so all four feeds
are dead-lettered as unresolvable while the service reports itself in perfect health. A service that
cheerfully rejects everything it is sent is worse than one that is plainly out of rotation.

Both Kafka consumers gained an HTTP port for this and have no endpoints on it. A container with no
port can only be probed by running a command inside it, which establishes that a JVM exists and
nothing whatever about whether it is still consuming.

### Autoscaling on consumer lag

`tracking-processor` scales on **Kafka consumer lag** — how many records the group has not read yet.
That is the honest measure of whether a consumer is keeping up: one at 20% CPU that is 400,000
records behind is failing, and one at 90% CPU three records behind is fine.

Kubernetes cannot see that number; its built-in autoscaler reads CPU and memory. **KEDA** is the
piece that closes the gap — an operator that polls Kafka's own consumer-group offsets and feeds the
answer to an otherwise ordinary HorizontalPodAutoscaler that it creates and owns.

```bash
./scripts/keda-up.sh               # install the operator (pinned version)
kubectl get scaledobject,hpa -n fleet
```

The rule is in `deploy/overlays/local/tracking-processor-scaledobject.yaml`: 500 records of lag per
pod, one replica minimum, four maximum. Four rather than more has two separate ceilings behind it —
the topic has 12 partitions and a partition has exactly one consumer per group, so a thirteenth pod
would idle for ever whatever the lag said; and this is a laptop.

Measured, under six simulators pushing far more than the demo does:

| | lag | replicas |
|---|---|---|
| 09:49:22 | 467 | 1 |
| 09:49:58 | 4,265 | 3 |
| 09:51:11 | 8,909 | 4 |

Worth knowing: a *burst* does not scale anything. A 2,880-record backlog was cleared by the single
running pod before the autoscaler's loop came round, which is the correct outcome — scaling out to
meet a backlog that has already gone would be pure churn, since removing a consumer again forces a
group rebalance.

## Continuous integration

Every push and every pull request is built by a machine that has never seen this project, from a
clean checkout — which is the only honest test of whether the repository is self-contained. The
workflow is `.github/workflows/ci.yml`.

| Job | What it runs | Why it is separate |
|---|---|---|
| Java build and tests | `./mvnw verify` | `verify`, not `test`: it also runs the nine `*IT` classes, which start a real Kafka and a real MongoDB in containers |
| Dashboard build and tests | `npm ci`, lint, `vitest`, `vite build` | Different language, different toolchain, different reasons to fail |
| Kustomize overlays render | `kubectl kustomize` over all three layers | Catches a resource listed but missing, or a patch matching nothing, in five seconds and with no cluster |
| Publish images to ghcr.io | Jib and one `docker build` | Only from `main`, and only if the three above passed |
| Stamp the deployed tag | `scripts/set-image-tag.sh` and a force-push to `deploy` | The only job that writes to the repository, and it never touches `main` |

The three test jobs run at once, so the wall-clock cost is the slowest rather than the sum. They are
separate rather than one long script because they fail for unrelated reasons, and a failure named
"Dashboard" is worth more than a failure at line 340 of a single log.

The integration tests are the reason this runs where it does: Testcontainers needs a Docker daemon,
and GitHub's Ubuntu runners have one already, so nothing in the workflow installs or configures
Docker.

### Publishing

Publishing lives in the same workflow as the tests, as a fourth job that `needs` the other three.
That is the gate: `needs` is a hard dependency, so a failing test does not merely mark a pull
request red — it makes the image that would have been built impossible. A separate workflow would
have to watch this one finish and decide for itself, which is the same rule written twice in a
place where the two copies can disagree.

```
ghcr.io/goutham-hegde/fleet-tracker/<name>:<commit sha>
```

Eight images, tagged with the full commit SHA and never `latest`. A moving tag cannot be rolled back
to and cannot answer "what is actually running", because two machines can hold different images
under the same name; a SHA names exactly one commit, so a running pod's image reference is a link
into the history of this repository.

The six JVM images are pushed by Jib's `build` goal, which assembles layers and sends them to the
registry over HTTPS with **no Docker daemon involved at all** — the publishing job is an ordinary
Maven run rather than a privileged one. The same POM builds them locally with `dockerBuild` instead;
the goal is the only difference between a laptop image and a published one.

```bash
./mvnw -Pimages  -DskipTests package    # into the local Docker daemon (scripts/images.sh)
./mvnw -Ppublish -DskipTests package -Dimage.tag=$(git rev-parse HEAD)   # to ghcr.io
```

There is no registry password anywhere in this repository and there should never be one. The job is
handed a token by GitHub that exists only while it runs and can only touch this repository's
packages; `docker login` writes it to `~/.docker/config.json`, and Jib reads that same file.

Every image carries the OCI annotation `org.opencontainers.image.source`, which is what makes
ghcr.io link a published package back to this repository rather than leaving it orphaned, plus
`org.opencontainers.image.revision` — the commit it was built from, readable with `docker inspect`.

### The gate

`main` is protected: the three test jobs are required, force-pushes and deletion are refused, and
the rule applies to the repository owner too. Without that last part it is advice rather than a
gate, since the person most likely to merge something red at midnight is the person who wrote it.

It has been exercised rather than assumed. A pull request that deliberately broke the shared JSON
configuration ran red and GitHub reported it as `BLOCKED`; it was closed rather than merged. What
that experiment also showed is worth keeping: the regression was caught seven minutes into the
build by the *simulator's* tests, not by the module that owns the setting, because every event class
carries its own `@JsonInclude` annotation and a class-level annotation beats a mapper default. There
is now a test in `libs/events` that fails in seconds instead.

## Continuous delivery

A commit merged to `main` ends up running on the cluster with nobody touching a terminal. The
awkward part is that the cluster is a Kind node on a laptop behind a home router: it has no public
address, and nothing on GitHub's runners can open a connection to it. Forwarding a port so that a
build machine on the internet could administer a home network's Kubernetes API would be a bad trade
even if it were easy.

So the direction is reversed. **ArgoCD runs inside the cluster and polls this repository outbound**,
applying whatever it finds. The only connection involved is the same one a `git pull` makes, and it
is made from the laptop. The laptop can be asleep for a day and will catch up when it wakes.

```
merge to main
   ↓
CI: tests → publish eight images to ghcr.io, tagged with the commit SHA
   ↓
CI: reset the `deploy` branch to that commit, stamp the tags into
    deploy/overlays/gitops, force-push
   ↓                                            (github.com — no inbound connection)
ArgoCD, inside the cluster, polls `deploy` every three minutes
   ↓
kubectl apply, by a controller, from the manifests in this repository
```

### Why a `deploy` branch

`main` is protected, and the protection applies to the Actions token exactly as it applies to a
person, so the publish job **cannot commit to it**. The usual GitOps move — CI rewrites the image
tag in an overlay and commits it back — is therefore closed off unless the gate is weakened, and
weakening the gate to make deployment convenient is the wrong way round.

The `deploy` branch is *reset* to the merged commit on every publish and carries exactly one extra
commit, the one that stamps the tags. It is never a second, divergent copy of the manifests: it is
`main`, plus the answer to "which build". Its history is rewritten every time and nothing reads that
history.

It also breaks the loop this design otherwise has. CI runs on pull requests and on pushes to `main`;
nothing triggers on `deploy`. Without that, the pipeline would publish an image, commit the tag,
build its own commit, publish again, and keep going.

### Two overlays, differing in one thing

| | `deploy/overlays/local` | `deploy/overlays/gitops` |
|---|---|---|
| Images from | this laptop's Docker daemon, via `kind load` | ghcr.io |
| Tag | `0.1.0-SNAPSHOT`, which no registry has heard of | the full commit SHA |
| Pull policy | `Never` — a forgotten `kind load` fails loudly | `IfNotPresent` |
| Applied by | a person typing `kubectl apply -k` | ArgoCD, unattended |

**Apply only one of them at a time.** They name the same Deployments, so a cluster with both applied
would have the two image references overwriting each other on every apply.

Everything they share is either in the base or in one of two kustomize **components**
(`deploy/overlays/components/`): the simulated fleet and the lag autoscaler. A component is an
optional slice of configuration that several overlays can each switch on — the alternative was a
copy of each manifest in each overlay, and the copies would have drifted the first time the
simulator's arguments changed. Neither belongs in the base: a real deployment has real trucks, and
the ScaledObject is a kind the API server does not recognise until KEDA is installed.

### Bringing it up

```bash
./scripts/keda-up.sh      # first, or the sync fails with `no matches for kind "ScaledObject"`
./scripts/argocd-up.sh    # installs ArgoCD (pinned) and registers the application
```

```bash
kubectl get application -n argocd -w        # Synced / Healthy is the answer
kubectl describe application/fleet-tracking -n argocd
kubectl -n argocd annotate app/fleet-tracking argocd.argoproj.io/refresh=hard --overwrite   # poll now

# The web UI. A port-forward rather than a Kind port mapping, because those are fixed at cluster
# creation and adding one means recreating the cluster.
kubectl port-forward -n argocd service/argocd-server 8090:443
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
```

To deploy a particular build by hand — a rollback, say — point the overlay at its tag and push the
branch:

```bash
./scripts/set-image-tag.sh <commit sha>
```

### Two fields ArgoCD must be told to ignore

Automated sync with self-heal means ArgoCD puts the cluster back whenever it differs from git. That
is the point, and it is also a trap wherever something *else* legitimately writes to a field of an
object ArgoCD manages. Both cases here are in `deploy/argocd/application.yaml`:

- **`/spec/replicas` of the tracking processor.** KEDA writes it. Without the exception, a scale-out
  to three pods reads as drift from the base's `replicas: 1` and self-heal scales it straight back —
  once per reconcile loop, for as long as the fleet stays busy. The autoscaler would be installed,
  correct, and entirely without effect.
- **`/spec/volumeClaimTemplates` of the two StatefulSets.** Nobody writes these; the API server
  *defaults* them, adding a `volumeMode` and a `status` that the manifests do not state. So the
  object read back is never the object sent. Normally the next sync settles such a difference, but a
  StatefulSet's claim templates are immutable — only `replicas` may change in place — so ArgoCD
  would re-sync, succeed, find the same difference, and never once report `Synced`.

## AWS

The platform runs on the laptop. AWS holds only what can exist there at **$0.00** under the
always-free allowances: IAM, a budget, a few megabytes of S3, a small DynamoDB table, and Lambda
behind CloudFront. Nothing that could run Kafka or MongoDB is free, so there is no cloud cluster and no
cloud overlay. The reasoning and the prices are in
[ADR 0001](docs/adr/0001-aws-at-zero-dollars.md).

Two Terraform stacks under `infra/`:

| Stack | Holds | State |
|---|---|---|
| `infra/bootstrap` | A zero-spend budget, then the state bucket, which depends on it | A local file (gitignored). A stack cannot keep its state in a bucket it is about to create |
| `infra/cloud` | Everything else: the GitHub OIDC provider and the CI role (S20); the cluster's OIDC provider, the archive bucket and two pod roles (S21); the public view's table, two functions, site bucket and CloudFront distribution (S22) | `s3://fleet-tracker-tfstate-<account>/cloud/`, locked with a lock file in the bucket |

**The budget counts gross cost.** On AWS's credit-based plans, usage is paid from credits first, so
the bill reads $0.00 while real resources are being consumed. The budget excludes credits and emails
at the first cent, which is what separates "within the free allowance" from "being paid for by
credits that will run out".

### No stored credentials

**GitHub Actions** assumes the `fleet-tracker-github-actions` role through OIDC. The job asks GitHub
for a signed token describing the run, and AWS exchanges it for credentials that expire within the
hour. The role's trust policy admits exactly one subject,
`repo:goutham-hegde@181922465/fleet-tracker@1345975529:ref:refs/heads/main`. Pull requests, other
branches, forks and every other repository on GitHub are refused. The numbers are the owner's and
the repository's ids. GitHub includes them in this repository's subject by default, and they are
never reused, so a same-named repository created after this one was deleted would still be refused.
The value comes from GitHub rather than being typed by hand:

```bash
gh api repos/goutham-hegde/fleet-tracker/actions/oidc/customization/sub --jq .sub_claim_prefix
```

CI checks this from both sides. A push to `main` must become the role. A pull request must be
refused, and the check first confirms that its own subject differs from the trusted one *only* in
being a pull request, because a policy naming a subject in the wrong format refuses everything and
looks exactly like one that works. Its only permissions are the public view's publishing job's
(S22), and each names a single resource: write the site bucket, invalidate the distribution, replace
the two functions' code.

**The laptop** signs in with `aws login`, which issues short-lived credentials from a console
session. No access key is written anywhere.

**Pods in the Kind cluster** use the same exchange GitHub does, with the cluster as the identity
provider ([ADR 0002](docs/adr/0002-the-cluster-as-its-own-identity-provider.md)). The API server
signs service-account tokens as `https://fleet-tracker-oidc.s3.ap-south-1.amazonaws.com`, a bucket
whose only public objects are the discovery document and the cluster's public key. IAM trusts that
issuer, and two roles each trust exactly one service account: `fleet:archiver` may write under
`archive/`, and `fleet:archive-replay` may read there. Neither may delete. Kind generates a new
signing key for every cluster, so **run `./scripts/aws-link.sh` after creating one**. Until then STS
refuses every pod token with `InvalidIdentityToken`.

### Bringing it up

One-time manual setup: create the account, put MFA on the root user, allow IAM users to see billing,
create an IAM user with `AdministratorAccess` and MFA, then:

```bash
aws login --region ap-south-1
aws configure set region ap-south-1      # aws login does not save the region
cp infra/bootstrap/terraform.tfvars.example infra/bootstrap/terraform.tfvars   # set budget_email

./scripts/infra-up.sh --plan             # changes nothing
./scripts/infra-up.sh                    # bootstrap, then cloud, then the AWS_ROLE_ARN repo variable
./scripts/infra-down.sh                  # everything, in reverse order
```

`infra-up.sh` refuses to run as the root user, and is idempotent: on an unchanged account both
stacks report no changes. `infra-down.sh` destroys the cloud stack first, because its state lives in
the bucket that the bootstrap stack owns, and removes the budget last. The alarm is the last thing
to go for the same reason it was the first to exist.

### The archive

`services/archiver` copies the four canonical topics into S3 as gzipped NDJSON, one file per topic
per UTC hour of *ingestion* time:

```
archive/<topic>/dt=YYYY-MM-DD/hour=HH/p<partition>-o<offset>-<writtenAtMillis>.ndjson.gz
```

A file is closed once its hour has ended and it has been quiet for a minute, which keeps the number
of S3 writes (the thing that costs money) to about four an hour. Offsets are committed only behind
the oldest record not yet in S3, so a crash repeats records and never loses one. Positions expire
after three days and the other topics after thirty.

```bash
./scripts/aws-link.sh     # after every cluster creation: publish the key, write fleet/archive-destination
kubectl logs -n fleet deployment/archiver | grep -E "AWS identity|Archived|PAUSED"
./scripts/archive-replay.sh position.events.v1 2026-09-11T12:00:00Z                 # verify one hour
./scripts/archive-replay.sh exceptions.v1 <from> <to> exceptions.v1                 # republish a range
```

Replay runs as a Job under the read-only role. By default it only verifies: every line parses, sits
under the hour it claims and has an event id, and duplicates are counted by event id. Republishing
onto a topic has to be asked for, and republished records carry a `fleet.replayed-from` header that
the archiver skips, so a replay never doubles the archive.

### The public view

The live map cannot be public: its API runs on the laptop, which has no public address. So the public
address shows the archive instead, and says so on screen. The design is recorded in
[ADR 0003](docs/adr/0003-the-public-view-is-indexed-on-arrival.md).

```
archiver -> S3 archive/ --(file finished)--> index function --(folds it once)--> DynamoDB table
                                                                                     ^
browser -> CloudFront -+- /*     -> site bucket: the dashboard, built in archive mode  |
                       +- /api/* -> lookup function (function URL) --------------------+
```

- **The index function** (`functions/public-view`) is woken by S3 each time the archiver finishes a
  file. It reads it once and folds tens of thousands of lines into a few dozen rows: the newest fix
  per shipment, each announced arrival and departure, each incident. Every write is conditional, so
  indexing a file twice or out of order leaves the same table. Estimates are left out, because a
  forecast from a run that may have ended days ago is not information.
- **The lookup function** answers the dashboard's own four `GET`s in the live API's shapes. Plans
  come from the committed lane catalogue. It is reachable only through CloudFront, which signs its
  requests; the function URL refuses anything else.
- **The dashboard** is built a second way, with `VITE_ARCHIVE_MODE=true`: no live stream, a
  one-minute poll, "archived through" in place of "live", and OpenFreeMap's vector basemap, which
  needs no key.

Every part is chosen for how it bills. S3 is read once per archived file, so its requests grow with
what the laptop produced and not with visitors. Visitors cost CloudFront, Lambda and DynamoDB requests,
all inside allowances that do not expire, and the table's capacity is provisioned inside the free
25 units, so a flood of visitors is throttled rather than billed.

```bash
./scripts/public-publish.sh           # functions, then the dashboard; what CI runs on every merge
./scripts/public-backfill.sh          # index what was archived before the notification existed
./scripts/public-backfill.sh 2026-09-11   # or one UTC day
```

CI's `publish-public` job runs the first of these on every merge to `main`, behind the same test jobs
as the image publish.

## The demo path, without containers

The same platform, brought up as jars on the host rather than as pods. Kept because it is the
faster loop when a service is being *changed* — an edit and a restart, with no image build and
no rollout in between — and because it is what M5 was demonstrated with.

```bash
./scripts/cluster-start.sh    # if the cluster is stopped
./scripts/demo.sh up          # ~53s including a full Maven build
```

It waits for Kafka and MongoDB, builds the services and the dashboard, runs all four seed scripts,
clears the derived collections, starts the five services and the dashboard, checks the seeded
manifests against their customers' schemas, and starts a four-truck run with disruptions switched
on. Then it prints the URL and what to look at for the first sixty seconds.

```bash
./scripts/demo.sh status   # what is running, and where each log is
./scripts/demo.sh down     # stop it all. The cluster is left alone
./scripts/demo.sh reset    # clear the derived collections only (services must be stopped)
```

**Run `down` before `./mvnw verify`.** On Windows the repackage goal renames a jar that a live JVM
still holds open, and the failure names neither the service nor the reason.

The clearing step is not housekeeping. A stop arrived at and departed from is terminal, so
re-running the simulator over the same shipment ids leaves every marker reading `DELIVERED` — the
geofencer working exactly as designed, and a demonstration in which nothing ever happens.

```bash
./scripts/check-manifests.sh   # would the platform accept the manifests the platform seeded?
```

That one runs inside `demo.sh up` and is worth knowing about on its own. The seed script writes
manifests straight into MongoDB, which is the right shape for a seed — in a real deployment they
arrive from customers' order systems — but it means the service that owns the manifest contract
never sees them, and a seeded body can drift from its customer's committed schema with nothing
anywhere saying so. It had: three of the four customers were storing, reading and rendering data
that the shipment service would have rejected. This submits one manifest per customer and mode
through the real endpoint, which is the only check that can tell.


## Prerequisites

| Tool | Purpose |
|---|---|
| Java 21 | Services. Maven comes via the wrapper. |
| Docker | Runs the Kind cluster. **Allocate 10-12 GB** — 8 GB is not enough for Kafka + Mongo + several JVMs + ArgoCD. |
| kubectl | Ships with Docker Desktop. |
| kind | Local Kubernetes. `winget install Kubernetes.kind` |
| helm | Chart installs. `winget install Helm.Helm` |
| terraform | AWS free-tier stack (M8). 1.10 or later, for S3-native state locking. `winget install Hashicorp.Terraform` |
| aws | AWS CLI (M8). A release recent enough to have `aws login` (late 2025 onwards; 2.36 is in use here). `winget install Amazon.AWSCLI` |
| mongosh | MongoDB shell, for inspecting the database by hand. `winget install MongoDB.Shell` |
| Node 20+ | Dashboard. Developed against Node 24 and npm 11. |

`winget` updates the *user* PATH, which existing shells do not see until they
restart. `scripts/lib.sh` adds the install directories itself so the scripts work
in the same session.
