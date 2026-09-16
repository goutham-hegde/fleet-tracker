#!/usr/bin/env bash
# How much the platform takes before it falls behind, and whether a crash loses anything. S23.
#
#   ./scripts/load-test.sh sweep [rate ...]   steps of offered load, in messages a second
#                                             (default: 100 250 500 1000 2000)
#   ./scripts/load-test.sh pod-kill [rate]    one run with crashes on a schedule, then the set of
#                                             position events Kafka holds compared with the set
#                                             MongoDB stored (default rate: 500)
#
# Settings, as environment variables:
#   LOAD_STEP=180         seconds of load per sweep step
#   LOAD_PROCESSORS=N     sweep with the tracking processor pinned at N pods instead of autoscaled:
#                         offered more than it can take, what it stores is its capacity
#   POD_KILL_RUN=300      seconds of load in the pod-kill run
#   POD_KILL_SCHEDULE     "second:target ..." pairs; targets are tracking-processor, ingest-gateway,
#                         mongodb and kafka
#
# Results land in target/load/<UTC timestamp>/: sweep.csv (one row per step), samples.csv (lag and
# replicas every few seconds), each load generator's own log, and for a pod-kill run pod-kill.txt
# beside the id lists it compares -- produced.txt, persisted.txt, missing.txt, unexpected.txt and
# stored-twice.txt.
#
# Needs the platform deployed from this working tree -- ./scripts/stack-up.sh --deploy -- because
# the parallel sender and the stored-latency timer it reads are S23 code.
#
# HOW A RATE BECOMES A FLEET
#
# The load generator is the simulator, run as a pod beside the platform, so every message is a
# real feed's payload for a truck the gateway can resolve and every rule downstream does its real
# work. A tick every 100 ms, each covering 30 simulated seconds, and each telematics unit reporting
# on its default 30-second cadence: one report per truck per tick. The phone (every 3 minutes),
# the reefer probe (every 5, cold-chain lanes only) and the EDI batches bring it to about eleven
# messages a second per truck, measured (phones in a dead zone hold theirs back). The offered rate
# is therefore set by the size of the fleet -- 1000/s is 91 trucks -- which is how load grows on a
# real platform too.
#
# Why 30 simulated seconds a tick and not fewer: every lane opens with an hour or two of loading at
# its origin, and a parked truck is the cheapest thing the tracking processor ever sees -- no
# estimate, a geofence it is already inside. At 5 seconds a tick that dwell outlasted a whole
# two-minute step, so the first sweeps measured a fleet that never left the depot. At 30 it passes
# in about 20 seconds, and a step sees trucks driving, arriving and dwelling in proportion.
#
# 128 senders keep each device's messages in order (see HttpMessageSink); with 64,
# the devices hashed unevenly enough that the busiest senders fell behind at 2000/s while the
# gateway's own latency stayed flat, which is the generator measuring itself.
#
# WHAT IS PAUSED, AND WHY
#
# The demo simulator, so the only traffic is the test's. And the archiver, which copies every topic
# to S3: a load test would put gigabytes there, against ADR 0001's kilobytes, and flood the public
# view's indexer into a table provisioned for ten writes a second. On the way out -- however the
# script exits -- the archiver's group is moved to the end of every topic before it is started
# again, so nothing the test produced ever reaches AWS. The cost is a gap in the archive for
# whatever the archiver had read but not yet written when it was stopped: under an hour of demo
# traffic, which the archive is a copy of anyway.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require kubectl "Ships with Docker Desktop."
require docker "Docker Desktop."
require mongosh "winget install MongoDB.Shell"

NS=fleet
NODE="$CLUSTER_NAME-control-plane"
GATEWAY_URL="http://ingest-gateway.fleet.svc.cluster.local:18081"
TICK=100ms
TIME_SCALE=300
TELEMATICS_INTERVAL=30s
MESSAGES_PER_TRUCK=11
WORKERS=128
# Trucks that finish their lane are replaced by new ones with the next number, so a run consumes
# more identities than it has trucks. Seeded generously; the sender's deadLettered count is the
# check that it was enough.
SEED_FLEET=2000
STEP_SECONDS="${LOAD_STEP:-180}"
PINNED_PROCESSORS="${LOAD_PROCESSORS:-}"
KILL_RUN="${POD_KILL_RUN:-300}"
KILL_SCHEDULE="${POD_KILL_SCHEDULE:-40:tracking-processor 80:ingest-gateway 120:tracking-processor 160:mongodb 200:kafka 240:tracking-processor}"
ARCHIVED_TOPICS=(position.events.v1 status.events.v1 shipment.derived.v1 exceptions.v1)
OUT="$REPO_ROOT/target/load/$(date -u +%Y%m%dT%H%M%SZ)"

