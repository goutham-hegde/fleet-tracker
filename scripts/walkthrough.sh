#!/usr/bin/env bash
# A guided demonstration of the running platform, one claim at a time.
#
# stack-up.sh brings the platform up and demo.sh does the same with host jars. Neither says what is
# worth looking at beyond the map. This script walks through the claims the design documents make,
# and has the running system prove each one while you watch: real requests, real Kafka records,
# real MongoDB counts, and one real crash. Nothing is mocked and nothing is pre-recorded.
#
#   ./scripts/walkthrough.sh             all seven acts, pausing between them in a terminal
#   ./scripts/walkthrough.sh 3 7         only those acts
#   ./scripts/walkthrough.sh --no-pause  run straight through (also the default without a terminal)
#
#   1  the fleet                   what the platform concluded, and where to look
#   2  four feeds, one front door  ADR 0005
#   3  the same fact twice         ADR 0004: a repeat is byte-identical, so it costs nothing
#   4  bad input is kept           ADR 0005: 202 DEAD_LETTERED, and the original in the DLQ
#   5  contracts per customer      ADR 0006: 422 for a broken manifest, 503 for an unknown customer
#   6  the rules                   five SLA rules, raised and cleared
#   7  kill -9                     a crashed consumer resumes where it left off; nothing skipped
#
# Needs the cluster stack from stack-up.sh. Writes nothing but three sample messages (acts 2-4,
# with event times in 2026-09-03, which change no shipment's current position) and one crash.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require kubectl "Ships with Docker Desktop."
require curl "It ships with Git for Windows."
require node "The dashboard needs it too: Node 20 or later."

NS=fleet
NODE="$CLUSTER_NAME-control-plane"
GATEWAY=http://localhost:18081
SHIPMENTS=http://localhost:18082
API=http://localhost:18083
SAMPLES="$REPO_ROOT/docs/samples"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PAUSE=true
[ -t 0 ] || PAUSE=false
ACTS=()
for arg in "$@"; do
  case "$arg" in
    --no-pause) PAUSE=false ;;
    [1-7]) ACTS+=("$arg") ;;
    *) die "usage: walkthrough.sh [--no-pause] [act ...]   (acts 1-7)" ;;
  esac
done
[ "${#ACTS[@]}" -gt 0 ] || ACTS=(1 2 3 4 5 6 7)

# --- presentation ------------------------------------------------------------------------------

act() {
  printf '\n\033[1;35m━━ %s ━━\033[0m\n' "$*"
}

# Narration, indented so it reads apart from command output.
say() {
  printf '%s\n' "$@" | sed 's/^/   /'
}

# Show a command, then run it.
show() {
  printf '\033[0;36m$ %s\033[0m\n' "$*"
}

pause() {
  if [ "$PAUSE" = true ]; then
    printf '\n\033[2m   Enter for the next act, Ctrl-C to stop\033[0m'
    read -r _
  fi
}

# Runs a JavaScript expression over JSON on stdin. `j` is the parsed input; `out` prints lines.
js() {
  node -e '
    let s = "";
    process.stdin.on("data", d => s += d).on("end", () => {
      const j = JSON.parse(s);
      const out = (...l) => l.forEach(x => console.log("   " + x));
      eval(process.argv[1]);
    });' "$1"
}

# POST a file to the gateway and print "<http code> <body>".
post() {
  local feed=$1 type=$2 file=$3
  curl -s -w ' %{http_code}' -H "Content-Type: $type" --data-binary "@$file" "$GATEWAY/ingest/$feed"
}

# --- Kafka, read by offsets rather than by timing -------------------------------------------------

kexec() { MSYS_NO_PATHCONV=1 kubectl exec -n "$NS" "$@"; }
kafka_tool() { kexec kafka-0 -c kafka -- "/opt/kafka/bin/$1" --bootstrap-server localhost:9092 "${@:2}"; }

# "<partition> <next offset>" for every partition of a topic.
end_offsets() {
  kafka_tool kafka-get-offsets.sh --topic "$1" --time -1 2>/dev/null | tr -d '\r' |
    awk -F: 'NF == 3 { print $2, $3 }' | sort -n
}

