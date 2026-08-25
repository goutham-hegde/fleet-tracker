# Build Progress

Running log of how this platform gets built — what was decided, what was rejected, and what
surprised me along the way.

**Last updated:** 2026-08-25 · **Current position:** M0 Walking Skeleton, session S1 of 24 complete
· **Repo:** [goutham-hegde/fleet-tracker](https://github.com/goutham-hegde/fleet-tracker)

```
M0 ██░░░░░░░░  1/3 sessions    ← current
M1 ░░░░░░░░░░  0/2
M2 ░░░░░░░░░░  0/3
M3 ░░░░░░░░░░  0/3
M4 ░░░░░░░░░░  0/2
M5 ░░░░░░░░░░  0/3
M6 ░░░░░░░░░░  0/1
M7 ░░░░░░░░░░  0/2
M8 ░░░░░░░░░░  0/3
M9 ░░░░░░░░░░  0/2
                1/24 sessions
```

Milestones are **gated** — a milestone does not start until the previous one's exit criteria all
pass. Sessions are roughly 3-4 hours and each ends at a committable checkpoint.

---

## M0 — Walking Skeleton

**Capability:** the local platform exists and is reachable. Empty, but real.

- [x] **S1** — Tooling and repo skeleton
- [ ] **S2** — Canonical event model
- [ ] **S3** — Kafka and MongoDB in Kind

**Exit criteria**

- [x] `./mvnw verify` green on the aggregate build
- [x] `kubectl get nodes` shows a Ready node
- [ ] A message round-trips through Kafka via console tools from the host
- [ ] A document round-trips through Mongo via `mongosh` from the host
- [ ] Every event type serializes and deserializes losslessly under test

---

## M1 — Synthetic Fleet

**Capability:** realistic, multi-format event data on demand. De-risks everything downstream.

- [ ] **S4** — Simulator core: routes, movement physics, tick loop
- [ ] **S5** — Four source formats + fault injection

**Exit criteria**

- [ ] A simulated truck traverses a multi-stop route with plausible physics (unit-tested)
- [ ] All four source formats emit correctly at a configurable rate
- [ ] Each fault type can be switched on and is visible in the output
- [ ] Sample payloads captured to `docs/samples/` as contract-test fixtures

---

## M2 — Events Land

**Capability:** heterogeneous sources normalize into one canonical stream, correctly attributed.

- [ ] **S6** — Gateway + telematics normalizer + first Testcontainers test
- [ ] **S7** — Remaining normalizers + DLQ routing
- [ ] **S8** — Identity resolution (device → vehicle → shipment)

**Exit criteria**

- [ ] Simulator → gateway → `position.events.v1`, asserted by integration test
- [ ] All four formats normalize correctly against captured fixtures
- [ ] Malformed input lands in the DLQ **and nowhere else**; valid input never does
- [ ] An event carrying only a `deviceId` reaches Kafka with `vehicleId` and `shipmentId` populated

---

## M3 — Shipments Have State

**Capability:** the system knows where every shipment is, whether it is at a stop, and its ETA.
This is the core product — everything before it is plumbing.

- [ ] **S9** — Position persistence + time-series collection
- [ ] **S10** — Geofencing with dwell thresholds
- [ ] **S11** — ETA calculation

**Exit criteria**

- [ ] Time-series collection grows and current position tracks live under simulator load
- [ ] A scripted geofence crossing produces **exactly one** arrival and **one** departure
- [ ] ETA converges on approach and does not thrash on GPS noise
- [ ] Kill and restart the processor mid-run: no duplicate arrivals, no lost positions

---

## M4 — The Business Layer

**Capability:** polymorphic manifests and SLA exception detection — where the "why MongoDB"
argument becomes demonstrable rather than asserted.

- [ ] **S12** — Polymorphic manifests + per-customer JSON Schema validation
- [ ] **S13** — Five SLA rules with raise/clear semantics

**Exit criteria**

- [ ] All four manifest shapes persist and query correctly from one collection
- [ ] A manifest violating its customer's schema is rejected with a useful error
- [ ] Each injected fault raises exactly the expected exception
- [ ] Exceptions **clear** when the condition resolves — not just raise

---

## M5 — Demoable ⭐

**Capability:** a stranger can watch the system work, on a map, in real time. The milestone that
makes the project worth showing — everything after adds credibility, nothing after adds viability.

- [ ] **S14** — Query API + SSE stream
- [ ] **S15** — React + MapLibre map with live markers
- [ ] **S16** — Shipment detail + exceptions panel

**Exit criteria**

- [ ] `curl` on the SSE endpoint streams live position and exception events
- [ ] Trucks visibly move on the map in real time
- [ ] Clicking a shipment renders its manifest correctly for all four customer types
- [ ] An injected fault appears in the exceptions panel within seconds
- [ ] The full 60-second demo path runs start to finish without intervention

---

## M6 — Reproducible Deployment

**Capability:** the whole stack runs from manifests, not from seven terminal tabs.

- [ ] **S17** — Jib images, kustomize overlays, probes, limits, HPA

**Exit criteria**

- [ ] `kubectl apply -k deploy/overlays/local` brings the stack up healthy from scratch
- [ ] All pods pass probes; none OOMKill under simulator load
- [ ] The HPA scales `tracking-processor` when consumer lag climbs

---

## M7 — Hands-Off Delivery

**Capability:** commit → tested → built → deployed, with no manual step.

- [ ] **S18** — GitHub Actions CI + ghcr.io publishing
- [ ] **S19** — ArgoCD pull-based GitOps

**Exit criteria**

- [ ] A PR runs fully green, including integration tests against real Kafka and Mongo
- [ ] A merge to main publishes SHA-tagged images to ghcr.io
- [ ] Pushing a commit deploys to Kind **unattended**, with no inbound access to the laptop
- [ ] A deliberately broken test blocks the pipeline

---

## M8 — Cloud Presence

**Capability:** a public HTTPS URL, real IAM, archived events in S3 — at **$0**.

- [ ] **S20** — AWS account, budget alert, Terraform base, GitHub OIDC
- [ ] **S21** — Archiver writing partitioned events to S3
- [ ] **S22** — CloudFront + Lambda public demo

**Exit criteria**

- [ ] A GitHub Actions job assumes the AWS role with **zero stored credentials**
- [ ] S3 objects land under correct date/hour partitions and replay reads them back
- [ ] A public HTTPS URL serves the dashboard; Lambda lookup returns real data
- [ ] `terraform destroy` removes everything cleanly
- [ ] **AWS billing console reads $0.00**

---

## M9 — Defensible

**Capability:** hard questions answered with numbers and documents instead of hand-waving.

- [ ] **S23** — Load test + resilience (pod-kill, no event loss)
- [ ] **S24** — ADRs, README, scripted demo

**Exit criteria**

- [ ] Throughput, lag, and p99 figures captured in `docs/`
- [ ] Pod-kill test shows produced count **==** persisted count
- [ ] Four ADRs written, each naming the alternative rejected and why
- [ ] A stranger can clone the repo and run the stack from the README alone

---

# Session log

## S1 — Tooling and repo skeleton

**2026-08-25 · M0 · commits `69c5d14`, `c03377a`, `42058ce`, `4f4fa59`, `446ef80`**

Set up the multi-module Maven build, the local Kubernetes cluster, and the scripts that tie them
together. No application code yet, by design — S1 is scaffolding.

**Built**

- Parent aggregator POM on Java 21 with Surefire/Failsafe split (`*Test` vs `*IT`)
- `libs/events` module stub
- Script-only Maven wrapper pinned to Maven 3.9.16
- Single-node Kind cluster (`deploy/kind-cluster.yaml`) on Kubernetes v1.36.1
- `scripts/preflight.sh`, `cluster-up.sh`, `cluster-down.sh`

**Decisions**

| Decision | Rejected alternative | Why |
|---|---|---|
| Import the Spring Boot BOM in `dependencyManagement` | Inherit `spring-boot-starter-parent` | Keeps `libs/events` a plain library, free of Spring Boot's plugin configuration |
| Spring Boot **4.1.1** | 3.5.16 (the version the assistant knows well) | Latest GA. A portfolio project on a superseded major version is a poor signal. Cost: 4.x is past the assistant's training cutoff, so APIs get verified against build output rather than recall |
| Script-only Maven wrapper | Default wrapper with `maven-wrapper.jar` | Keeps a 63 KB binary out of the repo |
| Host ports 18080 / 19092 / 37017 | Defaults 8080 / 9092 / 27017 | 8080 and 27017 were already bound on the dev machine. Non-default ports also make it impossible to connect to the wrong MongoDB by accident |
| `scripts/*.sh` | A `Makefile` | `make` is not installed, and shell scripts are more portable on a Windows host |
| Single-node Kind cluster | Multi-node | The Docker VM is memory-constrained; extra nodes cost ~500 MB each and buy nothing locally |

**Surprises**

- **Maven Central's search API is stale.** It reported Spring Boot 3.5.3 as latest; the
  authoritative `maven-metadata.xml` showed 4.1.1. The `<release>` tag is also unreliable — it
  named `4.2.0-M1`, a milestone. Version checks now go against `maven-metadata.xml` with
  milestones and RCs filtered out.
- **A `mongod` was already running on 27017**, unrelated to this project. Left alone; the cluster
  moved to 37017 instead.
- **`mvnw` cannot regenerate itself** — the running JVM holds a file lock on `maven-wrapper.jar`.
  Had to invoke the Maven the wrapper had already downloaded under `~/.m2/wrapper/dists/`.
- **`$LOCALAPPDATA` is unusable as a bash path** (Windows backslashes). `scripts/lib.sh` converts
  it with `cygpath`. Related: winget updates the *user* PATH, which running shells never see, so
  the scripts add the install directories themselves.
- **`.gitattributes` was load-bearing from commit one.** Without forced LF on `mvnw`, Windows
  checkouts produce CRLF and Linux CI fails with `bad interpreter: /bin/sh^M` — a failure that
  would not have surfaced until M7.

**Also this session**

Added `cluster-stop.sh` / `cluster-start.sh` after measuring what an idle cluster actually costs:
~660 MB RAM and ~24% of a core with *nothing* deployed. Stopping releases all of it and resumes in
~6s; recreating takes 90+s and loses everything inside. Verified by round-tripping the cluster —
the `local-path-provisioner` pod returns in `Error` and self-heals in ~10s, so `cluster-start.sh`
waits for pods to settle rather than reporting success early.

Published to **[github.com/goutham-hegde/fleet-tracker](https://github.com/goutham-hegde/fleet-tracker)**
(public — required for ghcr.io to stay free and unlimited in M7). Verified `mvnw` landed as mode
`100755`, which is what will let the Linux CI runners execute it.

**Left open**

- Docker VM has ~7 GB; the stack targets 10-12 GB. `preflight.sh` warns on every run.
- The repository history was rebuilt so every commit is authored by `goutham-hegde`. Note that
  GitHub retains force-pushed commits as unreachable objects, still fetchable by direct SHA until
  it garbage collects.

---

## Next up

**S2 — Canonical event model.** Build out `libs/events`: `PositionEvent`, `StatusEvent`, and the
five derived events, with Jackson serialization, Bean Validation constraints, and the `raw`
passthrough field. Exit criterion is lossless round-trip serialization for every event type.

Needs no external input — can start immediately.