kexec() { MSYS_NO_PATHCONV=1 kubectl exec -n "$NS" "$@"; }
kafka_tool() { kexec kafka-0 -c kafka -- "/opt/kafka/bin/$1" --bootstrap-server localhost:9092 "${@:2}"; }

# ---------------------------------------------------------------------------------------------
# Reading the platform

# The two consumer groups that matter, described by the broker. Prints one line:
#   <position log end> <processor committed> <processor lag> <exception-service lag>
# summed over partitions, or dashes when the broker could not answer (it may be the thing that was
# just crashed). One JVM start per call, a few seconds -- which is why sampling is not per second.
group_totals() {
  { kafka_tool kafka-consumer-groups.sh --describe \
      --group tracking-processor --group exception-service 2>/dev/null || true; } | tr -d '\r' |
    awk '$1 == "tracking-processor" && $2 == "position.events.v1" && $5 ~ /^[0-9]+$/ {
           rows++; end += $5
           if ($4 ~ /^[0-9]+$/) { cur += $4; lag += $6 } else { lag += $5 }
         }
         $1 == "exception-service" && $6 ~ /^[0-9]+$/ { exlag += $6 }
         END { if (rows == 0) print "- - - -"; else printf "%d %d %d %d\n", end, cur, lag, exlag }'
}

# "<partition> <next offset>" per partition of a topic.
end_offsets() {
  { kafka_tool kafka-get-offsets.sh --topic "$1" --time -1 2>/dev/null || true; } | tr -d '\r' |
    awk -F: 'NF == 3 { print $2, $3 }' | sort -n
}

# Records appended between two end_offsets readings, summed over partitions.
offsets_between() {
  awk 'NR == FNR { start[$1] = $2; next } ($1 in start) { n += $2 - start[$1] } END { print n + 0 }' "$1" "$2"
}

processor_replicas() {
  local n
  n=$(kubectl get deployment/tracking-processor -n "$NS" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)
  echo "${n:-0}"
}

# The tracking processor's stored-latency buckets (see StoredLatency), as "<pod> <le> <count>" for
# every running pod. Upper bounds in seconds; "inf" is the overflow past five minutes.
BUCKETS="0.005 0.01 0.015 0.02 0.03 0.05 0.075 0.1 0.15 0.2 0.3 0.5 0.75 1 1.5 2 3 5 7.5 10 15 20 30 60 120 300 inf"
stored_buckets() {
  local pod
  for pod in $(kubectl get pods -n "$NS" -l app.kubernetes.io/name=tracking-processor \
                 --field-selector=status.phase=Running -o name 2>/dev/null || true); do
    # One exec per pod, the 27 requests made inside it: an exec costs half a second from here.
    { kexec "${pod#pod/}" -- sh -c 'for le in '"$BUCKETS"'; do
        v=$(wget -qO- "http://localhost:18084/actuator/metrics/fleet.position.stored.latency?tag=le:$le" \
            | grep -o "\"value\":[0-9.eE+]*" | cut -d: -f2)
        echo "$le ${v:-0}"
      done' 2>/dev/null || true; } | tr -d '\r' | awk -v p="${pod#pod/}" 'NF == 2 { print p, $1, $2 }'
  done
}

# From two stored_buckets snapshots: "<positions timed> <p50> <p99>", percentiles as the upper bound
# of the bucket they fall in, in ms. Subtracted per pod before summing, so a pod that started
# during the step counts from zero. A pod that stopped during it takes its counts with it, which
# is why the count of positions timed is reported beside the count stored.
bucket_percentiles() {
  awk 'NR == FNR { before[$1 " " $2] = $3; next }
       { d = $3 - before[$1 " " $2]; if (d < 0) d = 0; count[$2] += d; total += d }
       END {
         n = split("'"$BUCKETS"'", le, " ")
         if (total == 0) { print "0 - -"; exit }
         for (i = 1; i <= n; i++) {
           seen += count[le[i]]
           label = (le[i] == "inf") ? ">300000" : sprintf("%g", le[i] * 1000)
           if (p50 == "" && seen >= 0.50 * total) p50 = label
           if (p99 == "" && seen >= 0.99 * total) p99 = label
         }
         printf "%d %s %s\n", total, p50, p99
       }' "$1" "$2"
}

# MongoDB's own view of how long its operations take: "<read µs total> <reads> <write µs total>
# <writes>" since the server started. Two readings give the average per operation in between.
mongo_latency() {
  mongosh "mongodb://localhost:37017" --quiet --eval '
    const l = db.adminCommand({ serverStatus: 1 }).opLatencies;
    print([l.reads.latency, l.reads.ops, l.writes.latency, l.writes.ops].join(" "));
  ' 2>/dev/null | tr -d '\r' || echo "- - - -"
}

# "<read ms> <write ms>": average per operation between two mongo_latency readings.
mongo_averages() {
  awk -v a="$1" -v b="$2" 'BEGIN {
    split(a, x, " "); split(b, y, " ")
    r = y[2] - x[2]; w = y[4] - x[4]
    printf "%s %s\n", (r > 0 ? sprintf("%.2f", (y[1] - x[1]) / r / 1000) : "-"),
                      (w > 0 ? sprintf("%.2f", (y[3] - x[3]) / w / 1000) : "-")
  }'
}

