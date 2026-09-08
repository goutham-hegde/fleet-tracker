#!/usr/bin/env bash
# The whole platform, from a stopped cluster to trucks moving on a map, in one command.
#
# Until now running this project meant seven terminals: five services, a simulator and a dev
# server, started in an order that is obvious only once you already know it, against a database
# whose derived collections have to be cleared first or every marker opens reading DELIVERED. That
# is fine for building and useless for showing, and "the demo path runs start to finish without
# intervention" is one of M5's exit criteria rather than a convenience.
#
# What this is NOT is a deployment. Every service here runs as a jar on the host against the Kafka
# and MongoDB inside Kind, which is exactly how they have been run all along -- containerising them
# and bringing the whole stack up from manifests is M6. This script makes the current arrangement
# reproducible; it does not pretend to be the next milestone.
#
#   ./scripts/demo.sh up      build, seed, reset, start everything, print the URL
#   ./scripts/demo.sh down    stop everything this started. Leaves the cluster alone
#   ./scripts/demo.sh status  what is running, and where its log is
#   ./scripts/demo.sh reset   clear the derived collections only (services must be stopped)
#
# Run 'down' before './mvnw verify'. On Windows the repackage goal renames a jar that a live JVM
# still holds open, and the failure names neither the service nor the reason.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

DEMO_DIR="$REPO_ROOT/.demo"
LOG_DIR="$DEMO_DIR/logs"
PID_DIR="$DEMO_DIR/pids"

MONGO_URI="mongodb://localhost:37017"
DB="fleet"

# Four trucks rather than sixty-four, and a time scale of 150. Both are ceilings rather than
# preferences: the simulator's HTTP sink runs one worker awaiting one broker acknowledgement per
# request, so it tops out near 100 messages a second and silently drops the rest -- by design,
# since the tick thread also moves every truck and must never block. Four trucks at 150x sits
# under that. Faster or wider looks fine and quietly loses positions.
TRUCKS="${DEMO_TRUCKS:-4}"
TIME_SCALE="${DEMO_TIME_SCALE:-150}"
FLEET_SIZE="${DEMO_FLEET_SIZE:-64}"

# The five services, in the order they must start. Each line is:
#   name : module : health port (or "-" for a service with no HTTP port)
#
# The order matters in one place only -- the gateway must be up before the simulator posts to it --
# but the consumers are started first anyway so that nothing produced during the run is missed by a
# consumer that had not yet joined its group.
SERVICES=(
  "ingest-gateway:services/ingest-gateway:18081"
  "shipment-service:services/shipment-service:18082"
  "tracking-processor:services/tracking-processor:-"
  "exception-service:services/exception-service:-"
  "dashboard-api:services/dashboard-api:18083"
)

# --- process bookkeeping ---------------------------------------------------

# A PID file per process rather than one list, so a crashed service can be seen and restarted
# without disturbing the rest.
pid_file() { printf '%s/%s.pid' "$PID_DIR" "$1"; }
log_file() { printf '%s/%s.log' "$LOG_DIR" "$1"; }

running() {
  local file
  file="$(pid_file "$1")"
  [ -f "$file" ] || return 1
  kill -0 "$(cat "$file")" 2>/dev/null
}

start_process() {
  local name="$1"; shift
  if running "$name"; then
    ok "$name already running (pid $(cat "$(pid_file "$name")"))"
    return 0
  fi
  : > "$(log_file "$name")"
  # nohup and a redirect rather than a job: this script exits and everything it started must
  # outlive it, which is the whole point of writing pid files.
  nohup "$@" >>"$(log_file "$name")" 2>&1 &
  echo $! > "$(pid_file "$name")"
}

stop_process() {
  local name="$1" file pid
  file="$(pid_file "$name")"
  [ -f "$file" ] || return 0
  pid="$(cat "$file")"
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    # Give it a moment to close its Kafka consumer cleanly. A consumer killed outright leaves its
    # group without a leave request, so the broker waits out the session timeout before rebalancing
    # -- which is why a restarted service can appear to do nothing for half a minute.
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      kill -0 "$pid" 2>/dev/null || break
      sleep 0.5
    done
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$file"
  ok "stopped $name"
}

# Whether a service has finished starting.
#
# The check is Spring Boot's own final startup line rather than a health endpoint, and it is the
# right signal for all five: two of these services have no HTTP port at all (they are Kafka
# consumers and a timer), and for the three that do, the line is logged after the web server is
# already listening. One readiness rule for five services beats three rules and an exception.
service_ready() {
  grep -q "Started .*Application in" "$(log_file "$1")"
}

