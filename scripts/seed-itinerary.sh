#!/usr/bin/env bash
# Seed itinerary reference data: which stops each load is scheduled to visit, where
# they are, and how big a geofence each one has. The tracking processor decides that
# a shipment has arrived somewhere by comparing its positions against these, so an
# unseeded database means the geofencer sees every truck as permanently nowhere --
# and, exactly as with the identity data in S8, it looks perfectly healthy doing it.
#
# The stops come from docs/samples/lanes.json, which is generated from the simulator's
# lane definitions and committed. They are deliberately NOT retyped here: coordinates
# maintained in two places, where a transposed digit fails no build, is the same trap
# that a fixed identity list was before S8. Regenerate the file with:
#
#   ./mvnw -pl tools/fleet-simulator -am package
#   java -Dloader.main=com.fleettracking.simulator.export.LaneExport \
#     -cp tools/fleet-simulator/target/fleet-simulator-0.1.0-SNAPSHOT.jar \
#     org.springframework.boot.loader.launch.PropertiesLauncher docs/samples/lanes.json
#
# Idempotent. A document id is the shipment id, so re-running replaces each itinerary
# in place rather than adding a second copy. Run it as often as you like.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

MONGO_URI="mongodb://localhost:37017"
DB="fleet"
COLLECTION="itinerary"
LANES_FILE="$(dirname "${BASH_SOURCE[0]}")/../docs/samples/lanes.json"

# Must match seed-identity.sh. Both scripts provision ahead of the simulator's default
# eight trucks, because with repeat-routes on a finished truck is replaced by a fresh
# one numbered 9, 10, 11 ... and a load with no itinerary is a load that never arrives
# anywhere.
FLEET_SIZE="${1:-64}"

command -v mongosh >/dev/null 2>&1 \
  || die "mongosh not on PATH. Install with: winget install MongoDB.Shell"

[ -f "$LANES_FILE" ] \
  || die "$LANES_FILE not found. Regenerate it -- see the header of this script."

LANES_JSON="$(cat "$LANES_FILE")"

log "Seeding itineraries for $FLEET_SIZE shipments into $DB.$COLLECTION at $MONGO_URI"

# The lane a load runs is encoded in its own id: the simulator names a load
# SHP-<first three letters of the route id, upper-cased>-<number>, dealing trucks onto
# the lanes round-robin. So the shipment id is enough to know the itinerary, and the
# rule is applied here rather than a mapping table being maintained alongside it.
mongosh "$MONGO_URI" --quiet --eval "
  db = db.getSiblingDB('$DB');
  const catalogue = $LANES_JSON;
  const lanes = catalogue.lanes;

  const ops = [];
  for (let n = 1; n <= $FLEET_SIZE; n++) {
    const lane = lanes[(n - 1) % lanes.length];
    const suffix = String(n).padStart(4, '0');
    const shipmentId = 'SHP-' + lane.code + '-' + suffix;

    const doc = {
      _id: shipmentId,
      shipmentId: shipmentId,
      routeId: lane.routeId,
      // The stops in the order they are visited. Order is carried explicitly as seq
      // rather than left implicit in the array, because a geofence decision reads a
      // single stop and should not have to know its index to say which one it was.
      stops: lane.stops.map(s => ({
        stopId: s.stopId,
        seq: s.seq,
        name: s.name,
        city: s.city,
        state: s.state,
        // Stored as separate numbers, matching how the position events carry them.
        // Not GeoJSON: nothing here is queried geospatially. The geofence question is
        // 'how far is this one point from that one point', which is arithmetic, and
        // a 2dsphere index would be machinery for a query nobody makes.
        latitude: s.latitude,
        longitude: s.longitude,
        // Per stop, not global. A 400 m distribution yard and a 120 m kerbside dock
        // are not the same size, and a single radius would either miss a truck parked
        // at the far fence or catch traffic passing the dock on the street.
        radiusMeters: s.radiusMeters,
        kind: s.kind
      }))
    };
    ops.push({ replaceOne: { filter: { _id: doc._id }, replacement: doc, upsert: true } });
  }

  const result = db.$COLLECTION.bulkWrite(ops);
  print('  ok  ' + result.upsertedCount + ' inserted, ' + result.modifiedCount + ' updated');

  // No index is created here, and that is deliberate rather than an omission: the
  // document id IS the shipment id, so the only lookup this collection serves is
  // already answered by the primary key every collection has.
  const stops = db.$COLLECTION.aggregate([
    { \$project: { n: { \$size: '\$stops' } } },
    { \$group: { _id: null, total: { \$sum: '\$n' } } }
  ]).toArray();
  print('  ok  collection now holds ' + db.$COLLECTION.countDocuments()
        + ' itineraries covering ' + (stops.length ? stops[0].total : 0) + ' scheduled stops');
" || die "Seeding failed. Is the platform up? Try ./scripts/platform-up.sh"

echo
ok "Itineraries seeded. The tracking processor can now geofence every load."
log "Inspect one with:"
echo "    mongosh $MONGO_URI --eval 'db.getSiblingDB(\"$DB\").$COLLECTION.findOne()'"
