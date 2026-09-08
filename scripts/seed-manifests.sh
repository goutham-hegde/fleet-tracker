#!/usr/bin/env bash
# Give every simulated load some paperwork.
#
# S12 built the manifest store but nothing produces manifests, so until now they existed only
# where a test or a person put one. Two of S13's five SLA rules read a customer's commitment out
# of a manifest -- the temperature band a cold-chain load must stay inside, and the window a
# distribution centre booked -- and with an empty collection both rules are silent. Silent is
# also what a correctly working exception service looks like, which is exactly the failure mode
# S8 found in the gateway arriving through a different door.
#
# In a real deployment these arrive from the customers' order systems. Nothing about the
# collection, the schemas or the rules would change; seeding them here rather than compiling
# them in is what makes that true rather than claimed.
#
# The customer is chosen by the lane, matching what actually moves on it:
#
#   HYD  hyd-blr-cold   MEDIVAULT         pharma cold chain   -- the only refrigerated lane
#   DEL  del-bom-nh48   VISTAMART         retail DC           -- the only booked delivery window
#   BLR  blr-maa-ltl    SOUTHERN-FREIGHT  part-truckload      -- multi-drop, no window
#   BOM  bom-pnq-dray   QUICKSHIP         parcel              -- port drayage
#
# It follows that LATE_ARRIVAL only ever applies to the Delhi lane and TEMPERATURE_EXCURSION only
# to the Hyderabad one. That is correct rather than a gap: a carrier cannot be late against a
# deadline nobody set, and a dry van has no cold chain to break.
#
# Idempotent. The document id is the shipment id, so re-running replaces each manifest in place.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

MONGO_URI="mongodb://localhost:37017"
DB="fleet"
COLLECTION="manifests"

# Must match seed-identity.sh and seed-itinerary.sh. A load with a manifest but no itinerary is
# as useless as one with an itinerary and no manifest.
FLEET_SIZE="${1:-64}"

# Must match the version seed-manifest-schemas.sh stamps, or a stored manifest will name a schema
# document that does not exist.
SCHEMA_VERSION="2026-09-04"

# How long after seeding the retail delivery window closes.
#
# This is the one setting here that has to be thought about rather than accepted. The window is
# an absolute instant, and the simulator starts its clock at the wall-clock moment it is launched
# -- so a window is only meaningful relative to when the run begins. Seed, then run.
#
# Sized against the Delhi-Mumbai lane, which is about 1,400 km of road and takes roughly a day of
# simulated time at highway speed plus its dwells. Ten hours is comfortably short of that, so a
# load on that lane is projected late early in the run and stays late -- which is what makes the
# rule visible in a demo. Widen it to watch estimates converge inside a window instead.
WINDOW_OPENS_HOURS="${WINDOW_OPENS_HOURS:-6}"
WINDOW_CLOSES_HOURS="${WINDOW_CLOSES_HOURS:-10}"

command -v mongosh >/dev/null 2>&1 \
  || die "mongosh not on PATH. Install with: winget install MongoDB.Shell"

log "Seeding manifests for $FLEET_SIZE shipments into $DB.$COLLECTION at $MONGO_URI"
log "Retail delivery windows open in ${WINDOW_OPENS_HOURS}h and close in ${WINDOW_CLOSES_HOURS}h"

