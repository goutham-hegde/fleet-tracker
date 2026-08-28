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

All topics are keyed by `shipmentId`, which is what guarantees per-shipment ordering without
paying for global ordering.

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