# ---------------------------------------------------------------------------------------------
# The load generator

SIM_IMAGE=""

start_load() {
  local name=$1 trucks=$2 seconds=$3
  kubectl delete pod "$name" -n "$NS" --ignore-not-found >/dev/null
  kubectl apply -f - >/dev/null <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: $name
  namespace: $NS
  labels:
    app.kubernetes.io/name: load-generator
    app.kubernetes.io/part-of: fleet-tracking
spec:
  restartPolicy: Never
  securityContext: {runAsNonRoot: true, runAsUser: 1000, runAsGroup: 1000}
  containers:
    - name: load
      image: $SIM_IMAGE
      imagePullPolicy: IfNotPresent
      args:
        - --fleet.simulator.emit.http.enabled=true
        - --fleet.simulator.emit.http.base-url=$GATEWAY_URL
        - --fleet.simulator.emit.http.workers=$WORKERS
        - --fleet.simulator.emit.http.queue-capacity=20000
        - --fleet.simulator.emit.http.report-every=10s
        - --fleet.simulator.emit.logging=false
        - --fleet.simulator.emit.telematics.interval=$TELEMATICS_INTERVAL
        - --fleet.simulator.tick-interval=$TICK
        - --fleet.simulator.time-scale=$TIME_SCALE
        - --fleet.simulator.trucks=$trucks
        - --fleet.simulator.repeat-routes=true
        - --fleet.simulator.run-for=${seconds}s
      resources:
        requests: {cpu: "1", memory: 512Mi}
        limits: {memory: 768Mi}
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities: {drop: [ALL]}
      volumeMounts: [{name: tmp, mountPath: /tmp}]
  volumes: [{name: tmp, emptyDir: {}}]
EOF
}

