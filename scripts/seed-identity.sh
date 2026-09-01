#!/usr/bin/env bash
# Seed dispatch reference data: which tractor is pulling which load, wearing which
# devices, over which period. The ingest gateway resolves every inbound message
# against this collection, so an unseeded database means all four feeds dead-letter
# as unresolvable while the gateway itself looks perfectly healthy.
#
# Idempotent. Every document id is derived from the shipment and the assignment's
# start instant, so re-running updates rows in place rather than adding a second,
# contradictory copy of each. Run it as often as you like.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

MONGO_URI="mongodb://localhost:37017"
DB="fleet"
COLLECTION="assignments"

# How many trucks to provision reference data for. The simulator's default fleet is
# eight, but with repeat-routes on (its default) a finished truck is replaced by a
# fresh one numbered 9, 10, 11 ... and every event from those would resolve to
# nothing. Provisioning ahead is what stops a long demo run quietly filling the
# dead-letter topic with perfectly valid freight.
FLEET_SIZE="${1:-64}"

command -v mongosh >/dev/null 2>&1 \
  || die "mongosh not on PATH. Install with: winget install MongoDB.Shell"

log "Seeding $FLEET_SIZE assignments into $DB.$COLLECTION at $MONGO_URI"

# The lanes, in the order the simulator deals trucks onto them: truck 1 runs the
# Chicago lane, truck 2 the Los Angeles cold-chain lane, and so on round-robin.
# Generated from that rule rather than listed by hand, because it is the same rule
# the simulator uses -- a hand-typed list would be a second, drifting statement of
# the fleet rather than a copy of it.
mongosh "$MONGO_URI" --quiet --eval "
  db = db.getSiblingDB('$DB');
  const lanes = ['CHI', 'LAX', 'ATL', 'HOU'];

  // Backdated deliberately. The committed contract fixtures in docs/samples were
  // captured on 2026-08-31, and a window opening 'now' would make every one of them
  // resolve to nothing -- a failure that looks like four broken normalizers rather
  // than like reference data that was never backdated.
  const epoch = ISODate('2026-01-01T00:00:00Z');

  const ops = [];
  for (let n = 1; n <= $FLEET_SIZE; n++) {
    const suffix = String(n).padStart(4, '0');
    const shipmentId = 'SHP-' + lanes[(n - 1) % lanes.length] + '-' + suffix;
    const doc = {
      _id: shipmentId + '@' + epoch.toISOString(),
      shipmentId: shipmentId,
      vehicleId: 'VEH-' + suffix,
      // Two identifiers for one truck that share nothing: the in-cab telematics unit
      // and the probe on the trailer behind it. Resolving both to one load is the
      // entire job of this collection.
      deviceIds: ['TLM-' + suffix, 'DEV-' + suffix],
      validFrom: epoch,
      // null, not a far-future date: the load is running and dispatch has not said
      // when it ends. The gateway's query has a branch for exactly this.
      validTo: null
    };
    ops.push({ replaceOne: { filter: { _id: doc._id }, replacement: doc, upsert: true } });
  }
  const result = db.$COLLECTION.bulkWrite(ops);
  print('  ok  ' + result.upsertedCount + ' inserted, ' + result.modifiedCount + ' updated');

  // Indexes created explicitly here, for the same reason Kafka topics are created by
  // a Job with explicit partition counts instead of by auto-creation: a collection
  // that indexes itself the first time something queries it is a collection whose
  // performance depends on which query ran first.
  //
  // Each index leads with the identifier the lookup matches on and carries validFrom
  // behind it, which is the order the query asks in -- equality first, then range.
  db.$COLLECTION.createIndex({ vehicleId: 1, validFrom: 1 });
  db.$COLLECTION.createIndex({ deviceIds: 1, validFrom: 1 });
  db.$COLLECTION.createIndex({ shipmentId: 1, validFrom: 1 });
  print('  ok  3 indexes present');

  print('  ok  collection now holds ' + db.$COLLECTION.countDocuments() + ' assignments');
" || die "Seeding failed. Is the platform up? Try ./scripts/platform-up.sh"

echo
ok "Reference data seeded. The gateway can now resolve all four feeds."
log "Inspect it with:"
echo "    mongosh $MONGO_URI --eval 'db.getSiblingDB(\"$DB\").$COLLECTION.find().limit(3)'"