# Every record appended to a topic since an end_offsets snapshot, one per line, as
# "<headers>\t<key>\t<value>". Reading from recorded offsets rather than tailing the topic means
# nothing depends on a consumer having joined before the message was sent.
records_since() {
  local topic=$1 before=$2 after
  after="$WORK/after-$topic"
  end_offsets "$topic" > "$after"
  awk 'NR == FNR { start[$1] = $2; next } ($1 in start) && $2 > start[$1] { print $1, start[$1], $2 - start[$1] }' \
    "$before" "$after" |
  while read -r partition from count; do
    [ "$count" -le 5000 ] || { from=$(( from + count - 5000 )); count=5000; }
    kafka_tool kafka-console-consumer.sh --topic "$topic" --partition "$partition" --offset "$from" \
      --max-messages "$count" --timeout-ms 10000 \
      --property print.headers=true --property print.key=true 2>/dev/null </dev/null | tr -d '\r'
  done
}

# "<log end> <committed> <lag>" for the tracking processor, summed over partitions.
processor_lag() {
  kafka_tool kafka-consumer-groups.sh --describe --group tracking-processor 2>/dev/null | tr -d '\r' |
    awk '$2 == "position.events.v1" && $5 ~ /^[0-9]+$/ {
           end += $5; if ($4 ~ /^[0-9]+$/) { cur += $4; lag += $6 } else { lag += $5 }
         } END { printf "%d %d %d\n", end, cur, lag }'
}

# --- preflight ---------------------------------------------------------------------------------

check_stack() {
  local missing=0 url
  for url in "$GATEWAY/actuator/health" "$SHIPMENTS/manifests?mode=PARCEL" "$API/api/meta"; do
    curl -sf -o /dev/null --max-time 5 "$url" || { warn "no answer from $url"; missing=1; }
  done
  [ "$missing" -eq 0 ] || die "the platform is not up. Run ./scripts/stack-up.sh first"
  kubectl get pod/kafka-0 -n "$NS" >/dev/null 2>&1 || die "no kafka-0 in namespace $NS"
  local sim
  sim=$(kubectl get deployment/fleet-simulator -n "$NS" -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)
  if [ -z "$sim" ] || [ "$sim" = 0 ]; then
    warn "the simulated fleet is not running, so nothing on the map will move."
    warn "  kubectl scale deployment/fleet-simulator -n fleet --replicas=1"
  fi
}

# --- the acts ----------------------------------------------------------------------------------

act_1() {
  act "1 · The fleet"
  say "Every truck on the map is the platform's conclusion, not the browser's. Movement, the next" \
      "stop, the estimate and the incidents all come from the services, and the page only draws them." \
      "Open http://localhost:18080 beside this terminal."
  echo
  show "curl $API/api/meta"
  curl -s "$API/api/meta"; echo
  show "curl $API/api/shipments   (summarised)"
  curl -s "$API/api/shipments" | js '
    const by = {};
    j.forEach(s => by[s.movement] = (by[s.movement] || 0) + 1);
    out(`${j.length} shipments: ` + Object.entries(by).map(([k, v]) => `${v} ${k}`).join(", "));
    const s = j.find(x => x.nextStop && x.nextStop.estimatedArrival) || j[0];
    if (s) {
      out("", `${s.shipmentId} on ${s.vehicleId}, via ${s.source}, ${s.movement}, stop ${s.stopsCompleted} of ${s.stopsTotal} done`);
      if (s.nextStop) out(`  next: ${s.nextStop.name}, ${s.nextStop.remainingKm.toFixed(1)} km by road` +
        (s.nextStop.estimatedArrival ? `, due ${s.nextStop.estimatedArrival} (confidence ${Math.round(s.nextStop.confidence * 100)}%)` : ""));
    }'
  echo
  say "Click a truck on the map: its plan, its geofences drawn in metres, its trail and its manifest." \
      "The live feed underneath is a plain event stream:  curl -N $API/api/stream"
}

act_2() {
  act "2 · Four feeds, one front door"
  say "Four sources that share nothing: a vendor's imperial JSON per vehicle, a phone app's terse" \
      "JSON per shipment, a carrier's batched EDI text with no coordinates, and a fridge sensor with no" \
      "position at all. Each has its own endpoint and parser, and the gateway turns all of them into" \
      "two envelope types, keyed by shipment. (docs/adr/0005)"
  local feed type file
  head -1 "$SAMPLES/mobile-app.jsonl"    > "$WORK/mobile.json"
  head -1 "$SAMPLES/reefer-sensor.jsonl" > "$WORK/reefer.json"
  cp "$SAMPLES/edi-214/interchange-0001.edi" "$WORK/edi.txt"
  for spec in "mobile application/json $WORK/mobile.json" \
              "reefer application/json $WORK/reefer.json" \
              "edi214 text/plain $WORK/edi.txt"; do
    read -r feed type file <<<"$spec"
    echo
    printf '   \033[2m%s\033[0m\n' "$(head -c 150 "$file" | tr '\n' ' ')…"
    show "curl -X POST $GATEWAY/ingest/$feed"
    printf '   %s\n' "$(post "$feed" "$type" "$file")"
  done
  echo
  say "One EDI file became four events, one per shipment in the batch. Status events carry no" \
      "position, and no position was made up for them."
}

