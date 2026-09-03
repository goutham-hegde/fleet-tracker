# Build Progress

Running log of how this platform gets built — what was decided, what was rejected, and what
surprised me along the way.

**Last updated:** 2026-09-03 · **Current position:** M3 in progress, session S9 of 24
· **Repo:** [goutham-hegde/fleet-tracker](https://github.com/goutham-hegde/fleet-tracker)

```
M0 ██████████  3/3 sessions    complete
M1 ██████████  2/2              complete
M2 ██████████  3/3              complete
M3 ███░░░░░░░  1/3              ← in progress
M4 ░░░░░░░░░░  0/2
M5 ░░░░░░░░░░  0/3
M6 ░░░░░░░░░░  0/1
M7 ░░░░░░░░░░  0/2
M8 ░░░░░░░░░░  0/3
M9 ░░░░░░░░░░  0/2
                9/24 sessions
```

Milestones are **gated** — a milestone does not start until the previous one's exit criteria all
pass. Sessions are roughly 3-4 hours and each ends at a committable checkpoint.

---

## M0 — Walking Skeleton

**Capability:** the local platform exists and is reachable. Empty, but real. **All five exit
criteria pass as of 2026-08-28; M1 is unblocked.**

- [x] **S1** — Tooling and repo skeleton
- [x] **S2** — Canonical event model
- [x] **S3** — Kafka and MongoDB in Kind

**Exit criteria**

- [x] `./mvnw verify` green on the aggregate build
- [x] `kubectl get nodes` shows a Ready node
- [x] A message round-trips through Kafka via console tools from the host
- [x] A document round-trips through Mongo via `mongosh` from the host
- [x] Every event type serializes and deserializes losslessly under test

---

## M1 — Synthetic Fleet

**Capability:** realistic, multi-format event data on demand. De-risks everything downstream.

- [x] **S4** — Simulator core: routes, movement physics, tick loop
- [x] **S5** — Four source formats + fault injection

**Exit criteria — all pass as of 2026-08-31; M2 is unblocked.**

- [x] A simulated truck traverses a multi-stop route with plausible physics (unit-tested)
- [x] All four source formats emit correctly at a configurable rate
- [x] Each fault type can be switched on and is visible in the output
- [x] Sample payloads captured to `docs/samples/` as contract-test fixtures

---

## M2 — Events Land

**Capability:** heterogeneous sources normalize into one canonical stream, correctly attributed.

- [x] **S6** — Gateway + telematics normalizer + first Testcontainers test
- [x] **S7** — Remaining normalizers + DLQ routing
- [x] **S8** — Identity resolution (device → vehicle → shipment)

**Exit criteria**

- [x] Simulator → gateway → `position.events.v1`, asserted by integration test
- [x] All four formats normalize correctly against captured fixtures
- [x] Malformed input lands in the DLQ **and nowhere else**; valid input never does — proved for
  all four feeds against `docs/samples/faults/`
- [x] An event carrying only a `deviceId` reaches Kafka with `vehicleId` and `shipmentId` populated
  — the reefer feed, asserted by integration test

**All four criteria pass as of 2026-09-02, against MongoDB-backed reference data. M3 is
unblocked.** S8 replaced the fixed configuration list with a temporal lookup: an assignment states
that a tractor pulled a load, wearing a given set of devices, over a period, and every lookup asks
as of the instant the source stated. A tractor that changes loads at noon, a trailer probe swapped
onto another vehicle, and an EDI batch filed four hours late all now resolve to what was actually
true at the time.

---

## M3 — Shipments Have State

**Capability:** the system knows where every shipment is, whether it is at a stop, and its ETA.
This is the core product — everything before it is plumbing.

- [x] **S9** — Position persistence + time-series collection
- [ ] **S10** — Geofencing with dwell thresholds
- [ ] **S11** — ETA calculation

**Exit criteria**

- [x] Time-series collection grows and current position tracks live under simulator load
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

## S2 — Canonical event model

**2026-08-27 · M0 · commits `13d3044`, `5087621`**

Built `libs/events`: the two envelopes every source normalizes into, the five events the platform
derives for itself, and the JSON configuration all of it travels under. This is the contract every
later service is written against, so it was worth getting the shape right before anything depends
on it.

**Built**

- A `sealed` `Event` hierarchy splitting into `SourceEvent` (normalized from a feed, carries the
  original payload) and `DerivedEvent` (a conclusion this platform reached, carries the id of the
  event that caused it). Sealing means a `switch` over an event in a consumer is checked by the
  compiler for exhaustiveness — a new event type becomes a build failure everywhere that forgot it.
- `PositionEvent` and `StatusEvent`, the two canonical envelopes, both carrying `RawPayload`.
- Value types `GeoPoint`, `LocationHint`, `TemperatureReading`, and enums `SourceSystem`,
  `StatusCode`, `ExceptionType`, `Severity`.
- `ShipmentArrived`, `ShipmentDeparted`, `EtaUpdated`, `ExceptionRaised`, `ExceptionCleared`.
- `EventJson`, the single shared Jackson configuration. Two services with independently configured
  mappers agree on field names and disagree on everything else, and the disagreement surfaces as a
  production deserialization failure rather than a compile error.
- 36 tests: round-trip for every type through the `Event` interface, byte-stability across repeated
  round trips, timestamp and duration formats, null omission, forward compatibility, and the
  constraint set.

**Decisions**

| Decision | Rejected alternative | Why |
|---|---|---|
| Jackson **3** (`tools.jackson`) | Jackson 2 (`com.fasterxml.jackson`), which the module already had | Spring Boot 4 auto-configures Jackson 3; Jackson 2 is only a compatibility path. The annotations are shared between both versions, so a model annotated for 2 and read by 3 compiles, runs, and silently ignores half the configuration. Worst kind of mismatch |
| `raw` is a `String` | A parsed JSON tree (`JsonNode`) | EDI 214 is not JSON — it is `~`-terminated segments of `*`-delimited text. A field that can only hold JSON cannot hold a quarter of the platform's input |
| `StatusEvent` is closed and typed | A `Map<String, Object>` attribute bag for whatever else a source sent | A map is untyped at compile time, round-trips lossily (a `Long` that fits in an `int` returns an `Integer`), and turns every consumer into string literals and casts. Anything unmodelled is already preserved verbatim in `raw` — the escape hatch exists once and does not need a worse second one |
| `StatusCode` holds no EDI 214 codes | Recording `AF`/`X1`/`D1` on the enum so the mapping lives in one place | The canonical model would then know about one of its four sources, and the next feed with a different vocabulary either distorts the enum or does not fit it. That table belongs in the EDI normalizer |
| `shipmentId` and `vehicleId` required on every source event | Nullable until enrichment fills them in | The shipment id is the Kafka partition key, and per-shipment ordering is the platform's one ordering guarantee. Identity resolution therefore happens *before* the envelope is constructed; an event that cannot be resolved is not representable and goes to the DLQ |
| Unknown JSON properties ignored | Fail on unknown properties | Within a topic version a producer must be able to add a field without every consumer being redeployed first, or no service can be deployed independently and separate consumers buy nothing. Adding a field stays compatible; removing or retyping one is a new topic version |
| Timestamps and durations as ISO-8601 strings | Epoch numbers | Epoch numbers lose sub-millisecond precision to floating point, are unreadable on a console consumer, and are ambiguous between seconds and milliseconds — which reliably puts events in 1970 or the year 57000. `PT47M13S` also states its own units; `2833` does not |
| `Duration dwell` computed on `ShipmentDeparted` | Let consumers subtract the arrival themselves | Detention time is billable. It should have exactly one definition, not one per consumer |
| Hibernate Validator only at test scope, with `ParameterMessageInterpolator` | Adding an EL implementation as a dependency | Keeps `libs/events` depending on `jakarta.validation-api` alone — the interfaces — so it imposes no validation implementation on its users. The EL-free interpolator handles the `{min}`/`{max}` substitution these constraints use |

**Surprises**

- **Spring Boot 4.1.1 manages two Jacksons at once.** The BOM carries `jackson-2-bom.version`
  (2.21.5, `com.fasterxml.jackson`) alongside `jackson-bom.version` (3.1.5, `tools.jackson`). The
  naming is the tell: Jackson 2 got the explicit `-2` suffix and a `spring-boot-jackson2`
  compatibility module, so the unsuffixed one is the default. The module had been written against
  the wrong one.
- **Jackson 3 moved date handling out of `SerializationFeature`.** `WRITE_DATES_AS_TIMESTAMPS` and
  `WRITE_DURATIONS_AS_TIMESTAMPS` no longer exist there; they are on a new `DateTimeFeature` enum.
  `serializationInclusion(...)` is likewise gone, replaced by `changeDefaultPropertyInclusion` taking
  a `UnaryOperator`. Both were found by decompiling the jar rather than by recall.
- **`jackson-datatype-jsr310` is obsolete on Jackson 3** — `java.time` support is built into
  databind as `tools.jackson.databind.ext.javatime`. One dependency removed.
- **Hibernate Validator 9 pulls no EL implementation**, and the Spring Boot BOM does not manage one,
  so `Validation.buildDefaultValidatorFactory()` would have failed at test time.
- **A parameterized test over a hand-written fixture list is a trap.** It proves things about the
  cases someone remembered, so "every event type round-trips" decays to "every event type someone
  wrote down" the first time the model grows. `Class.getPermittedSubclasses()` turns the sealed
  hierarchy into a runtime membership list, so the coverage test asks the model what exists instead
  of being told. The sealing bought exhaustiveness twice — once at compile time, once at test time.

**Left open**

- No integration tests yet (`*IT`); Failsafe reports "No tests to run". First one arrives in S6,
  when there is a Kafka to test against.
- Sample payloads are inline in `EventFixtures` rather than in `docs/samples/`. They move out and
  become shared contract fixtures in S5, once the simulator emits the real thing.

---

## S3 — Kafka and MongoDB in Kind

**2026-08-28 · M0 · commit `1e41d0b`**

Deployed the two stateful dependencies the rest of the platform is written against, and closed the
last two M0 gates. **M0 is complete**: the platform exists, is reachable from the host, and data
round-trips through both stores.

**Built**

- `deploy/base/` as a kustomize base — namespace `fleet`, plus a `kafka/` and a `mongodb/`
  directory. This is the layout M6's `kubectl apply -k deploy/overlays/local` will build on, so it
  was cheaper to start there than to restructure later.
- **Kafka 4.3.1** in KRaft mode as a single-replica StatefulSet on a 5 Gi volume, configured
  entirely through environment variables (the `apache/kafka` image maps `KAFKA_FOO_BAR` onto the
  `foo.bar` broker property, so there is no `server.properties` to maintain). Three listeners:
  `INTERNAL` for in-cluster clients, `EXTERNAL` for the Windows host, `CONTROLLER` for the raft
  quorum talking to itself.
- **MongoDB 8.0.29** as a StatefulSet on a 5 Gi volume, with an explicit WiredTiger cache cap.
- A **Job that creates the four canonical topics** with deliberate partition counts:
  `position.events.v1` 12, `shipment.derived.v1` 6, `status.events.v1` 3, `exceptions.v1` 3.
- Four scripts: `platform-up.sh` (apply and wait for both readiness probes and the Job),
  `platform-down.sh`, `smoke.sh` (host-side round trip through both stores), and `kafka-cli.sh`,
  which downloads the Kafka distribution into a gitignored `.tools/` on first use so the console
  tools are available on a machine that has no Kafka installed.

**Decisions**

| Decision | Rejected alternative | Why |
|---|---|---|
| MongoDB **8.0.29**, the production release line | 8.3.8, the newest tag on Docker Hub | MongoDB ships "rapid releases" (8.1/8.2/8.3) that it explicitly does not support for production. Unlike the Spring Boot 4.1.1 choice in S1, newest here is a *weaker* signal, not a stronger one |
| Kafka **4.3.1**, latest GA | 3.9.x | 4.x is KRaft-only. Staying on 3.x would mean either ZooKeeper or a migration path nobody asked for |
| Topic **auto-creation off**, topics created by a Job | Let producers auto-create on first use | Auto-creation silently accepts a typo and gives the new topic the default partition count. `position.event.v1` would quietly appear with 1 partition instead of 12 — the throughput and ordering story destroyed by a missing `s`, with no error anywhere |
| **StatefulSet**, not Deployment | Deployment with a PVC | Kafka's log directory *is* its database — broker identity, partitions, and every unread message. A StatefulSet is what guarantees `kafka-0` gets the same name and the same volume back after a restart |
| **Explicit partition counts per topic** | One default for all | Partition count is the ceiling on consumer parallelism, can be raised but never lowered, and raising it changes which partition a key hashes to — so a shipment's new events would land away from its own history and break per-shipment ordering. Too permanent to inherit from a default |
| **NodePort** services on fixed ports | `kubectl port-forward` | Port-forward is a foreground process that dies with its terminal and has to be re-run per session. The NodePorts pair with the port mappings already baked into the Kind cluster config |
| **`publishNotReadyAddresses: true`** on the headless services | Leave the default | Not a preference — Kafka cannot start without it. See surprises |
| **No authentication on MongoDB locally** | Root credentials in a Secret | The database is reachable only from this laptop, and credentials here would buy the appearance of security rather than the thing. Real IAM is an M8 deliverable against real cloud resources |
| Kafka retention **24 hours** | The 7-day default | This is a laptop. The M1 simulator at full rate would fill the disk |
| Kafka CLI downloaded into a gitignored `.tools/` | Commit it, or install system-wide | A 100 MB tarball does not belong in the repository, and a system-wide install is an undocumented prerequisite for anyone cloning this |

**Surprises**

- **Kafka deadlocked on its own readiness probe.** The broker logged `Kafka Server started` and then
  sat at `0/1` indefinitely while the topic Job retried for four minutes. The probe connects to
  `localhost:9092`, and Kafka's reply to any bootstrap is *"reconnect to me at my advertised
  address"* — `kafka.fleet.svc.cluster.local:9092`. But a Service publishes only the endpoints of
  **ready** pods, so that DNS name did not resolve until the probe passed, and the probe could not
  pass until the name resolved. `publishNotReadyAddresses: true` breaks the cycle. The general
  shape: any readiness probe that travels through the service's own DNS name is a candidate for
  this, and it presents as a healthy process that never goes ready.
- **Git Bash rewrote a path meant for a Linux container.** `kubectl exec ... /opt/kafka/bin/kafka-topics.sh`
  failed with `stat C:/Program Files/Git/opt/kafka/bin/kafka-topics.sh: no such file or directory`.
  MSYS converts anything shaped like a Unix absolute path into a Windows one before the process
  sees it. `MSYS_NO_PATHCONV=1` disables it. Same class of problem as the `$LOCALAPPDATA`
  conversion in S1, in the opposite direction.
- **Kafka's console tools emit a log4j stack trace on every invocation under Git Bash.** The
  launcher passes `-Dlog4j2.configurationFile=<path>`, which resolves to `G:/...`; log4j parses a
  path containing a colon as a URI and fails with `unknown protocol: g`. Harmless but it buries the
  actual output. `kafka-cli.sh` passes a proper `file:///` URI instead.
- **The `mongod` already on this machine is server-only** — no `mongosh`. Installed
  `MongoDB.Shell` 2.9.2 via winget and added its directory to `scripts/lib.sh`, since winget
  updates the user PATH that running shells never see.
- **A Job's pod template is immutable.** Re-applying a changed `kafka-topics` Job fails with a
  field-is-immutable error, so `platform-up.sh` deletes the Job before applying. Re-running the
  topic creation is safe because every `kafka-topics.sh --create` carries `--if-not-exists`.

**Measured**

Kafka and MongoDB together add roughly **625 MB** to the cluster — 1.29 GB total against ~660 MB
for an idle cluster with nothing deployed. Comfortable inside the 7.6 GB the Docker VM currently
has, which means the memory warning from `preflight.sh` still has no teeth until the JVM services
arrive in M2.

**Left open**

- The Docker VM is at 7.6 GB against the 10-12 GB the full stack targets. Not yet a problem, and
  now measured rather than assumed.
- No `deploy/overlays/` yet — only the base. The overlay structure is an M6 deliverable and there
  is nothing to differentiate between environments until then.
- MongoDB has no authentication and no replica set, so no transactions and no change streams. Both
  are deliberate for a local single-node store; if change streams turn out to be wanted in M3, a
  single-node replica set is the smallest change that provides them.

---

## S4 — Simulator core

**2026-08-29 · M1 · commit `b631e15`**

M1 opens with `tools/fleet-simulator`: a Spring Boot application that drives synthetic trucks along
real freight lanes with movement physics that hold up under inspection. Nothing in the cluster is
involved — no Kafka, no MongoDB, no Kubernetes. This is the data source everything downstream will
be written and tested against, which is why it is built before the services that consume it.

**Built**

- **Geodesic maths** (`route/Geo`) — great-circle distance, initial bearing, and the direct
  geodesic that moves a truck a given distance on a given heading. Haversine on a mean-radius
  sphere rather than an ellipsoidal solver.
- **Route model** — `Stop` (location, city/state, geofence radius, dwell, kind), `Leg`, and
  `Route`, which derives its legs and knows both its straight-line and its road-corrected length.
- **Four real lanes** (`route/Lanes`) — Chicago→Dallas long-haul, LA→Denver cold chain,
  Atlanta→Columbus multi-stop LTL, and a Houston→Laredo drayage shuttle. Deliberately different
  shapes, so a bug cannot hide behind uniformity.
- **The movement model** (`fleet/Truck`) — a three-phase state machine (driving, dwelling,
  completed) advanced one tick at a time, with acceleration and braking limits, a mean-reverting
  wander on cruise speed, reefer temperature drift, and a monotonic odometer. Arrivals and
  departures come out as typed `TruckTransition`s: the ground truth M3's geofencing will later have
  to rediscover from noisy positions alone.
- **The fleet and tick loop** — `Simulation` (plain Java, no Spring) holding the trucks and their
  shared simulated clock, and `SimulationRunner`, a `SmartLifecycle` that steps it on a schedule.
- **`TickObserver`** — the seam S5's four wire-format emitters and S6's Kafka producer plug into.
  S4 ships one implementation, which logs.
- **54 unit tests** in the module, on top of the 36 in `libs/events`.

**Decisions**

| Decision | Rejected alternative | Why |
|---|---|---|
| Straight great-circle legs between stops | Road-snapped geometry from a routing engine (OSRM, or a paid API) | A routing service is a network dependency and another container, and nothing downstream can tell the difference: geofencing, ETA and dedup are all tested by the *timing* of positions, not by whether the truck followed the actual I-55. Routes are plain data, so real polylines remain a fixture change rather than a code change |
| Drive the short line, but bill speed and odometer against the longer road (`ROAD_CIRCUITY = 1.18`) | Drive the straight line at full speed | Getting this wrong in the optimistic direction is the worst outcome available: every ETA the platform computes in M3 would look excellent, because the trucks would be cheating in exactly the way a naive ETA assumes they can. Real roads run 15-25% longer than the straight line |
| Haversine on a sphere | Vincenty or Karney on the WGS-84 ellipsoid | The ellipsoid is accurate to millimetres; haversine is off by up to 0.5%, around 5 m per kilometre. GPS is routinely worse than that, and the geofence thresholds this feeds are tens of metres wide. The exact solver also needs an iterative loop that can fail to converge |
| The simulation keeps its own clock | Stamp events with `Instant.now()` | Time compression is the point of `time-scale`. At 60× the trucks cover an hour of ground per real minute; wall-clock stamps would describe that hour as a minute and imply speeds of 6 000 km/h to anything computing speed from timestamps |
| Braking derived from `v² = 2as` | An explicit "am I nearly there yet" distance check | The stopping-distance formula *is* the deceleration curve. Taking the lesser of it and the driver's desired speed produces a smooth approach that begins on its own about 480 m out at 100 km/h, with no branch anywhere that says "start braking" |
| One seeded generator per truck, derived from a master seed | One shared generator for the fleet | A shared generator makes a truck's behaviour depend on how many other trucks happen to be running, so a run with 8 trucks produces a different truck #1 than a run with 4. Per-truck seeds make a run reproducible *and* stable under fleet resizing |
| `scheduleWithFixedDelay` | `scheduleAtFixedRate` | If a tick ever overruns, fixed-rate scheduling fires the backlog back to back — a burst of events at precisely the moment the process is already struggling. Fixed delay just runs slower |
| Lanes as Java constants | A YAML or JSON resource | They are test fixtures. A malformed lane should be a compile error, not a startup failure |

**Surprises**

- **The truck arrived at its first dock doing 21 km/h**, after braking flat out for thirty seconds.
  The braking curve was computed from the distance remaining *now*, which lets the truck cross the
  curve during the very step that discovers it — and once above the curve it can never get back,
  because the excess speed consumes extra ground, which lowers the curve again. The error compounds
  monotonically. Braking against the distance that will remain *after* the step fixes it: the truck
  starts braking one tick early and then tracks the curve to within a centimetre per second. A
  one-tick lookahead is the difference between a plausible model and an obviously broken one.
- **The application ran exactly one tick and exited cleanly.** The tick thread was a daemon, and
  with no web server there was nothing else holding the JVM open, so it was abandoned the moment
  `main` returned. The logs showed a successful startup and a graceful shutdown, with no error at
  all. Non-web Spring Boot applications need something non-daemon to stay alive.
- **`spring-boot-maven-plugin` does not repackage unless the execution is declared.** The BOM is
  imported rather than inherited (an S1 decision that keeps `libs/events` a plain library), and
  that brings dependency versions but none of `spring-boot-starter-parent`'s plugin executions. The
  jar built without complaint and failed at runtime with `no main manifest attribute`.
- **A degree of longitude at 60°N is not exactly half a degree of arc.** The test asserting it was
  0.53 m out — correctly. `cos(latitude)` gives the distance along the *parallel*, and a parallel is
  not a great circle: the shortest path between two points at the same latitude bows toward the pole
  and is slightly shorter. The assertion now also checks the sign of that difference, since being
  long there would be a real bug.

**Left open**

- Every truck uses the same `DriverProfile`. Per-lane profiles — a mountain lane should not cruise
  at the same speed as a flat interstate — are worth having but are not needed until there is
  something measuring ETA accuracy.
- No fault injection yet, and no wire formats. Both are S5, which is what makes the simulator
  useful to M2 rather than merely correct.
- Sample payloads are still not in `docs/samples/`; there is nothing to capture until S5 emits the
  four real shapes.

---

## S5 — Four source formats and fault injection

**2026-08-31 · M1 · commit `55c2d46`**

The simulator stops emitting internal snapshots and starts emitting what the four real feeds
actually put on the wire. The movement core is untouched: every emitter attaches as a
`TickObserver`, which is what S4's seam was for. M1 closes here, and `docs/samples/` now holds the
contract fixtures M2's normalizers will be written against — before the gateway has ever seen a
live feed.

**Built**

- **The emission seam.** `SourceMessage` carries a payload plus its provenance: which feed, what
  content type, when the event happened, and — separately — when the source got round to sending
  it. `MessageSink` is where a message goes, with a logging implementation, a file-capture
  implementation, and a composite. S6's Kafka producer becomes one more implementation and no
  formatting code changes.
- **`Cadence`** — the piece that turns one uniform tick loop into four independent reporting rates,
  measured in simulated time so the rates survive any `time-scale`. Each device gets a random phase
  offset, so eight trucks reporting every thirty seconds do not all report on the same tick.
- **Telematics** — nested JSON in imperial units. Reports a vehicle and no shipment, calls itself
  `TLM-0002` while the reefer probe on the same truck is `DEV-0002`, and expresses accuracy as HDOP
  rather than metres.
- **Mobile app** — abbreviated keys, epoch milliseconds, metres per second. Knows the shipment and
  not the vehicle: the exact inverse of telematics. Loses signal, buffers, and dumps the backlog on
  reconnect out of order and with repeats.
- **EDI 214** — full X12 interchanges, `ISA`/`GS` envelope around several `ST`…`SE` transaction
  sets. No coordinates at all: a city and a state in an `MS1` segment. Delayed twice over, first by
  the back office taking 45 simulated minutes to enter an event and then by waiting for the next
  30-minute batch window.
- **Reefer probe** — temperature, setpoint and a device id. No position, no vehicle, no shipment.
  Only refrigerated lanes carry one.
- **Fault injection** — GPS noise and bad fixes inside the emitters, drops, duplicates and
  corruption at the sink boundary, each switchable on its own, all drawn from the run's seed so a
  fault is reproducible. A `chaos` profile turns everything on at once.
- **`docs/samples/`** — 150 messages per JSON feed, 10 EDI interchanges, plus a `faults/` set for
  dead-letter fixtures. Regenerable from a fixed seed; the command is in the directory's README.
- **53 new unit tests**, taking the module to 143.

**Decisions**

| Decision | Rejected alternative | Why |
|---|---|---|
| Full X12 interchange envelope, several shipments per file | Bare `ST`…`SE` transaction sets, one status per message | The batching *is* the awkward part of EDI. An interchange covering many shipments has no single shipment id and therefore no partition key, so M2 has to split it before it can key anything. Emitting one status per message would have quietly deleted that problem |
| A `MessageSink` interface with swappable implementations | Emitters log their payloads, and S6 retrofits a seam | The same reasoning that made `TickObserver` worth having in S4. Kafka arrives as one implementation rather than as an edit to four emitters |
| EDI carries city and state but **not** our stop id | Include the stop id so events match cleanly | A carrier does not know this platform's identifiers. Putting them on the wire would turn geocoding and stop matching — a substantial part of M2 and M3 — into a dictionary lookup |
| Waypoint arrivals are never filed to EDI | File every arrival | Carriers report freight events, not every time a truck stops. Geofencing will observe arrivals EDI has no opinion about, which is a realistic disagreement between two sources and worth having |
| Identity added to `TruckTransition` | Pair transitions back to trucks by timestamp | S4's transitions recorded that *a* truck arrived somewhere, which was enough to log them. EDI and the mobile app both need to name the shipment, and matching on timestamps afterwards is exactly the guesswork ground truth exists to avoid |
| GPS noise on by default; every other fault off | All faults off, or all on | Noise is not a fault — it is what GPS does, and M3's geofencing has to rediscover arrivals from exactly that. Drops and corruption are faults, and a default run should be realistic rather than adversarial |
| One `FaultProfile` per emitter, each separately seeded | One shared fault generator | Same reasoning as per-truck seeds in S4. A shared generator makes telematics output depend on whether the mobile app is switched on, and every fixture changes when any feed is disabled |
| Transport faults wrap the capture sink | Faults applied beside capture | What lands in `docs/samples/` should be what the platform would actually receive — corruption included, dropped messages absent |
| Separate, lower capture cap for EDI | One cap for all feeds | Three feeds append a line to one file; EDI writes a whole file per interchange. A cap generous enough for the mobile app's reconnect bursts to appear would have committed hundreds of near-identical EDI documents |

**Surprises**

- **The fixtures came out empty, and nothing said so.** `FileMessageSink` buffered writes and
  flushed only on `close()`. Early trial runs looked fine because they wrote 70 KB and the 8 KB
  buffer overflowed repeatedly on its own; the real capture, capped at 25 messages per feed, stayed
  under 8 KB and never overflowed. The shutdown hook that would have flushed it never ran either,
  because the runs were ended by killing the JVM. Three zero-byte files and a successful-looking
  run. Capture now flushes per line: a sink whose output is only correct when the process exits
  cleanly is wrong, because capture runs are ended by killing the process.
- **A test that replayed the same hour twice.** The EDI test drove the emitter by computing tick
  times from a fixed start, so a second call to the helper began again at the start rather than
  continuing. Simulated time went backwards between the two phases and the batch window never came
  round. The emitter was correct; the harness was not. For anything whose behaviour is defined by
  elapsed time, the test clock has to be as continuous as the real one.
- **A README claiming things the fixtures did not show.** The samples README described an
  out-of-order reconnect burst, using a sequence copied from an earlier trial run. The committed
  capture was capped at 25 messages spread over 8 trucks — about three reports each — so no truck
  was ever offline long enough to produce one. Fixed by capping the JSON feeds far higher than EDI,
  then re-deriving every example in the README from the committed files. Documentation about
  generated artefacts has to be generated from the artefacts, or checked against them.

**Left open**

- The mobile app's outage model is per-shipment and independent, so two trucks in the same dead zone
  are not correlated. Realistic correlation would need geography the emitter does not have.
- EDI files timestamps as `UT` rather than local time with a zone code. Local-time filing is the
  more realistic variant and a genuine integration trap, but it needs a timezone per stop, which is
  data the route model does not carry. Worth revisiting when M2 has a geocoder that would know.
- No emitter writes to a network. Everything is in-process until S6.

---

## S6 — Ingest gateway, telematics normalizer, first integration test

**2026-08-31 · M2 · commit `3dd6119`**

The first service. Four external feeds now have a front door: an HTTP endpoint each, a normalizer
behind it, and Kafka on the far side. Telematics is the feed that works end to end; the other three
have their endpoints framed and answer `503` until S7 writes their normalizers. This is also the
first `*IT` in the repository — Failsafe has reported "No tests to run" since S1.

**Built**

- **`services/ingest-gateway`** — a Spring Boot web service. `POST /ingest/{telematics,mobile,
  edi214,reefer}`, each taking the request body as an unparsed string.
- **Identity resolution as a seam.** `IdentityResolver` answers three questions — what load is this
  vehicle pulling, this device attached to, this shipment carried by — one for each fragment of
  identity a feed happens to know. S6 ships a configuration-backed implementation standing in for a
  transport management system; S8 replaces it with a MongoDB-backed one and no normalizer changes.
  It refuses to start on contradictory reference data.
- **`TelematicsNormalizer`** — flattens the vendor's nesting and converts four things that would
  each fail silently: miles per hour to km/h, miles to kilometres, HDOP to a radius in metres, and a
  heading of exactly 360 to 0. The odometer's stated unit is honoured rather than assumed.
- **`NormalizationResult`** — a sealed success-or-rejection type rather than an exception, with
  five rejection categories. Success carries a *list*, because an EDI interchange is a batch.
- **Deterministic event ids.** An event's id is a name-based UUID over the feed, the reporting
  device and the instant the source reported — never the arrival time. A duplicate delivery
  therefore produces a byte-identical id, which is what lets a consumer de-duplicate and what lets a
  replayed topic regenerate the ids it had before.
- **Publishing and dead-lettering.** Canonical events go to `position.events.v1` keyed by
  `shipmentId`; anything that could not become one goes to the new `ingest.dlq.v1` with the original
  bytes, a reason, and both as Kafka headers. The send is awaited rather than fired and forgotten.
- **`HttpMessageSink` in the simulator** — one more `MessageSink`, so the fleet can post to a
  running gateway. No emitter changed. Off by default: the simulator's defining property is that it
  runs standalone.
- **`IngestGatewayIT`** — the first Testcontainers test. A real broker, pinned to the version the
  cluster runs, with topics created explicitly rather than auto-created.
- **35 new unit tests plus 4 integration tests**, taking the repository to 218.

**Decisions**

| Decision | Rejected alternative | Why |
|---|---|---|
| Sources reach the gateway over **HTTP** | The simulator writes raw payloads to Kafka ingest topics and the gateway consumes them | Three of the four feeds are systems this platform does not control — a telematics vendor's webhook, a phone app, a carrier's EDI system — and none of them will be given broker credentials or a client library. HTTP is what they can actually talk to. It also keeps the gateway the only writer to the source topics, so the invariant every consumer relies on is enforced in one place |
| Request bodies bound as `String` | Bind to a typed payload and let the framework parse | A malformed message would fail inside request binding and return a framework-generated `400` the service never saw, so the dead-letter topic would be empty of precisely the messages it exists to hold. It also keeps `raw` byte-exact rather than a re-rendering with normalized whitespace and key order |
| A rejected message gets **`202`**, not `400` | `400 Bad Request` for anything unparseable | Corrupt bytes corrupt identically on every retry, so a `400` either loses the message or invites an endless retry loop. `202` with a body saying `DEAD_LETTERED` and why is true: the payload is durably stored and the sender need not resend. `503` is reserved for the cases where a retry can genuinely help — the broker did not acknowledge, or the feed has no normalizer yet |
| The producer send is awaited before responding | Fire and forget, respond immediately | Returning `202` while the message sits in a client-side batch in the JVM's heap is a lie: a rescheduled pod loses it after the vendor has been told it arrived. The gateway is where responsibility for a message transfers |
| Event ids derived from what the source stated | A random UUID per event | Two feeds deliver the same message twice — the mobile app resends unacknowledged messages, and any HTTP producer retries a lost response. Random ids make the second copy indistinguishable from a real event, so the same fix is counted twice |
| Validation applied centrally, in the ingest pipeline | Each normalizer validates its own output | "Every normalizer remembers to validate" is not a property code review enforces. Here a normalizer's output structurally cannot reach Kafka without being range-checked |
| A feed with no normalizer answers `503` | Dead-letter it as unsupported | Nothing is wrong with the data; the gateway is unfinished. Filling the rejection topic with valid messages would bury the invalid ones it exists to surface |
| One dead-letter topic for all four feeds | One per feed | Nothing consumes it in order or in isolation, and replay means running a backlog through the same fixed gateway. Each message names its own source in a header, so filtering is a header check rather than a separate subscription |
| The dead-letter topic is unkeyed | Key it by whatever identifier could be salvaged | A message that failed to parse usually has no readable shipment id, and keying only the ones that do would spread one feed's failures unevenly for no benefit |
| The HTTP sink drops when its queue is full | Block until the gateway catches up | The tick thread also moves every truck. Blocking would slow the fleet to whatever the gateway could absorb while its timestamps still claimed a compressed run. A real device with a full buffer drops too |

**Surprises**

- **Spring Boot 4 does not auto-configure a library you merely depend on.** Adding
  `org.springframework.kafka:spring-kafka` produced `No qualifying bean of type
  KafkaTemplate<String, String>`, which reads like a generics mismatch. Boot 4 moved
  auto-configuration out of `spring-boot-autoconfigure` into a module per technology, so the raw
  library brings the classes and nothing that builds one. `spring-boot-starter-kafka` is the
  dependency. The error names the symptom and nothing about the cause.
- **Failsafe tests the packaged artifact, which a Spring Boot service does not have.** The first
  integration run failed with "Unable to find a `@SpringBootConfiguration` by searching packages
  upwards from the test", as though the application class were misplaced. It was on the classpath
  the whole time: the `repackage` goal had rewritten the module's jar into an executable one with
  its classes under `BOOT-INF/classes`, and Failsafe puts that jar on the test classpath rather than
  `target/classes`. Pointing Failsafe's `classesDirectory` at the build output directory fixes it
  for every service that will follow.
- **Testcontainers 2.x renamed every module.** The Boot 4.1.1 BOM manages Testcontainers 2.0.5, in
  which `org.testcontainers:kafka` became `org.testcontainers:testcontainers-kafka`. The old
  coordinates are simply unmanaged, so the build fails with `'dependencies.dependency.version' is
  missing` — an error that never mentions a rename.
- **`TestRestTemplate` no longer exists in Spring Boot 4.** Replaced here by a plain JDK
  `HttpClient`, which is arguably the better test anyway: the endpoint is one a telematics vendor
  will call with no Spring on their side at all.
- **A shutdown that deadlocks exactly when shutdown matters.** The HTTP sink first stopped its
  worker with a poison pill pushed onto the queue. The one moment shutdown matters most — a gateway
  that has stopped answering, so the queue is full — is the one moment there is no room to enqueue
  it, and the worker would wait on a queue nobody drains while `close()` waited on the worker. Found
  by reasoning about the test rather than by the test failing. Replaced with a flag and a timed
  poll.

**Verified end to end**

Eight simulated trucks posting to a locally running gateway put **3 984 position events** on
`position.events.v1` on the Kind cluster, with **zero** dead letters. Both events sampled for
`SHP-ATL-0003` landed on the same partition, which is the per-shipment ordering guarantee working.
An odometer of 79 352.7 miles arrived as 127 705.79 km and an HDOP of 1.28 as 6.4 m, with the
original payload intact in `raw`.

**Left open**

- Only telematics normalizes. The other three endpoints answer `503`.
- Reference data is a fixed list in `application.yaml`. Until S8 it cannot express that a tractor
  pulls a different load tomorrow, and a reefer probe still has no route to a shipment.
- The gateway is not containerized and has no manifest; it runs from a jar against the cluster's
  published ports. Deployment arrives with the rest of M2.
- The HTTP sink's throughput ceiling is roughly 100 messages per second — one worker, and a Kafka
  acknowledgement awaited per request. A run at `time-scale=600` asks for nearly three times that
  and drops the excess by design. Realistic time scales are nowhere near it.
- At high time scales `occurredAt` runs *ahead* of `receivedAt`, because simulated time outruns the
  wall clock. Anything computing feed lag in M3 has to expect that from a compressed run.

---

## S7 — The other three normalizers, and dead-letter routing proved for all four

**2026-09-01 · M2 · commit `8f073ca`**

All four feeds now reach Kafka. The gateway's shape did not change to accommodate them: three new
`Normalizer` beans, collected by type, and no edit to the controller, the config class or the
publisher. What did change is the result type — a batch feed turns out to be able to fail *partly*,
which the two-outcome version could not express.

**Built**

- **`MobileAppNormalizer`** — epoch milliseconds to an instant, metres per second to km/h, and a
  shipment id resolved to a vehicle, which is the exact inverse of the telematics lookup. A
  driver's tap becomes a status event carrying the phone's coordinates; a routine ping becomes a
  position. There is no dedup table and no reordering buffer in it: the derived event id already
  makes a resend byte-identical, and lag is passed through as the gap between `occurredAt` and
  `receivedAt` rather than disguised.
- **`Edi214Normalizer`** — an X12 reader. Splits on `~` rather than newlines, preserves empty
  elements, honours the `AT7` time zone code as a fixed offset, translates the carrier's status
  vocabulary, and splits one interchange into one event per `ST`/`SE` transaction set. It verifies
  the format's own checksums: `SE` counts the segments in its set and `GE` counts the sets in the
  group, which is the only way to notice segments that were dropped rather than never sent.
- **`ReeferNormalizer`** — a temperature, a device id and nothing else, resolved to a shipment
  entirely from reference data. Becomes a status event with no position and no place name, carrying
  both the measured and the setpoint temperature so every consumer derives the deviation the same
  way from the numbers the device actually sent.
- **`NormalizationResult.Partial`** — the third outcome: some events came out, and something was
  also wrong. `IngestService` publishes the survivors and dead-letters the original bytes whole.
- **61 new tests**, taking the repository to 272 unit tests and 7 integration tests.

**Decisions**

| Decision | Rejected alternative | Why |
|---|---|---|
| A damaged batch **publishes what survived and dead-letters the whole original** | Reject the entire interchange if any part of it is damaged | A truncated interchange typically holds several complete, self-consistent shipment statuses and one that was cut off. Rejecting all of them throws away real freight events that the carrier's back office has already sent and will not send again. Publishing without a dead-letter leaves no record anywhere that anything was missing. Doing both is only safe because event ids are derived: replaying the dead-letter entry regenerates byte-identical ids for what already published, and downstream de-duplication absorbs them |
| The mobile normalizer holds **no dedup state** | Remember recently-seen ids and drop repeats at the gateway | The gateway is the component that gets restarted and scaled horizontally. Three instances behind a load balancer would each dedupe against their own private idea of what they had seen, which is worse than not deduping at all. The derived id pushes the decision to consumers, which have the state to make it |
| The mobile normalizer does **not reorder** a backlog burst | Buffer and sort by `occurredAt` before publishing | Buffering means choosing how long to wait for a phone that may be off for an hour. Kafka preserves arrival order within a partition and a shipment's messages all share one, so M3 sees the burst as it happened and decides with the full picture |
| The event-kind is part of the mobile **event id**, not the app's `seq` | Use `seq`, which is unique per installation | `seq` counts messages sent, so it restarts at 1 on a reinstall and the same fix resent afterwards would produce a different id — the exact failure derived ids exist to prevent. The event kind separates a driver's tap from the ping sent in the same second, which is the only real collision |
| `AF` maps to **`PICKED_UP`**, not to a generic departure | Map all four departure-shaped codes to `DEPARTED_STOP` | "Carrier departed pick-up location with shipment" is the moment the freight is aboard, which is the milestone a customer's SLA is written against. `X3` and `X1` do both collapse to one arrival, because the canonical vocabulary describes what happened to the truck and the route model already knows which stop it was |
| An unknown status code or event type is **rejected** | Fall back to a generic position or status | A carrier or an app version extending its vocabulary should surface in the dead-letter topic as work to do, rather than disappear into the canonical stream looking ordinary |
| The `AT7` time zone code is **honoured**, and `LT` is rejected | Assume UTC | Reading a Pacific timestamp as UTC puts an arrival eight hours early — a plausible-looking time, not an error. `LT` means "local time" for a sender whose location the file never states, so there is nothing to convert with |
| EDI carries a `LocationHint` and **never a coordinate** | Geocode the city centroid at ingest | The centroid of Memphis is 8 km from most of Memphis, and publishing it into a position field would tell every consumer there was a real fix. Geocoding is a consumer's decision, made with a tolerance it chooses |
| The reefer feed leaves `stopId` and `position` null | Fill them from the last known position of the same shipment | That would be this service inventing a fact by joining two feeds, in the one component whose job is to normalize each feed faithfully. It is also exactly what M3 is for |

**Surprises**

- **Five of the eight committed fault interchanges are not damaged at all.** The chaos profile
  corrupts a fraction of what it emits, and by chance it truncated only one interchange, before any
  complete transaction set. So nothing in `docs/samples/faults/` exercises partial acceptance, and
  the test that covers it truncates a good committed interchange deterministically instead. Worth
  knowing before trusting a fixture directory to cover a case by name.
- **Dropping a single `~` produces a file that still parses.** Removing the first segment terminator
  merges the `ISA` and `GS` headers into one segment that still begins with `ISA`; every count
  below it still agrees, and no check catches it. The only visible symptom is that no standalone
  `GS` exists, so the parser looks for one — a check that reads as pointless until you know why.
- **A test helper from S6 assumed every record on a topic was a position event.** It cast to
  `PositionEvent` to reach the raw payload, which was true while only telematics worked and threw a
  `ClassCastException` the moment status events appeared. The fix was to read the shared supertype.
- **Two tests posting the same captured payload see each other's records.** Both drain from the
  start of the topic, and a resent payload produces a byte-identical event by design — so the
  duplicate was the derived-id property working, surfacing as a test collision. Tests now
  deliberately pick different captured lines and say why.

**Left open**

- Reference data is still a fixed list in `application.yaml`. Every feed now depends on it, and the
  reefer feed depends on it completely. S8 replaces the implementation behind the existing seam.
- The gateway is still not containerized and has no manifest.
- `docs/samples/faults/edi-214/` does not contain a partially-damaged interchange, so that case is
  covered by a constructed fixture rather than a captured one. A future chaos capture with a
  higher truncation rate would provide a real one.

---

## S8 — Identity resolution against MongoDB, and a lookup that knows what time it is

**2026-09-02 · M2**

Reference data left the configuration file. An assignment now states that a tractor pulled a load,
wearing a given set of devices, **over a period**, and every lookup asks as of the instant the
source stated rather than as of now. That single change is what M2 was missing: the platform can
express that a tractor pulls a different load tomorrow, and answer correctly about yesterday.

M2's four exit criteria pass against real reference data, and M3 is unblocked.

**Built**

- **`Assignment`** — one dispatch decision as a MongoDB document: shipment, vehicle, the devices
  fitted to it, and a half-open validity window `[validFrom, validTo)`. Half-open so two consecutive
  loads meet exactly at the changeover instant without both claiming it. An open assignment carries
  a null end, which is the normal state of a running load rather than an edge case.
- **`MongoIdentityResolver`** — the same three questions, answered by one indexed query with a range
  on the validity window. It asks for two matches and uses one: if two come back, the reference data
  contradicts itself and it resolves nothing rather than picking a side.
- **`scripts/seed-identity.sh`** — loads dispatch reference data and creates the three indexes.
  Idempotent, because a document's id is derived from the shipment and the assignment's start
  instant, exactly as event ids are derived from the payload.
- **`MongoIdentityResolverIT`** — 11 tests against a real MongoDB, including the two cases that
  could not previously be stated: one tractor resolving to different loads at 13:00 and 15:00, and a
  trailer probe swapped at noon resolving to whichever tractor actually had it.
- The end-to-end test now runs against a MongoDB container as well as a Kafka one, because the claim
  it makes — a device id reaches Kafka as a shipment id — is now a claim about a database query.

**Decisions**

| Decision | Rejected alternative | Why |
|---|---|---|
| Every lookup takes **the instant the source stated** | Keep the time-free interface and resolve "as of now" inside the implementation, so no normalizer changes | The seam was built to make the swap invisible, and honouring that literally would have made the temporal schema unusable — a time-aware question cannot be asked through a time-free interface. Three of the four feeds are delayed by design: EDI by a filing delay and a batch window, the mobile app by connectivity gaps it buffers through. Resolving those against arrival time attributes the end of one load to the next one the tractor picked up. Four call sites changed, each by one argument |
| **One query per message, no cache** | Hold the assignment table in memory and refresh it on a timer | The round trip is one indexed lookup on the same cluster, and this service's ceiling is the broker acknowledging a produce request, not this. A cache buys a staleness window — a period during which the gateway knowingly attributes positions using an assignment dispatch has already ended — to save a cost that is not the bottleneck. The seam still permits a cached implementation if profiling ever disagrees |
| Contradictory reference data **resolves to nothing** | Return the first match, or fail at startup as the old resolver did | Two overlapping assignments mean dispatch has said a tractor is pulling two loads at once. Mongo cannot express "no two documents for this vehicle may overlap in time" as a unique index, so the check moved to read time. Returning either candidate would publish positions attributed to a load that may not be carrying them, silently, at the rate telematics arrives. Failing at startup is no longer available: reference data is operational data that changes while the service runs |
| Validity windows are **half-open**, with a null end for an open assignment | Closed intervals; or a far-future sentinel date instead of null | With closed intervals the changeover instant belongs to both assignments, which is precisely the contradiction the overlap check exists to catch. A sentinel end date collapses the query into one comparison and indexes marginally better, at the cost of every reader having to know that the year 9999 means "still running" |
| The configuration-backed resolver was **deleted**, not kept as a fallback | Keep both, selected by a property | Two definitions of the truth, one of which cannot express history. An in-memory equivalent moved into test sources for the normalizer unit tests, and it honours validity windows rather than ignoring them — a stub that answered every instant identically would let a normalizer pass the wrong timestamp with no test noticing |
| Reference data is **seeded by a script**, with indexes created explicitly | Let the application create indexes on startup, or rely on implicit indexing | Same reasoning as creating Kafka topics in a Job with explicit partition counts rather than letting a producer auto-create them: a collection that indexes itself the first time something queries it is a collection whose performance depends on which query happened to run first |
| The seed provisions **64 trucks**, not 8 | Mirror the simulator's default fleet size | With repeat-routes on — the simulator's default — a finished truck is replaced by a fresh one numbered 9, 10, 11, and every event from those resolved to nothing. A long demo run was quietly dead-lettering perfectly valid freight, and the fixed list made that unfixable rather than merely wrong |

**Surprises**

- **Spring Boot 4 renamed the MongoDB connection properties, and the old names fail silently.**
  `spring.data.mongodb.uri` is deprecated at level `error` since 4.0.0 in favour of
  `spring.mongodb.uri`; a property deprecated at that level is not bound, and nothing is logged. The
  application fell back to the default `mongodb://localhost/test` — which on this machine is an
  unrelated MongoDB that accepted the connection and answered every query. **The integration test
  passed while seeding reference data into a database belonging to something else**, because a test
  that seeds a database and reads it back is satisfied by any database. It was caught by a startup
  log line reporting 8 assignments where the cluster held 64. Three guards now: the corrected
  property names, a startup line naming the database actually connected to, and a test asserting the
  port the client is connected to — the one claim that successful reading and writing cannot fake.
- **The temporal lookup made a pre-existing bug visible.** Trucks 9 and above have never had
  reference data, so any run long enough for a truck to finish its route was dead-lettering valid
  events. The fixed list could not have been extended to cover it without knowing the run length in
  advance.
- **The overlap check fires on data that looks fine in isolation.** Both assignments are individually
  valid; only their intersection is wrong. That is why it cannot be a schema constraint.

**Left open**

- The gateway is still not containerized and has no manifest, so reference data lives on the cluster
  while the service reading it runs from a jar on the host.
- Nothing writes assignments except the seed script. A real deployment learns them from a TMS feed;
  the collection and the query would not change.
- `docs/samples/faults/edi-214/` still contains no partially-damaged interchange, so that path is
  covered by a constructed fixture.

---

## S9 — The first consumer, and a position history that keeps its shape

**2026-09-03 · M3**

`tracking-processor` is the first service in this repository that *reads* a topic. Everything before
it moved messages along; this one turns a stream into state you can query — where a shipment is now,
and where it has been. Position history goes into a MongoDB time-series collection, and a second,
much smaller collection holds one current position per shipment.

M3's first exit criterion passes: under simulator load the history grows and the current position
tracks live, verified against the real cluster rather than only in tests.

**Built**

- **`PositionPoint` and the time-series collection** — one measurement per position event, with
  `occurredAt` as the time field and `shipmentId` as the meta field. The collection is created
  explicitly at startup, because inserting into a missing one silently produces an *ordinary*
  collection: no buckets, no compression, no automatic index, and no error to say so.
- **`CurrentPosition`** — one document per shipment, updated by a conditional upsert that only ever
  moves forward in event time. That condition is not defensive programming; it is the mobile feed's
  normal behaviour, which dumps a buffered backlog out of order after a signal gap.
- **`PartitionGuard`** — makes Kafka's redelivery harmless without paying for it on every message.
  Redelivery is always a contiguous run at the start of a partition assignment, so the guard checks
  against stored history until the first genuinely new record and then stops for that assignment.
  Steady-state cost: nothing.
- **`RecentEventIds`** — a bounded, per-partition set of recently-seen event ids, which is what stops
  a message the *source* sent twice being stored twice. Added mid-session; see the surprise below.
- **`TrackingDeadLetters` and `tracking.dlq.v1`** — a dead-letter topic separate from the gateway's,
  because an entry here means something inside the platform produced an event that should not exist,
  while `ingest.dlq.v1` holds routine bad data from carriers. Burying one in the other loses it.
- **`Topics` moved into `libs/events`** — a second service needed to name the same topics, and two
  copies of a wire contract diverge silently. A service's *own* dead-letter topic stays with that
  service, since nothing else touches it.

**Decisions**

| Decision | Rejected alternative | Why |
|---|---|---|
| Records are consumed as **strings and parsed in the listener** | Configure the container to deserialize JSON into the envelope | Same reasoning as the gateway binding request bodies as strings. A record that fails to deserialize fails *inside the framework*, before any of this code runs — so the dead-letter path that exists for exactly that record would never see it. It also keeps every service on the one shared Jackson mapper rather than on whatever a deserializer builds for itself |
| The error handler **retries for ever** | Spring's default: ten fast attempts, then log and move on | The failure that will actually happen is MongoDB being briefly unavailable. Ten rapid attempts take milliseconds, after which every position event for the length of the outage is discarded with a log line nobody is reading. That is silent data loss. Unbounded retry is safe here only because a record that can never succeed is set aside by the consumer itself and never reaches the handler |
| Redelivery is checked **only until the first new record per assignment** | Check every record against the database, for ever | Time-series collections cannot take a unique index, so the database will not reject a repeat. But redelivery can only appear as a contiguous run at the start of an assignment, so permanent checking would double the read load on the busiest path in the platform to defend against something confined to the first moments after a rebalance |
| Source duplicates are caught by a **bounded in-memory set per partition** | An indexed database lookup on every event; or accepting duplicates and filtering when reading | The duplicate is the mobile app resending a backlogged message seconds later, so the window only has to span a burst. A permanent database check is the same trade the guard already declined. Filtering at read time keeps the write path cheap but obliges every future reader to remember — and geofencing, ETA and the dashboard are all future readers |
| History is written **before** the current position | The other order | If the process dies between the two writes, this order leaves the measurement durable and the current position one fix behind, which the next event repairs. The other order leaves a current position pointing at a measurement that is not in the history: a shipment whose "now" cannot be found in its own past |
| A **separate dead-letter topic** for this consumer | Reuse `ingest.dlq.v1` | Different audiences. The gateway's rejections are carriers sending malformed data, which is routine and high-volume; an entry here means a producer went around the gateway or an envelope changed without migration. Mixing them buries a handful of real defects in a large pile of expected noise |

**Surprises**

- **The mobile feed's duplicates were reaching the database, and nothing was catching them.** The
  simulator's mobile emitter resends a message whose acknowledgement was lost — by design, not as an
  injected fault — and its own notes say the derived event id exists so that this can be deduped
  downstream. The gateway deliberately does not dedupe; it only makes the repeat *recognisable*. The
  processor's redelivery guard is off in steady state, also deliberately. So nobody did it. A live
  run stored 2,102 measurements under 2,100 distinct ids, and both duplicates were mobile. It was
  found by aggregating the real collection after a run, not by a test — no test would have shown it,
  because every test published exactly the events it expected. **Two mechanisms that each correctly
  decline a job leave the job undone, and the gap is invisible from inside either one.**
- **An integration test failed on a race that describes the design.** The end-to-end test waited for
  the history count to reach N and then asserted the current position existed. But the store performs
  two writes per event, deliberately in that order, so there is a window of tens of milliseconds in
  which the count is final and the current position is not yet written. The poll loop landed in it,
  twice in a row. The service was right and the test was wrong, and the fix generalises: **wait for
  the condition being asserted, not for a proxy that usually arrives at the same time.**
- **A build failed with a file-rename error rather than a test failure.** The Spring Boot repackage
  goal could not rename the jar, because a service started earlier in the session was still running
  from it. The message names neither the running process nor the reason.

**Left open**

- Nothing is containerized yet. The gateway and the processor both run from jars on the host against
  a cluster that holds only Kafka and MongoDB; M6 is where that changes.
- The restart criterion is only half-proved. A kill and restart mid-run lost no positions and stored
  no duplicates, but "no duplicate **arrivals**" cannot be checked until S10 produces arrivals.
- A duplicate whose two copies straddle the whole in-memory window would still be stored twice. The
  feed does not behave that way, and the limit is asserted in a test rather than left to be
  rediscovered later as a defect — but it is a limit.
- `docs/samples/faults/edi-214/` still contains no partially-damaged interchange, so that path is
  covered by a constructed fixture.

---

## Next up

**S10 — Geofencing with dwell thresholds.** The processor records where every shipment is and has
been. S10 makes it notice something: that a truck has *arrived* somewhere, and later that it has
*left*.

The difficulty is not the geometry. It is that the simulator knows the truth and the platform must
not be allowed to peek at it. Arrivals and departures exist in the simulator as explicit transitions;
the geofencer has to rediscover them from noisy positions alone — GPS noise is on by default at 6 m —
and produce **exactly one** arrival and **one** departure per stop. A truck idling on the edge of a
fence will otherwise flap between inside and outside, which is what a dwell threshold is for.

That is also what finally settles S9's restart criterion: once arrivals exist, killing the processor
mid-run must not produce a second one.

S9 leaves behind: nothing containerized, a bounded duplicate window that a sufficiently separated
repeat would slip through, and the still-missing partially-damaged EDI fixture.