# Samples every few seconds until the load pod ends, calling "$ON_TICK <elapsed>" each time if set.
# Leaves the step's peaks in LAG_MAX, EXLAG_MAX and REPLICAS_MAX.
ON_TICK=""
watch_load() {
  local name=$1 label=$2 phase started="" elapsed end cur lag exlag replicas
  LAG_MAX=0; EXLAG_MAX=0; REPLICAS_MAX=0
  while :; do
    # Missing is its own state: a pod deleted from under the script would otherwise read as
    # "not finished yet" for ever.
    phase=$(kubectl get pod "$name" -n "$NS" -o jsonpath='{.status.phase}' 2>/dev/null || echo Missing)
    case "$phase" in Succeeded|Failed|Missing) break ;; Running) [ -n "$started" ] || started=$SECONDS ;; esac
    if [ -n "$started" ]; then
      elapsed=$((SECONDS - started))
      [ -z "$ON_TICK" ] || "$ON_TICK" "$elapsed"
      read -r end cur lag exlag < <(group_totals)
      replicas=$(processor_replicas)
      echo "$(date -u +%H:%M:%S),$label,$elapsed,$end,$cur,$lag,$exlag,$replicas" >> "$OUT/samples.csv"
      [[ "$lag" =~ ^[0-9]+$ ]] && [ "$lag" -gt "$LAG_MAX" ] && LAG_MAX=$lag
      [[ "$exlag" =~ ^[0-9]+$ ]] && [ "$exlag" -gt "$EXLAG_MAX" ] && EXLAG_MAX=$exlag
      [ "$replicas" -gt "$REPLICAS_MAX" ] && REPLICAS_MAX=$replicas
      printf '    t=%3ss  positions in Kafka %-9s processor lag %-7s exception-service lag %-7s processors %s\n' \
        "$elapsed" "$end" "$lag" "$exlag" "$replicas"
    fi
    sleep 5
  done
  kubectl logs "$name" -n "$NS" > "$OUT/$name.log" 2>&1 || true
  if [ "$phase" != Succeeded ]; then
    tail -30 "$OUT/$name.log"
    die "load generator $name ended $phase"
  fi
  grep -q 'worker(s)' "$OUT/$name.log" \
    || die "the simulator image predates S23's parallel sender. Run ./scripts/stack-up.sh --deploy first."
}

# Seconds until the processor has caught up, or a dash if it has not within the limit.
wait_for_drain() {
  local limit=$1 started=$SECONDS lag
  while [ $((SECONDS - started)) -lt "$limit" ]; do
    read -r _ _ lag _ < <(group_totals)
    if [ "$lag" = 0 ]; then echo $((SECONDS - started)); return 0; fi
    sleep 3
  done
  echo "-"
  return 1
}

# (after - before) / the step's length. Over the configured length rather than the pod's lifetime:
# the sender produces nothing while its JVM starts, and a rate over the lifetime would charge that
# to the platform.
per_second() {
  awk -v a="$1" -v b="$2" -v s="$STEP_SECONDS" \
    'BEGIN { if (a ~ /^[0-9]+$/ && b ~ /^[0-9]+$/) printf "%.1f", (a - b) / s; else print "-" }'
}

# One field of the sender's closing "load total=..." line, units stripped.
total_field() {
  grep -o 'load total=.*' "$1" | tail -1 | grep -o " $2=[^ ]*" | cut -d= -f2 | sed -E 's#(ms|/s|s)$##' || true
}

# ---------------------------------------------------------------------------------------------
# Before and after

SIMULATOR_REPLICAS=""
ARCHIVER_REPLICAS=""

prepare() {
  kubectl get deployment/ingest-gateway -n "$NS" >/dev/null 2>&1 \
    || die "The platform is not deployed. Run ./scripts/stack-up.sh first."
  if kubectl get crd applications.argoproj.io >/dev/null 2>&1 \
     && kubectl get application fleet-tracking -n argocd >/dev/null 2>&1; then
    die "ArgoCD manages this cluster and would undo the pauses below. Load-test the local overlay."
  fi
  SIM_IMAGE=$(kubectl get deployment/fleet-simulator -n "$NS" \
                -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null || true)
  : "${SIM_IMAGE:=ghcr.io/goutham-hegde/fleet-tracker/fleet-simulator:0.1.0-SNAPSHOT}"

  mkdir -p "$OUT"
  echo "time,step,elapsed_s,positions_in_kafka,processor_committed,processor_lag,exception_lag,processor_replicas" > "$OUT/samples.csv"
  trap restore EXIT

  SIMULATOR_REPLICAS=$(kubectl get deployment/fleet-simulator -n "$NS" -o jsonpath='{.spec.replicas}' 2>/dev/null || true)
  ARCHIVER_REPLICAS=$(kubectl get deployment/archiver -n "$NS" -o jsonpath='{.spec.replicas}' 2>/dev/null || true)

  log "Pausing the demo fleet and the archiver"
  [ -z "$SIMULATOR_REPLICAS" ] || kubectl scale deployment/fleet-simulator -n "$NS" --replicas=0 >/dev/null
  [ -z "$ARCHIVER_REPLICAS" ] || kubectl scale deployment/archiver -n "$NS" --replicas=0 >/dev/null
  kubectl wait --for=delete pod -n "$NS" -l 'app.kubernetes.io/name in (fleet-simulator,archiver)' \
    --timeout=120s >/dev/null 2>&1 || true
  kubectl delete pod -n "$NS" -l app.kubernetes.io/name=load-generator --ignore-not-found >/dev/null
  ok "only the test's traffic from here on; nothing reaches S3"

  log "Seeding reference data for $SEED_FLEET trucks"
  "$REPO_ROOT/scripts/seed-identity.sh" "$SEED_FLEET" >/dev/null
  "$REPO_ROOT/scripts/seed-itinerary.sh" "$SEED_FLEET" >/dev/null
  "$REPO_ROOT/scripts/seed-manifests.sh" "$SEED_FLEET" >/dev/null
  ok "assignments, itineraries and manifests"
}