act_3() {
  act "3 · The same fact, sent twice, is the same event"
  say "Phones resend and HTTP clients retry, and a pod can die between publishing and recording." \
      "Every id is derived from what the source stated, so a repeat is identical and costs nothing." \
      "Here one telematics message is sent twice. (docs/adr/0004)"
  head -1 "$SAMPLES/telematics.jsonl" > "$WORK/telematics.json"
  end_offsets position.events.v1 > "$WORK/before-pos"
  echo
  show "curl -X POST $GATEWAY/ingest/telematics   (twice)"
  printf '   %s\n' "$(post telematics application/json "$WORK/telematics.json")"
  printf '   %s\n' "$(post telematics application/json "$WORK/telematics.json")"
  echo
  show "what Kafka now holds for it"
  local stated
  stated=$(sed -n 's/.*"fixTime":"\([^"]*\)".*/\1/p' "$WORK/telematics.json")
  records_since position.events.v1 "$WORK/before-pos" | grep -F "\"occurredAt\":\"$stated\"" |
    sed -n 's/.*"eventId":"\([^"]*\)".*"occurredAt":"\([^"]*\)","receivedAt":"\([^"]*\)".*/eventId \1   occurred \2   received \3/p' |
    sed 's/^/   /'
  echo
  say "Two records with one eventId, differing only in when they arrived. Consumers skip the second" \
      "one, and anything keyed by the id overwrites itself. In S23's crash test this is why six" \
      "kill -9s cost one duplicate and no losses."
}

act_4() {
  act "4 · Bad input is kept, not refused"
  say "A corrupt message is corrupt on every retry, so answering 400 either loses it or starts a retry" \
      "loop. The gateway answers 202 DEAD_LETTERED: the message is stored, with its source and the" \
      "reason, in ingest.dlq.v1. (docs/adr/0005)"
  head -1 "$SAMPLES/faults/telematics.jsonl" > "$WORK/corrupt.json"
  end_offsets ingest.dlq.v1 > "$WORK/before-dlq"
  echo
  printf '   \033[2m%s\033[0m\n' "$(grep -o '"gps":{[^}]*}' "$WORK/corrupt.json")"
  show "curl -X POST $GATEWAY/ingest/telematics   (latitude NaN)"
  printf '   %s\n' "$(post telematics application/json "$WORK/corrupt.json")"
  echo
  show "the dead-letter record"
  records_since ingest.dlq.v1 "$WORK/before-dlq" | tail -1 | cut -c1-260 | sed 's/^/   /'
  echo
  say "The headers name the feed and the reason, so one feed's failures are a header filter away." \
      "The value carries the original request body byte for byte, in its 'body' field, so nothing a" \
      "carrier sent is lost while someone works out what went wrong."
}

act_5() {
  act "5 · Every customer has its own contract"
  say "Manifests are a typed envelope around a body that differs per customer. The four customers" \
      "share no body fields. Each body is checked against that customer's JSON Schema, and a new" \
      "customer is a new schema document, not a code change. (docs/adr/0006)"
  local id
  id=$(curl -s "$SHIPMENTS/manifests?customerId=MEDIVAULT" | js 'out(j[0] ? j[0].shipmentId : "")' | tr -d ' ')
  [ -n "$id" ] || { warn "no MEDIVAULT manifest on file. Run ./scripts/seed-manifests.sh"; return; }
  echo
  show "curl $SHIPMENTS/manifests/$id"
  curl -s "$SHIPMENTS/manifests/$id" > "$WORK/manifest.json"
  js 'out(`${j.customerId} / ${j.mode}: temperature ${JSON.stringify(j.body.temperature)}`)' < "$WORK/manifest.json"

  js 'const m = { shipmentId: j.shipmentId, customerId: j.customerId, mode: j.mode,
                  schemaVersion: j.schemaVersion, body: j.body };
      m.body.temperature.maxC = 40;
      console.log(JSON.stringify(m))' < "$WORK/manifest.json" > "$WORK/too-warm.json"
  echo
  show "POST it again with maxC = 40, above what a vaccine may be kept at"
  curl -s -w ' %{http_code}' -H 'Content-Type: application/json' --data-binary "@$WORK/too-warm.json" \
    "$SHIPMENTS/manifests" | cut -c1-300 | sed 's/^/   /'

  sed 's/"customerId":"MEDIVAULT"/"customerId":"NEWCO"/' "$WORK/too-warm.json" > "$WORK/newco.json"
  echo
  show "POST it for a customer with no schema on file"
  curl -s -w ' %{http_code}' -H 'Content-Type: application/json' --data-binary "@$WORK/newco.json" \
    "$SHIPMENTS/manifests" | cut -c1-300 | sed 's/^/   /'
  echo
  say "422 means the caller sent something its own contract forbids. 503 means the platform has not" \
      "been given that contract yet, and the same request will succeed once it has. Neither was stored."
}

