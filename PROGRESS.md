# Build Progress

Running log of how this platform gets built — what was decided, what was rejected, and what
surprised me along the way.

**Last updated:** 2026-08-29 · **Current position:** M1 Synthetic Fleet under way, session S4 of 24
· **Repo:** [goutham-hegde/fleet-tracker](https://github.com/goutham-hegde/fleet-tracker)

```
M0 ██████████  3/3 sessions    complete
M1 █████░░░░░  1/2              ← current
M2 ░░░░░░░░░░  0/3
M3 ░░░░░░░░░░  0/3
M4 ░░░░░░░░░░  0/2
M5 ░░░░░░░░░░  0/3
M6 ░░░░░░░░░░  0/1
M7 ░░░░░░░░░░  0/2
M8 ░░░░░░░░░░  0/3
M9 ░░░░░░░░░░  0/2
                4/24 sessions
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
- [ ] **S5** — Four source formats + fault injection

**Exit criteria**

- [x] A simulated truck traverses a multi-stop route with plausible physics (unit-tested)
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

## Next up

**S5 — Four source formats and fault injection.** M1 finishes by making the simulator emit the four
real wire shapes rather than internal snapshots: nested telematics JSON in imperial units, terse and
unreliable mobile-app JSON, EDI 214 segment text with no coordinates, and reefer readings that carry
a temperature and a device id but no position and no shipment. Each arrives as a `TickObserver`, so
the movement core does not change.

Fault injection lands alongside them, because a feed that never misbehaves does not test anything
M2 is built to survive: dropped messages, duplicates, out-of-order backlogs dumped after a
connectivity gap, GPS noise and outright bad fixes, delayed EDI batches, and malformed payloads
destined for the DLQ. Each fault switchable independently.

Captured samples of all four formats go to `docs/samples/` at the end of S5 and become the contract
fixtures M2's normalizers are tested against — which is what lets the gateway be written before it
has ever seen a live feed.
