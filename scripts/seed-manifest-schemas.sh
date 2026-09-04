#!/usr/bin/env bash
# Load the per-customer manifest schemas the shipment service validates against.
#
# This is the script that makes M4's central claim true rather than asserted: onboarding a
# customer is running this, not releasing a service. The schemas themselves live as committed
# JSON under docs/schemas/manifests/ and are NOT retyped here -- same reasoning as the itinerary
# seed, which reads coordinates from docs/samples/lanes.json rather than holding a second copy
# of them that can drift without failing a build.
#
# An unseeded database is the quiet failure here, and it looks different from the gateway's:
# the service starts cleanly and then answers 503 to every submission, because it will not
# guess at a contract it has not been given. That is deliberate -- see ValidationResult.NoSchema.
#
# Idempotent. A document id is derived from the customer and the freight mode, so re-running
# updates the four documents in place rather than adding four more.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

MONGO_URI="mongodb://localhost:37017"
DB="fleet"
COLLECTION="manifest.schemas"
SCHEMA_DIR="$(dirname "${BASH_SOURCE[0]}")/../docs/schemas/manifests"

# The version stamped onto every manifest accepted against these schemas. Bump it when a schema
# changes so that a stored manifest can always be explained: one that would fail today's schema
# is a manifest from before the change, not a corruption.
SCHEMA_VERSION="2026-09-04"

command -v mongosh >/dev/null 2>&1 \
  || die "mongosh not found. Install with: winget install MongoDB.Shell"

# customer id : freight mode : schema file
SCHEMAS=(
  "MEDIVAULT:PHARMA_COLD_CHAIN:medivault.pharma-cold-chain.json"
  "VISTAMART:RETAIL_REPLENISHMENT:vistamart.retail-replenishment.json"
  "SOUTHERN-FREIGHT:LTL:southern-freight.ltl.json"
  "QUICKSHIP:PARCEL:quickship.parcel.json"
)

log "Seeding ${#SCHEMAS[@]} manifest schemas into $DB.$COLLECTION at $MONGO_URI"

for entry in "${SCHEMAS[@]}"; do
  customer="${entry%%:*}"
  rest="${entry#*:}"
  mode="${rest%%:*}"
  file="$SCHEMA_DIR/${rest#*:}"

  [ -f "$file" ] || die "schema file not found: $file"

  # The schema document is passed in as a shell variable rather than interpolated into the
  # JavaScript, so a quote or a dollar sign inside a schema cannot break out of the string.
  SCHEMA_JSON="$(cat "$file")" \
  CUSTOMER="$customer" MODE="$mode" VERSION="$SCHEMA_VERSION" \
  mongosh "$MONGO_URI" --quiet --eval "
    db = db.getSiblingDB('$DB');

    const customer = process.env.CUSTOMER;
    const mode = process.env.MODE;
    const schema = JSON.parse(process.env.SCHEMA_JSON);

    db['$COLLECTION'].replaceOne(
      { _id: customer + '/' + mode },
      {
        _id: customer + '/' + mode,
        customerId: customer,
        mode: mode,
        version: process.env.VERSION,
        schema: schema,
        updatedAt: new Date(),
        // Spring Data writes this on documents it saves; setting it here means a seeded
        // document and a service-written one deserialize through the same path rather than
        // one of them arriving without a type hint.
        _class: 'com.fleettracking.shipment.schema.ManifestSchema'
      },
      { upsert: true }
    );
  " >/dev/null || die "failed to seed schema for $customer/$mode"

  log "  $customer / $mode  <- $(basename "$file")"
done

# The index the validator's lookup uses. Created here rather than left to the service so that
# it is a property of the deployment rather than of whichever service happened to start first,
# the same reasoning as creating Kafka topics in a Job.
mongosh "$MONGO_URI" --quiet --eval "
  db = db.getSiblingDB('$DB');
  db['$COLLECTION'].createIndex({ customerId: 1, mode: 1 }, { name: 'customer_mode' });
" >/dev/null || die "failed to create index"

COUNT=$(mongosh "$MONGO_URI" --quiet --eval "
  db = db.getSiblingDB('$DB');
  print(db['$COLLECTION'].countDocuments({}));
")

log "Done. $COLLECTION holds $COUNT schema(s)."
