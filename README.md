# Fleet Tracking

Real-time shipment and fleet tracking platform. Ingests location and status events from four
dissimilar sources, normalizes them into a canonical Kafka stream, and tracks shipments end to end
against SLA rules — with a live map dashboard.

> **Status:** in development — milestone M0 of M9, session 3 of 24.
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
services/       five Spring Boot services
tools/          fleet-simulator — the synthetic data source
libs/events/    canonical event model shared by everything
dashboard/      React + MapLibre live map
deploy/         kustomize manifests + ArgoCD applications
infra/          Terraform for the AWS free-tier pieces
docs/adr/       architecture decision records
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
| Kafka | `localhost:19092` |
| MongoDB (cluster) | `mongodb://localhost:37017` |

## Platform

Kafka and MongoDB run inside the cluster, in the `fleet` namespace.

```bash
./scripts/platform-up.sh    # deploy Kafka + MongoDB + the canonical topics (idempotent)
./scripts/smoke.sh          # prove both are reachable from the host and round-trip data
./scripts/platform-down.sh  # delete the namespace -- DESTROYS all Kafka and Mongo data
```

`platform-up.sh` is a wrapper around `kubectl apply -k deploy/base` that also waits for both
readiness probes and for the topic-creation Job. Kafka and MongoDB together add roughly **625 MB**
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

Lanes are Chicago→Dallas (long-haul), Los Angeles→Denver (refrigerated), Atlanta→Columbus
(multi-stop LTL) and Houston→Laredo (border drayage). They differ in shape on purpose — leg
lengths, stop counts and dwell patterns are all different, so a bug cannot hide behind uniformity.

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
shipment. Until S8 it reads a fixed list of assignments from configuration under
`fleet.gateway.identity.assignments`, matching the simulator's default fleet.

To watch the whole path end to end, start the gateway and point the simulator at it:

```bash
java -jar services/ingest-gateway/target/ingest-gateway-0.1.0-SNAPSHOT.jar &
java -jar tools/fleet-simulator/target/fleet-simulator-0.1.0-SNAPSHOT.jar   --fleet.simulator.emit.http.enabled=true --fleet.simulator.time-scale=60

./scripts/kafka-cli.sh kafka-console-consumer.sh --bootstrap-server localhost:19092   --topic position.events.v1 --from-beginning
```

## Prerequisites

| Tool | Purpose |
|---|---|
| Java 21 | Services. Maven comes via the wrapper. |
| Docker | Runs the Kind cluster. **Allocate 10-12 GB** — 8 GB is not enough for Kafka + Mongo + five JVMs + ArgoCD. |
| kubectl | Ships with Docker Desktop. |
| kind | Local Kubernetes. `winget install Kubernetes.kind` |
| helm | Chart installs. `winget install Helm.Helm` |
| terraform | AWS free-tier stack (M8). `winget install Hashicorp.Terraform` |
| aws | AWS CLI (M8). `winget install Amazon.AWSCLI` |
| mongosh | MongoDB shell, for inspecting the database by hand. `winget install MongoDB.Shell` |
| Node 20+ | Dashboard. |

`winget` updates the *user* PATH, which existing shells do not see until they
restart. `scripts/lib.sh` adds the install directories itself so the scripts work
in the same session.
