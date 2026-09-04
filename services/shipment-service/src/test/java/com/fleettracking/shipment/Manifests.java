package com.fleettracking.shipment;

import com.fleettracking.events.EventJson;
import com.fleettracking.shipment.manifest.FreightMode;
import com.fleettracking.shipment.schema.ManifestSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The four customer contracts and one valid manifest body for each.
 *
 * <p>The schemas are read from {@code docs/schemas/manifests}, the same committed files
 * {@code seed-manifest-schemas.sh} loads — not from copies written beside the tests. A validator
 * tested against a schema its own author retyped proves the validator agrees with that copy, which
 * says nothing about the contract the platform actually enforces. This is the same reasoning that
 * puts the normalizer fixtures in {@code docs/samples} rather than in test sources.
 */
public final class Manifests {

  private Manifests() {}

  public static final String PHARMA_CUSTOMER = "MEDIVAULT";
  public static final String RETAIL_CUSTOMER = "VISTAMART";
  public static final String LTL_CUSTOMER = "SOUTHERN-FREIGHT";
  public static final String PARCEL_CUSTOMER = "QUICKSHIP";

  /** Matches the version the seed script stamps; the tests assert it reaches the manifest. */
  public static final String VERSION = "2026-09-04";

  /**
   * Finds {@code docs/schemas/manifests} by walking up from the working directory.
   *
   * <p>Maven sets the working directory to the module and an IDE often uses the repository root; a
   * relative path that assumes either one passes under that one and fails under the other.
   */
  public static Path schemaDir() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve("docs").resolve("schemas").resolve("manifests");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(
        "docs/schemas/manifests not found above " + Path.of("").toAbsolutePath());
  }

  /** One committed schema, parsed. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> schemaJson(String fileName) {
    try {
      String text = Files.readString(schemaDir().resolve(fileName), StandardCharsets.UTF_8);
      return EventJson.mapper().readValue(text, Map.class);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The four schema documents exactly as the seed script would write them. */
  public static List<ManifestSchema> allSchemas() {
    return List.of(
        schema(PHARMA_CUSTOMER, FreightMode.PHARMA_COLD_CHAIN, "medivault.pharma-cold-chain.json"),
        schema(RETAIL_CUSTOMER, FreightMode.RETAIL_REPLENISHMENT, "vistamart.retail-replenishment.json"),
        schema(LTL_CUSTOMER, FreightMode.LTL, "southern-freight.ltl.json"),
        schema(PARCEL_CUSTOMER, FreightMode.PARCEL, "quickship.parcel.json"));
  }

  public static ManifestSchema schema(String customerId, FreightMode mode, String fileName) {
    return new ManifestSchema(
        ManifestSchema.idFor(customerId, mode),
        customerId,
        mode,
        VERSION,
        schemaJson(fileName),
        Instant.parse("2026-09-04T00:00:00Z"));
  }

  // --- one valid body per mode ------------------------------------------------------------------
  //
  // Written out in full rather than generated, because the point of these is that they share almost
  // no fields. A helper that built all four from a common base would hide exactly the property the
  // tests exist to demonstrate.

  public static Map<String, Object> pharmaBody() {
    return map(
        "drugLicenceNo", "TG/28/2019",
        "temperature", map("minC", 2.0, "maxC", 8.0, "excursionToleranceMinutes", 30),
        "consignment",
            map(
                "batchNo", "MV-2026-8841",
                "expiryDate", "2027-04-30",
                "units", 1200,
                "controlledSubstance", false,
                "productName", "Recombinant insulin, 10ml vials"),
        "custody",
            List.of(
                map(
                    "party", "MediVault Genome Valley",
                    "handedOverAt", "2026-09-03T18:40:00Z",
                    "signature", "R. Iyer")));
  }

  public static Map<String, Object> retailBody() {
    return map(
        "dcCode", "DC-BOM-04",
        "asnNumber", "ASN-2026-114872",
        "purchaseOrders",
            List.of(
                map("poNumber", "PO40028841", "lineCount", 37, "valueInr", 486200.0, "department", "Grocery"),
                map("poNumber", "PO40028842", "lineCount", 12, "valueInr", 118400.0, "department", "Household")),
        "deliveryWindow",
            map(
                "opensAt", "2026-09-05T04:00:00Z",
                "closesAt", "2026-09-05T07:30:00Z",
                "dockNumber", 14),
        "pallets", 26,
        "temperatureControlled", false);
  }

  public static Map<String, Object> ltlBody() {
    return map(
        "ewayBillNo", "381004827155",
        "freightClass", "85",
        "pieces",
            List.of(
                map(
                    "description", "Injection moulded fittings, palletised",
                    "weightKg", 412.5,
                    "dimensionsCm", map("length", 120.0, "width", 100.0, "height", 145.0),
                    "hazmat", false),
                map("description", "Spare motor housings", "weightKg", 88.0)),
        "billTo", map("party", "Chennai Industrial Supplies", "gstin", "33AABCU9603R1ZM"),
        "accessorials", List.of("APPOINTMENT", "LIFTGATE"));
  }

  public static Map<String, Object> parcelBody() {
    return map(
        "trackingNumber", "QS4471028893IN",
        "serviceLevel", "NEXT_DAY",
        "weightKg", 1.85,
        "recipient", map("name", "A. Krishnan", "pincode", "600028", "phone", "9840112233"),
        "signatureRequired", true,
        "declaredValueInr", 4200.0,
        "codAmountInr", 0.0);
  }

  /** An ordered map, so a manifest read back compares cleanly against the one that was sent. */
  public static Map<String, Object> map(Object... keysAndValues) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      result.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return result;
  }
}