# The autoscaler, held still for a sweep.
#
# Pinned (LOAD_PROCESSORS=N): KEDA's pause annotation, which sets the replica count and leaves it.
# Otherwise only scale-DOWN is switched off, and scaling up works exactly as it does in production.
# Two reasons, both found in the first sweep. A pod that leaves mid-step takes its latency counters
# with it, so a step measured half its positions. And giving a pod back is a rebalance: every
# consumer stops while partitions move, which put a 7.5-second p99 into a step that was otherwise
# keeping up -- charged to the load, when it was caused by the load having been light.
SCALING_HELD=false
hold_scaling() {
  SCALING_HELD=true
  if [ -n "$PINNED_PROCESSORS" ]; then
    log "Pinning the tracking processor at $PINNED_PROCESSORS pod(s)"
    kubectl annotate scaledobject/tracking-processor -n "$NS" \
      autoscaling.keda.sh/paused-replicas="$PINNED_PROCESSORS" --overwrite >/dev/null
    kubectl scale deployment/tracking-processor -n "$NS" --replicas="$PINNED_PROCESSORS" >/dev/null
    kubectl rollout status deployment/tracking-processor -n "$NS" --timeout=300s >/dev/null
    ok "$(processor_replicas) pod(s), autoscaling paused"
  else
    kubectl patch scaledobject/tracking-processor -n "$NS" --type merge -p \
      '{"spec":{"advanced":{"horizontalPodAutoscalerConfig":{"behavior":{"scaleDown":{"selectPolicy":"Disabled"}}}}}}' >/dev/null
    ok "autoscaling up as usual; scaling down held until the sweep ends"
  fi
}

release_scaling() {
  kubectl annotate scaledobject/tracking-processor -n "$NS" autoscaling.keda.sh/paused-replicas- >/dev/null 2>&1
  kubectl patch scaledobject/tracking-processor -n "$NS" --type merge -p \
    '{"spec":{"advanced":{"horizontalPodAutoscalerConfig":{"behavior":{"scaleDown":{"selectPolicy":null}}}}}}' >/dev/null 2>&1
  ok "autoscaling back to normal"
}

restore() {
  local status=$?
  set +e
  trap - EXIT
  echo
  [ "$SCALING_HELD" = false ] || release_scaling
  log "Restoring the demo fleet and the archiver"
  kubectl delete pod -n "$NS" -l app.kubernetes.io/name=load-generator --ignore-not-found --wait=false >/dev/null 2>&1
  if [ -n "$ARCHIVER_REPLICAS" ]; then
    local topics=() t
    for t in "${ARCHIVED_TOPICS[@]}"; do topics+=(--topic "$t"); done
    # Explicit topics rather than --all-topics: a group that has never committed has no topics to
    # reset, and would then start from the earliest offset -- straight through the test's traffic.
    if kafka_tool kafka-consumer-groups.sh --group archiver --reset-offsets --to-latest \
         "${topics[@]}" --execute >/dev/null 2>&1; then
      ok "archiver moved past everything the test produced"
      kubectl scale deployment/archiver -n "$NS" --replicas="$ARCHIVER_REPLICAS" >/dev/null
    else
      warn "Could not move the archiver's offsets, so it is left at zero replicas rather than let it"
      warn "archive the test. Retry the reset, then: kubectl scale deployment/archiver -n fleet --replicas=1"
    fi
  fi
  [ -z "$SIMULATOR_REPLICAS" ] \
    || kubectl scale deployment/fleet-simulator -n "$NS" --replicas="$SIMULATOR_REPLICAS" >/dev/null
  ok "results in ${OUT#"$REPO_ROOT"/}"
  exit "$status"
}