act_6() {
  act "6 · The rules"
  say "Five SLA rules read the same streams as the tracking processor: temperature excursion, unplanned" \
      "stop, route deviation, late arrival and signal loss. Each one raises an incident and clears it" \
      "again, and severity depends on what the load is. The simulator injects roughly one disruption" \
      "per truck per simulated hour."
  echo
  show "curl $API/api/exceptions   (summarised)"
  curl -s "$API/api/exceptions" | js '
    const t = {};
    j.forEach(e => { const k = `${e.type} ${e.state}`; t[k] = (t[k] || 0) + 1; });
    Object.entries(t).sort().forEach(([k, v]) => out(`${String(v).padStart(4)}  ${k}`));
    const e = j.find(x => x.state === "OPEN");
    if (e) out("", `${e.shipmentId}, ${e.type}, ${e.severity}:`, `  ${e.detail}`);'
  echo
  say "On the map these are the panel on the right. Watch one move to \"recently cleared\"."
}

act_7() {
  act "7 · kill -9"
  say "A crash, not a shutdown: SIGKILL from the Kubernetes node, so no shutdown hook runs and no" \
      "offset is committed on the way out. Kubernetes restarts the container, and Kafka hands it the" \
      "positions after its last commit, so it may see some twice (act 3) and cannot skip any."
  local id pid end cur lag restarts tries
  echo
  show "tracking processor, before"
  read -r end cur lag <<<"$(processor_lag)"
  say "log end $end, committed $cur, lag $lag"
  restarts=$(kubectl get pods -n "$NS" -l app.kubernetes.io/name=tracking-processor \
    -o jsonpath='{.items[0].status.containerStatuses[0].restartCount}')

  id=$(docker exec "$NODE" crictl ps --name '^tracking-processor$' -q 2>/dev/null | head -1 || true)
  [ -n "$id" ] || { warn "no running tracking-processor container"; return; }
  pid=$(docker exec "$NODE" crictl inspect -o go-template --template '{{.info.pid}}' "$id" | tr -d '\r')
  echo
  show "docker exec $NODE kill -9 $pid"
  docker exec "$NODE" sh -c "kill -9 $pid"
  printf '   \033[0;31mkilled\033[0m\n'

  echo
  show "waiting for Kubernetes to restart it"
  tries=90
  until [ "$(kubectl get pods -n "$NS" -l app.kubernetes.io/name=tracking-processor \
      -o jsonpath='{.items[0].status.containerStatuses[0].restartCount}')" -gt "$restarts" ] &&
      kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=tracking-processor -n "$NS" \
        --timeout=5s >/dev/null 2>&1; do
    tries=$((tries - 1)); [ "$tries" -gt 0 ] || die "the tracking processor did not come back"
    sleep 2
  done
  kubectl get pods -n "$NS" -l app.kubernetes.io/name=tracking-processor | sed 's/^/   /'

  echo
  show "lag, until it has caught up"
  tries=40
  while :; do
    read -r end cur lag <<<"$(processor_lag)"
    say "log end $end, committed $cur, lag $lag"
    [ "$lag" -gt 100 ] || break
    tries=$((tries - 1)); [ "$tries" -gt 0 ] || { warn "still behind; check kubectl logs"; break; }
    sleep 3
  done
  echo
  say "The consumer carried on from its last commit, and its committed offset is back at the head of" \
      "the log. The full proof compares every event id Kafka holds with every id MongoDB stored, across" \
      "six crashes including the broker and the database:  ./scripts/load-test.sh pod-kill" \
      "(S23: 148,349 produced, 148,349 stored.) docs/performance.md has the numbers."
}

# --- main --------------------------------------------------------------------------------------

check_stack
printf '\033[1mFleet tracking: a walkthrough in %d act(s)\033[0m\n' "${#ACTS[@]}"
for i in "${!ACTS[@]}"; do
  "act_${ACTS[$i]}"
  [ "$i" -eq $(( ${#ACTS[@]} - 1 )) ] || pause
done
echo
ok "Done. The design behind each act is in docs/adr/, the history in PROGRESS.md."
