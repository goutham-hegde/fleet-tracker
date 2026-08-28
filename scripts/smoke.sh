#!/usr/bin/env bash
# Prove the platform is reachable and works, from the Windows host rather than
# from inside the cluster. That distinction is the whole point: a broker that
# answers on localhost:9092 inside its own pod proves nothing about whether the
# advertised listener, the NodePort and Kind's port mapping are wired up.
#
# Everything created here is named *.smoke and removed at the end.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

KAFKA_BOOTSTRAP="localhost:19092"
MONGO_URI="mongodb://localhost:37017"
TOPIC="smoke.test"
kafka() { "$REPO_ROOT/scripts/kafka-cli.sh" "$@"; }

# --- Kafka round trip --------------------------------------------------------
log "Kafka: connecting to $KAFKA_BOOTSTRAP"
kafka kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --list >/dev/null \
  || die "Cannot reach Kafka on $KAFKA_BOOTSTRAP. Is the platform up?"
ok "broker responded to a metadata request"

log "Kafka: creating topic '$TOPIC'"
kafka kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" \
  --create --if-not-exists --topic "$TOPIC" --partitions 1 --replication-factor 1 >/dev/null

msg="smoke-$(date +%s)"
log "Kafka: producing '$msg'"
printf '%s\n' "$msg" | kafka kafka-console-producer.sh \
  --bootstrap-server "$KAFKA_BOOTSTRAP" --topic "$TOPIC" 2>/dev/null

log "Kafka: consuming it back"
# --from-beginning reads the partition from offset 0 rather than from the end,
# so the message produced a second ago is visible. --max-messages 1 makes the
# consumer exit instead of tailing forever.
got=$(kafka kafka-console-consumer.sh --bootstrap-server "$KAFKA_BOOTSTRAP" \
  --topic "$TOPIC" --from-beginning --max-messages 1 --timeout-ms 20000 2>/dev/null | tr -d '\r')

if [ "$got" = "$msg" ]; then
  ok "round trip: sent '$msg', received '$got'"
else
  kafka kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --delete --topic "$TOPIC" >/dev/null 2>&1 || true
  die "Kafka round trip failed. Sent '$msg', received '$got'"
fi

kafka kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --delete --topic "$TOPIC" >/dev/null
ok "cleaned up topic '$TOPIC'"

echo
log "Canonical topics on the cluster"
kafka kafka-topics.sh --bootstrap-server "$KAFKA_BOOTSTRAP" --describe \
  | grep -E '^Topic:' | sed 's/^/  /'

# --- MongoDB round trip ------------------------------------------------------
echo
if ! command -v mongosh >/dev/null 2>&1; then
  warn "mongosh not on PATH -- skipping the MongoDB round trip."
  warn "Install with: winget install MongoDB.Shell"
  exit 1
fi

log "MongoDB: connecting to $MONGO_URI"
# There is an unrelated mongod on this machine's 27017. Asserting the port here
# is what keeps a green run from being a green run against the wrong database.
mongosh "$MONGO_URI" --quiet --eval '
  const doc = { _id: "smoke", ts: new Date(), note: "S3 round trip" };
  db = db.getSiblingDB("fleet_smoke");
  db.probe.deleteMany({ _id: "smoke" });
  db.probe.insertOne(doc);
  const back = db.probe.findOne({ _id: "smoke" });
  if (!back || back.note !== doc.note) { throw new Error("document did not round trip"); }
  print("  ok  wrote and read back: " + JSON.stringify(back));
  // serverStatus().host is the name the server calls itself. Reached on
  // localhost:37017 but answering as "mongodb-0:27017" is the proof that this
  // is the pod inside the cluster, and not the unrelated mongod that is
  // already running on this machine.
  print("  ok  server version " + db.version() +
        ", answering as " + db.serverStatus().host);
  db.dropDatabase();
  print("  ok  dropped database fleet_smoke");
' || die "MongoDB round trip failed."

echo
ok "Smoke test passed: Kafka and MongoDB are both reachable from the host."