wait_ready() {
  local name="$1" tries=90
  while [ "$tries" -gt 0 ]; do
    if ! running "$name"; then
      warn "$name exited during startup. Last lines:"
      tail -n 20 "$(log_file "$name")" >&2
      die "$name did not start"
    fi
    if service_ready "$name"; then
      ok "$name up"
      return 0
    fi
    tries=$((tries - 1))
    sleep 1
  done
  warn "$name never reported ready. Last lines:"
  tail -n 20 "$(log_file "$name")" >&2
  die "$name did not become ready"
}

# --- the pieces ------------------------------------------------------------

check_platform() {
  require kubectl "Install it, or run scripts/preflight.sh."
  require mongosh "winget install MongoDB.Shell"
  kubectl get ns fleet >/dev/null 2>&1 \
    || die "the fleet namespace is missing. Run scripts/cluster-start.sh then scripts/platform-up.sh"
  log "Waiting for Kafka and MongoDB to be ready"
  kubectl wait --for=condition=ready pod/kafka-0 -n fleet --timeout=180s >/dev/null \
    || die "kafka-0 never became ready. kubectl get endpointslice -n fleet tells you more than the container log does"
  kubectl wait --for=condition=ready pod/mongodb-0 -n fleet --timeout=180s >/dev/null \
    || die "mongodb-0 never became ready"
  ok "platform ready: kafka localhost:19092 | mongo localhost:37017"
}

build_all() {
  log "Building the services and the dashboard"
  # Tests are skipped here deliberately: this path is for demonstrating, and './mvnw verify' is the
  # gate that decides whether the code is any good. Running the integration tests would also start
  # Testcontainers alongside a cluster that is already up.
  (cd "$REPO_ROOT" && ./mvnw -q -DskipTests package) || die "the Maven build failed"
  ok "jars built"
  (cd "$REPO_ROOT/dashboard" && npm install --silent && npm run build --silent) \
    || die "the dashboard build failed"
  ok "dashboard built"
}

seed_all() {
  log "Seeding reference data (all four scripts are idempotent)"
  "$REPO_ROOT/scripts/seed-identity.sh" "$FLEET_SIZE" >/dev/null
  "$REPO_ROOT/scripts/seed-itinerary.sh" "$FLEET_SIZE" >/dev/null
  "$REPO_ROOT/scripts/seed-manifest-schemas.sh" >/dev/null
  "$REPO_ROOT/scripts/seed-manifests.sh" "$FLEET_SIZE" >/dev/null
  ok "dispatch, itineraries, manifest schemas and manifests seeded"
}

# Clear everything the platform derived, so a re-run starts from nothing.
#
# Without this every marker opens reading DELIVERED, and correctly so: a stop arrived at and
# departed from is terminal, and re-running the simulator over the same shipment ids replays a
# journey the geofencer has already concluded. That is S10 working, not a bug -- but it makes for a
# demonstration in which nothing ever happens.
#
# position.history is a time-series collection and dropping it under a live consumer is the S10
# failure: the next insert silently creates an ORDINARY collection in its place, with no buckets,
# no compression and no error. The tracking processor recreates it properly at startup and refuses
# to run if it finds an ordinary one, which is why this is only ever done while it is stopped.
reset_state() {
  for name in tracking-processor exception-service; do
    running "$name" && die "stop the services first: ./scripts/demo.sh down"
  done
  log "Clearing derived state"
  mongosh "$MONGO_URI" --quiet --eval "
    const d = db.getSiblingDB('$DB');
    ['geofence.state','shipment.eta','shipment.position','exception.state','exceptions','position.history']
      .forEach(c => d.getCollection(c).drop());
  " >/dev/null
  ok "position history, geofence state, ETAs and incidents cleared"
}

start_services() {
  for entry in "${SERVICES[@]}"; do
    local name module port jar
    IFS=: read -r name module port <<<"$entry"
    jar="$REPO_ROOT/$module/target/$name-0.1.0-SNAPSHOT.jar"
    [ -f "$jar" ] || die "missing $jar. Run './scripts/demo.sh up' without --no-build"
    if [ "$port" = "-" ]; then log "Starting $name (no HTTP port)"; else log "Starting $name on $port"; fi
    start_process "$name" java -jar "$jar"
    wait_ready "$name"
  done
}

