# Fleet Tracking

Real-time shipment and fleet tracking platform. Ingests location and status events from four
dissimilar sources, normalizes them into a canonical Kafka stream, and tracks shipments end to end
against SLA rules — with a live map dashboard.

> **Status:** in development — milestone M0 of M9, session 1 of 24.
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
| Node 20+ | Dashboard. |

`winget` updates the *user* PATH, which existing shells do not see until they
restart. `scripts/lib.sh` adds the install directories itself so the scripts work
in the same session.
