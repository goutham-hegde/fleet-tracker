#!/usr/bin/env bash
# Would the platform accept the manifests the platform seeded?
#
# scripts/seed-manifests.sh writes straight into MongoDB, which is the right shape for a seed --
# in a real deployment these arrive from customers' order systems and nothing about the collection
# or the rules would change. But it means the one component that owns the manifest contract, the
# shipment service, never sees them, so a seeded body can drift from the customer's committed
# schema and nothing anywhere says so.
#
# It had drifted, and by S16 three of the four customers were affected: VistaMart's purchase orders
# were bare strings where the schema wants objects, Southern Freight's pieces carried a 'count' the
# schema forbids, and QuickShip's tracking numbers, service levels and phone numbers all failed
# their own patterns. Every one of them stored, read and rendered perfectly. Nothing was broken --
# it was simply data the platform itself would have rejected, sitting in the platform's database
# and being shown to people as though it had passed.
#
# So this submits one manifest per (customer, mode) through the real endpoint. That pair is exactly
# what a schema is keyed by, so one of each covers every schema on file; submitting all sixty-four
# would test the same four contracts sixteen times each.
#
# Re-submitting is safe: the document id is the shipment id, so an accepted manifest replaces
# itself with the same content, and a rejected one is not written at all.
#
#   ./scripts/check-manifests.sh              against localhost:18082
#   ./scripts/check-manifests.sh 18082        explicitly
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

PORT="${1:-18082}"
MONGO_URI="mongodb://localhost:37017"
DB="fleet"

require mongosh "winget install MongoDB.Shell"
require curl "It ships with Git for Windows."

curl -sf -o /dev/null --max-time 3 "http://localhost:$PORT/manifests?mode=PARCEL" \
  || die "no shipment service on $PORT. Start it, or run scripts/demo.sh up"

log "Reading one manifest per customer and mode"

# One document per distinct (customerId, mode), as JSON lines.
#
# The shipment id comes from `_id` rather than from a `shipmentId` field, and that is not
# defensive: the two writers disagree. The seed script stores both, while the shipment service
# stores the id only as `_id` -- which is correct, since the manifest's id IS the shipment id and a
# second copy of it is a field that can drift. Reading `shipmentId` therefore works on a seeded
# document and produces an envelope with no id at all on one this service has written, which the
# endpoint answers with a flat 400 that names nothing. Found by this script rejecting manifests it
# had accepted a minute earlier.
#
# `createdAt` is dropped for a different reason: the service assigns its own, and sending one back
# would submit a field the caller does not own.
mapfile -t SAMPLES < <(mongosh "$MONGO_URI" --quiet --eval "
  const d = db.getSiblingDB('$DB');
  d.manifests.aggregate([
    { \$sort: { _id: 1 } },
    { \$group: { _id: { c: '\$customerId', m: '\$mode' }, doc: { \$first: '\$\$ROOT' } } },
    { \$sort: { _id: 1 } }
  ]).forEach(r => print(JSON.stringify({
    shipmentId: r.doc._id,
    customerId: r.doc.customerId,
    mode: r.doc.mode,
    schemaVersion: r.doc.schemaVersion,
    body: r.doc.body
  })));
")

[ "${#SAMPLES[@]}" -gt 0 ] || die "the manifests collection is empty. Run scripts/seed-manifests.sh"

failed=0
for sample in "${SAMPLES[@]}"; do
  [ -n "$sample" ] || continue
  label="$(printf '%s' "$sample" | sed -n 's/.*"customerId":"\([^"]*\)".*/\1/p')"
  body_file="$(mktemp)"
  printf '%s' "$sample" > "$body_file"

  response_file="$(mktemp)"
  code="$(curl -s -o "$response_file" -w '%{http_code}' \
    -X POST "http://localhost:$PORT/manifests" \
    -H 'Content-Type: application/json' --data-binary "@$body_file")"

  case "$code" in
    201)
      ok "$label accepted"
      ;;
    422)
      # The interesting failure, and the reason this script exists. The manifest was understood
      # perfectly and breaks the customer's own contract.
      warn "$label rejected by its own schema:"
      grep -o '"field":"[^"]*","message":"[^"]*"' "$response_file" \
        | sed 's/"field":"//; s/","message":"/  ->  /; s/"$//; s/^/      /'
      failed=$((failed + 1))
      ;;
    503)
      # Not the customer's fault and not the data's: there is no schema on file for this pair.
      warn "$label has no schema on file for mode. Run scripts/seed-manifest-schemas.sh"
      failed=$((failed + 1))
      ;;
    *)
      warn "$label unexpected $code: $(head -c 200 "$response_file")"
      failed=$((failed + 1))
      ;;
  esac
  rm -f "$body_file" "$response_file"
done

if [ "$failed" -gt 0 ]; then
  die "$failed of ${#SAMPLES[@]} seeded manifests would be rejected by the shipment service"
fi
ok "all ${#SAMPLES[@]} seeded customer contracts are satisfied by the data on file"