start_dashboard() {
  log "Starting the dashboard on 18080"
  # The built assets rather than the dev server. 18080 is the port the README has reserved for the
  # dashboard since M0 and it is in the API's CORS list, and serving what 'npm run build' produced
  # is a rehearsal of the deployment rather than a different thing that happens to work.
  # Vite's own entry point rather than 'npm run preview'. npm launches vite as a child, so killing
  # npm on Windows leaves the server holding 18080 and the next 'up' fails on a port that nothing
  # visible is using. One process, one pid, one kill.
  # The root is positional; vite has no --root on this subcommand. Passing it this way also means
  # the port and strictPort come from dashboard/vite.config.ts rather than from flags here, so
  # there is still exactly one place that says the dashboard lives on 18080.
  start_process dashboard node "$REPO_ROOT/dashboard/node_modules/vite/bin/vite.js"     preview "$REPO_ROOT/dashboard"
  local tries=30
  while [ "$tries" -gt 0 ]; do
    curl -sf -o /dev/null --max-time 2 "http://localhost:18080/" && { ok "dashboard up on 18080"; return 0; }
    tries=$((tries - 1))
    sleep 1
  done
  die "the dashboard never answered on 18080"
}

start_simulator() {
  log "Starting the fleet ($TRUCKS trucks at ${TIME_SCALE}x, with disruptions)"
  # The 'disrupted' profile, not 'chaos'. A fault breaks the wire -- dropped, duplicated and
  # corrupted messages -- and proves the gateway's dead-lettering holds. A disruption breaks the
  # world: the truck actually breaks down, the reefer actually fails, the driver actually diverts.
  # Every message this produces is perfectly formed and completely accurate; it simply describes
  # something bad, which is what the SLA rules exist to judge.
  #
  # repeat-routes is left ON, so a finished truck is replaced by a fresh one and the fleet keeps
  # moving for as long as somebody is watching.
  start_process simulator java -jar \
    "$REPO_ROOT/tools/fleet-simulator/target/fleet-simulator-0.1.0-SNAPSHOT.jar" \
    --spring.profiles.active=disrupted \
    --fleet.simulator.emit.http.enabled=true \
    --fleet.simulator.time-scale="$TIME_SCALE" \
    --fleet.simulator.tick-interval=200ms \
    --fleet.simulator.trucks="$TRUCKS"
  sleep 3
  running simulator || { tail -n 20 "$(log_file simulator)" >&2; die "the simulator exited"; }
  ok "fleet rolling"
}

# --- commands --------------------------------------------------------------

cmd_up() {
  mkdir -p "$LOG_DIR" "$PID_DIR"
  check_platform
  [ "${1:-}" = "--no-build" ] || build_all
  seed_all
  reset_state
  start_services
  start_dashboard
  # Before the fleet starts moving, not after: a manifest the platform itself would reject is the
  # one thing on this screen that looks completely correct while being wrong, and the point of a
  # demonstration path is that it fails loudly rather than showing something plausible.
  "$REPO_ROOT/scripts/check-manifests.sh" 18082
  start_simulator

  printf '\n'
  ok "The demo is up: \033[1mhttp://localhost:18080\033[0m"
  cat <<'NOTE'

  Sixty seconds, in order:

    0:00  Trucks appear on four Indian freight lanes and start moving. The colour is what the
          platform concluded, not what the browser guessed: blue moving, green inside a geofence,
          amber stopped somewhere that is not a planned stop.
    0:15  Click one. Its plan, its geofences as real circles in metres, and where it has actually
          been. Scroll the card for the manifest -- and click a truck on a different lane, because
          the four customers share no fields at all and the same panel draws both.
    0:35  Watch the exceptions panel on the right. A breakdown, a detour, a reefer failure or a
          slowdown is injected roughly once per truck-hour of simulated time, and the rule that
          judges it publishes within seconds of the evidence arriving.
    0:50  Wait for one to move down into "recently cleared". Every rule raises AND clears, and the
          duration shown there is the platform's own measure, not the browser's.

  Under it:  curl -N localhost:18083/api/stream     the same events, unrendered
             ./scripts/demo.sh status               what is running
             ./scripts/demo.sh down                 stop it all (leaves the cluster up)

NOTE
}

cmd_down() {
  stop_process simulator
  stop_process dashboard
  # Reverse order, so consumers leave their groups before the gateway stops feeding them.
  for entry in dashboard-api exception-service tracking-processor shipment-service ingest-gateway; do
    stop_process "$entry"
  done
  ok "everything this script started is stopped. The cluster is untouched"
}

cmd_status() {
  printf '%-20s %-10s %s\n' PROCESS STATE LOG
  for name in ingest-gateway shipment-service tracking-processor exception-service dashboard-api dashboard simulator; do
    if running "$name"; then
      printf '%-20s %-10s %s\n' "$name" "up($(cat "$(pid_file "$name")"))" "$(log_file "$name")"
    else
      printf '%-20s %-10s %s\n' "$name" "down" "-"
    fi
  done
}

case "${1:-up}" in
  up)     shift || true; cmd_up "$@" ;;
  down)   cmd_down ;;
  status) cmd_status ;;
  reset)  reset_state ;;
  *)      die "usage: demo.sh [up [--no-build] | down | status | reset]" ;;
esac