# ---------------------------------------------------------------------------------------------
# sweep

cmd_sweep() {
  local rates=("$@")
  [ ${#rates[@]} -gt 0 ] || rates=(100 250 500 1000 2000)
  prepare
  hold_scaling

  local csv="$OUT/sweep.csv"
  echo "offered_per_s,trucks,accepted_per_s,http_p50_ms,http_p99_ms,http_max_ms,dead_lettered,refused,unreachable,dropped,positions_in_per_s,positions_stored_per_s,processor_lag_max,drain_s,positions_timed,stored_p50_ms,stored_p99_ms,mongo_read_ms,mongo_write_ms,exception_lag_max,processors_max" > "$csv"

  # Unrecorded. The services' JVMs compile their hot paths as they run, so the first minute after a
  # deploy is slower than every minute after it, and would otherwise be charged to the first step.
  echo
  log "Warm-up: 250 messages/s for 60s, not recorded"
  start_load load-warmup $(( (250 + MESSAGES_PER_TRUCK - 1) / MESSAGES_PER_TRUCK )) 60
  watch_load load-warmup warmup >/dev/null

  local rate trucks name in_before in_after done_before done_after done_drained drain log_file
  local mongo_before mongo_after timed p50 p99 mongo_read mongo_write
  for rate in "${rates[@]}"; do
    trucks=$(( (rate + MESSAGES_PER_TRUCK - 1) / MESSAGES_PER_TRUCK ))
    name="load-$rate"
    echo
    log "Step: $rate messages/s offered ($trucks trucks) for ${STEP_SECONDS}s"
    wait_for_drain 900 >/dev/null || warn "the processor had not caught up before this step"
    read -r in_before done_before _ _ < <(group_totals)
    stored_buckets > "$OUT/buckets-$rate-before.txt"
    mongo_before=$(mongo_latency)

    start_load "$name" "$trucks" "$STEP_SECONDS"
    watch_load "$name" "$rate"
    read -r in_after done_after _ _ < <(group_totals)
    drain=$(wait_for_drain 900 || true)

    # After the drain rather than as the load stops, so that every position the step offered has
    # been stored and timed -- including the ones that waited in the backlog, which are the point.
    stored_buckets > "$OUT/buckets-$rate-after.txt"
    mongo_after=$(mongo_latency)
    read -r timed p50 p99 < <(bucket_percentiles "$OUT/buckets-$rate-before.txt" "$OUT/buckets-$rate-after.txt")
    read -r mongo_read mongo_write < <(mongo_averages "$mongo_before" "$mongo_after")

    # Everything consumed should have been timed, less the source's own duplicates (a phone
    # resending what it never saw acknowledged is consumed, not stored, and not timed). Well short
    # of that means a pod's counters were lost, and the percentiles describe a sample, not the step.
    read -r _ done_drained _ _ < <(group_totals)
    if [[ "$done_drained" =~ ^[0-9]+$ ]] && [[ "$done_before" =~ ^[0-9]+$ ]] \
       && [ "$timed" -lt $(( (done_drained - done_before) * 98 / 100 )) ]; then
      warn "timed $timed of $((done_drained - done_before)) positions consumed: the latency figures are a sample"
    fi

    log_file="$OUT/$name.log"
    echo "$rate,$trucks,$(per_second "$(total_field "$log_file" accepted)" 0),$(total_field "$log_file" p50),$(total_field "$log_file" p99),$(total_field "$log_file" max),$(total_field "$log_file" deadLettered),$(total_field "$log_file" refused),$(total_field "$log_file" unreachable),$(total_field "$log_file" dropped),$(per_second "$in_after" "$in_before"),$(per_second "$done_after" "$done_before"),$LAG_MAX,$drain,$timed,$p50,$p99,$mongo_read,$mongo_write,$EXLAG_MAX,$REPLICAS_MAX" >> "$csv"
    ok "$(tail -1 "$csv")"
  done

  echo
  column -t -s, "$csv" 2>/dev/null || cat "$csv"
}

# ---------------------------------------------------------------------------------------------
# pod-kill

declare -A KILL_AT=()

# A crash, not a shutdown: SIGKILL to the container's first process, sent from the Kind node. No
# shutdown hook runs, nothing is flushed, no offset is committed on the way out. Deleting the pod
# instead would send SIGTERM and allow a clean exit, which is exactly what this is not testing.
crash() {
  local target=$1 elapsed=$2 id pid
  id=$(docker exec "$NODE" crictl ps --name "^$target\$" -q 2>/dev/null | head -1 || true)
  if [ -z "$id" ]; then
    warn "t=${elapsed}s: no running $target container to crash"
    return
  fi
  pid=$(docker exec "$NODE" crictl inspect -o go-template --template '{{.info.pid}}' "$id" | tr -d '\r')
  docker exec "$NODE" sh -c "kill -9 $pid"
  echo "$elapsed,$target,$pid" >> "$OUT/crashes.csv"
  printf '\033[0;31m  kill -9\033[0m %s at t=%ss\n' "$target" "$elapsed"
}

crash_on_schedule() {
  local elapsed=$1 at
  for at in "${!KILL_AT[@]}"; do
    if [ "$elapsed" -ge "$at" ]; then
      crash "${KILL_AT[$at]}" "$elapsed"
      unset "KILL_AT[$at]"
    fi
  done
}

cmd_pod_kill() {
  local rate=${1:-500} trucks entry
  trucks=$(( (rate + MESSAGES_PER_TRUCK - 1) / MESSAGES_PER_TRUCK ))
  for entry in $KILL_SCHEDULE; do KILL_AT[${entry%%:*}]=${entry#*:}; done
  prepare
  echo "elapsed_s,target,pid" > "$OUT/crashes.csv"

  log "Waiting for the processor to finish what it already has"
  wait_for_drain 600 >/dev/null || die "the tracking processor is not catching up; fix that first"

  # An empty position history, so that afterwards everything in it was written during this run and
  # the comparison is between two complete sets rather than a set and a filter.
  reset_derived_state
  kubectl scale deployment/tracking-processor -n "$NS" --replicas=1 >/dev/null
  kubectl rollout status deployment/tracking-processor -n "$NS" --timeout=180s >/dev/null
  ok "tracking processor back, with a fresh time-series collection"

  end_offsets position.events.v1 > "$OUT/offsets-start.txt"
  end_offsets tracking.dlq.v1 > "$OUT/dlq-start.txt"
  [ -s "$OUT/offsets-start.txt" ] || die "could not read the position topic's offsets"

  echo
  log "Load: $rate messages/s ($trucks trucks) for ${KILL_RUN}s, crashing: $KILL_SCHEDULE"
  start_load load-kill "$trucks" "$KILL_RUN"
  ON_TICK=crash_on_schedule
  watch_load load-kill kill
  ON_TICK=""
  [ ${#KILL_AT[@]} -eq 0 ] || warn "crashes scheduled past the end of the run were skipped: ${!KILL_AT[*]}"

  log "Waiting for every consumer to recover and catch up"
  kubectl rollout status deployment/tracking-processor -n "$NS" --timeout=300s >/dev/null || true
  local drain
  drain=$(wait_for_drain 900) || die "the processor did not catch up within 15 minutes"
  ok "caught up ${drain}s after the load stopped"

  end_offsets position.events.v1 > "$OUT/offsets-end.txt"
  end_offsets tracking.dlq.v1 > "$OUT/dlq-end.txt"

  log "Reading every position event the run put on Kafka"
  local p start end
  : > "$OUT/produced-all.txt"
  while read -r p start; do
    end=$(awk -v p="$p" '$1 == p { print $2 }' "$OUT/offsets-end.txt")
    [ -n "$end" ] && [ "$end" -gt "$start" ] || continue
    { kafka_tool kafka-console-consumer.sh --topic position.events.v1 --partition "$p" \
        --offset "$start" --max-messages $((end - start)) --timeout-ms 60000 2>/dev/null || true; } \
      | tr -d '\r' | grep -o '"eventId":"[^"]*"' | cut -d'"' -f4 >> "$OUT/produced-all.txt" || true
  done < "$OUT/offsets-start.txt"

  log "Reading every measurement MongoDB stored"
  mongosh "mongodb://localhost:37017" --quiet --eval '
    const ids = db.getSiblingDB("fleet").getCollection("position.history")
      .find({}, { _id: 1 }).toArray().map(d => d._id);
    if (ids.length) print(ids.join("\n"));
  ' | tr -d '\r' > "$OUT/persisted-all.txt"

  LC_ALL=C sort -u "$OUT/produced-all.txt" > "$OUT/produced.txt"
  LC_ALL=C sort -u "$OUT/persisted-all.txt" > "$OUT/persisted.txt"
  LC_ALL=C comm -23 "$OUT/produced.txt" "$OUT/persisted.txt" > "$OUT/missing.txt"
  LC_ALL=C comm -13 "$OUT/produced.txt" "$OUT/persisted.txt" > "$OUT/unexpected.txt"
  LC_ALL=C sort "$OUT/persisted-all.txt" | uniq -d > "$OUT/stored-twice.txt"

  local records offset_span produced_n stored_docs persisted_n missing unexpected dlq repeated
  records=$(wc -l < "$OUT/produced-all.txt")
  offset_span=$(offsets_between "$OUT/offsets-start.txt" "$OUT/offsets-end.txt")
  produced_n=$(wc -l < "$OUT/produced.txt")
  stored_docs=$(wc -l < "$OUT/persisted-all.txt")
  persisted_n=$(wc -l < "$OUT/persisted.txt")
  missing=$(wc -l < "$OUT/missing.txt")
  unexpected=$(wc -l < "$OUT/unexpected.txt")
  repeated=$(wc -l < "$OUT/stored-twice.txt")
  dlq=$(offsets_between "$OUT/dlq-start.txt" "$OUT/dlq-end.txt")

  {
    echo "pod-kill run: $rate messages/s offered, ${KILL_RUN}s, $(date -u +%Y-%m-%dT%H:%MZ)"
    echo "crashes (kill -9):"
    tail -n +2 "$OUT/crashes.csv" | awk -F, '{ printf "  t=%ss  %s\n", $1, $2 }'
    echo "sender: $(grep -o 'load total=.*' "$OUT/load-kill.log" | tail -1)"
    echo
    echo "records appended to position.events.v1   $offset_span"
    echo "records read back                        $records"
    echo "distinct event ids produced              $produced_n"
    echo "measurements in position.history         $stored_docs"
    echo "distinct event ids persisted             $persisted_n"
    echo "produced but not persisted               $missing"
    echo "persisted but never produced             $unexpected"
    echo "stored more than once                    $repeated"
    echo "set aside on tracking.dlq.v1             $dlq"
    echo "processor caught up after                ${drain}s"
  } | tee "$OUT/pod-kill.txt"

  echo
  if [ "$records" -ne "$offset_span" ]; then
    die "read back $records of $offset_span records, so the comparison is incomplete"
  fi
  # What the platform promises is that nothing is lost, not that nothing repeats -- and those are
  # the same decision seen from two sides. Every id is derived from what the source said, never
  # generated, so a repeat is byte-identical and the last writer stores what the first one did.
  # Publishing to Kafka before recording in Mongo then makes a crash cost duplicates rather than
  # losses, which is the trade this design chose deliberately.
  #
  # The first pod-kill run asserted zero duplicates as well, and failed on one document out of
  # 149,736: a phone resent a fix 187 ms after the first copy, and the crash 14 seconds earlier had
  # rebalanced the partition onto a pod whose RecentEventIds set was empty. Nothing below that can
  # catch it -- position.history is a time-series collection, and those cannot carry a unique index.
  # So the gate asserts the invariant that holds, and duplicates are reported as a figure. A
  # duplicate that was NOT byte-identical would be a different matter, and would show up as an id
  # persisted that was never produced.
  if [ "$missing" -eq 0 ] && [ "$unexpected" -eq 0 ] && [ "$dlq" -eq 0 ]; then
    ok "PASS: produced == persisted ($produced_n distinct events), nothing lost, nothing dead-lettered" \
      | tee -a "$OUT/pod-kill.txt"
    [ "$repeated" -eq 0 ] \
      || warn "$repeated of $stored_docs stored twice (byte-identical; see stored-twice.txt)" \
           | tee -a "$OUT/pod-kill.txt"
  else
    warn "FAIL: see missing.txt and unexpected.txt" | tee -a "$OUT/pod-kill.txt"
    exit 1
  fi
}

case "${1:-}" in
  sweep) shift; cmd_sweep "$@" ;;
  pod-kill) shift; cmd_pod_kill "$@" ;;
  *) die "Usage: $0 sweep [rate ...] | pod-kill [rate]" ;;
esac