mongosh "$MONGO_URI" --quiet --eval "
  db = db.getSiblingDB('$DB');

  const now = new Date();
  const hours = h => new Date(now.getTime() + h * 3600 * 1000);
  const days  = d => new Date(now.getTime() + d * 86400 * 1000);
  const iso   = d => d.toISOString().replace(/\.[0-9]{3}Z\$/, 'Z');

  // The lane code is the middle segment of the shipment id, which seed-itinerary.sh derives the
  // same way. Both scripts read the lane from the id rather than maintaining a mapping table.
  const lanes = ['DEL', 'HYD', 'BLR', 'BOM'];

  // Each builder returns the customer, the freight mode, and the untyped body their own committed
  // JSON Schema constrains. The bodies share no fields at all, which is the entire argument for
  // holding them in one MongoDB collection rather than one relational table.
  const builders = {
    // Pharma cold chain. The temperature block sits at the platform's reserved path, which is
    // what lets the cold-chain rule read a band without knowing this customer exists.
    HYD: n => ({
      customerId: 'MEDIVAULT',
      mode: 'PHARMA_COLD_CHAIN',
      body: {
        drugLicenceNo: 'TG/28/2019',
        temperature: {
          minC: 2,
          maxC: 8,
          // MediVault's own judgement about their own product, and it beats the platform's
          // default of twenty minutes.
          excursionToleranceMinutes: 30
        },
        consignment: {
          batchNo: 'MV' + String(240000 + n),
          expiryDate: iso(days(540)).substring(0, 10),
          units: 400 + (n % 7) * 120,
          controlledSubstance: n % 5 === 0,
          productName: 'Recombinant vaccine, 10-dose vial'
        },
        custody: [
          { party: 'MediVault Genome Valley', handedOverAt: iso(hours(-2)) }
        ]
      }
    }),

    // Retail replenishment. The only customer that books a dock slot, and therefore the only one
    // that can be late against one.
    DEL: n => ({
      customerId: 'VISTAMART',
      mode: 'RETAIL_REPLENISHMENT',
      body: {
        dcCode: 'DC-BHW-0' + (1 + (n % 4)),
        asnNumber: 'ASN' + String(880000 + n),
        // Objects, not bare order numbers. VistaMart's schema requires a line count and a value
        // per order, and the number must be PO followed by exactly eight digits -- which is why
        // there is no hyphen in it.
        purchaseOrders: [
          { poNumber: 'PO' + String(50000000 + n), lineCount: 12 + (n % 40), valueInr: 180000 + (n % 23) * 45000, department: ['Grocery', 'Apparel', 'Home', 'Electronics'][n % 4] },
          { poNumber: 'PO' + String(50000000 + n + 1), lineCount: 4 + (n % 9), valueInr: 60000 + (n % 17) * 12000 }
        ],
        deliveryWindow: {
          opensAt: iso(hours($WINDOW_OPENS_HOURS)),
          closesAt: iso(hours($WINDOW_CLOSES_HOURS)),
          dockNumber: 1 + (n % 12)
        },
        pallets: 8 + (n % 14),
        temperatureControlled: false
      }
    }),

    // Part-truckload. Freight class and piece count, and nothing this platform enforces.
    BLR: n => ({
      customerId: 'SOUTHERN-FREIGHT',
      mode: 'LTL',
      body: {
        ewayBillNo: String(391000000000 + n),
        freightClass: '85',
        // No piece count: Southern Freight's schema sets additionalProperties false and describes
        // a piece as one physical item, so three crates are three entries rather than one with a
        // count of three. Dimensions are what an LTL carrier actually prices on alongside weight.
        pieces: [
          { description: 'Machined components, crated', weightKg: 210 + (n % 9) * 15, dimensionsCm: { length: 120, width: 80, height: 95 }, hazmat: false },
          { description: 'Spare assemblies', weightKg: 95, dimensionsCm: { length: 60, width: 40, height: 45 }, hazmat: false }
        ],
        billTo: { party: 'Peenya Industrial Traders', gstin: '29AABCU9603R1ZX' },
        accessorials: n % 3 === 0 ? ['LIFTGATE'] : []
      }
    }),

    // A parcel. Almost no paperwork, which is precisely why it is here.
    BOM: n => ({
      customerId: 'QUICKSHIP',
      mode: 'PARCEL',
      body: {
        // QS, ten digits, then IN. And EXPRESS is not one of QuickShip's four service levels --
        // a plausible-looking value that its own schema has never permitted.
        trackingNumber: 'QS' + String(7000000000 + n) + 'IN',
        serviceLevel: n % 4 === 0 ? 'NEXT_DAY' : 'STANDARD',
        weightKg: Math.round((0.4 + (n % 11) * 0.35) * 100) / 100,
        recipient: {
          name: 'Consignee ' + n,
          pincode: '41' + String(1000 + (n % 900)).substring(0, 4),
          // A ten-digit Indian mobile number, with no country code: the schema asks for the
          // subscriber number and a +91 prefix is neither ten digits nor starts with 6-9.
          phone: String(9820000000 + n * 7)
        },
        signatureRequired: n % 6 === 0,
        declaredValueInr: 1500 + (n % 20) * 450,
        codAmountInr: n % 5 === 0 ? 2400 : 0
      }
    })
  };

  const ops = [];
  for (let n = 1; n <= $FLEET_SIZE; n++) {
    const lane = lanes[(n - 1) % lanes.length];
    const shipmentId = 'SHP-' + lane + '-' + String(n).padStart(4, '0');
    const built = builders[lane](n);

    ops.push({
      replaceOne: {
        filter: { _id: shipmentId },
        replacement: {
          _id: shipmentId,
          shipmentId: shipmentId,
          customerId: built.customerId,
          mode: built.mode,
          schemaVersion: '$SCHEMA_VERSION',
          createdAt: now,
          body: built.body
        },
        upsert: true
      }
    });
  }

  const result = db['$COLLECTION'].bulkWrite(ops);
  print('  ok  ' + result.upsertedCount + ' inserted, ' + result.modifiedCount + ' updated');

  const byCustomer = db['$COLLECTION'].aggregate([
    { \$group: { _id: '\$customerId', n: { \$sum: 1 } } },
    { \$sort: { _id: 1 } }
  ]).toArray();
  byCustomer.forEach(r => print('  ok  ' + r._id + ': ' + r.n + ' manifests'));
" || die "Seeding failed. Is the platform up? Try ./scripts/platform-up.sh"

echo
ok "Manifests seeded. The cold-chain and late-arrival rules now have contracts to enforce."
log "Note that these are written directly, bypassing the shipment service's own validation."
log "To check one against its customer's schema the way a real submission would be:"
echo "    curl localhost:18082/manifests/SHP-HYD-0002"
