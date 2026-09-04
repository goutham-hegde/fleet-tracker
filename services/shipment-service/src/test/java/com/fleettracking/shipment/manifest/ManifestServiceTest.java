package com.fleettracking.shipment.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.shipment.Manifests;
import com.fleettracking.shipment.schema.ManifestSchema;
import com.fleettracking.shipment.schema.ManifestValidator;
import com.fleettracking.shipment.schema.SchemaStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The accept path: what is stored, what is refused, and — most importantly — what is <em>not</em>
 * stored when it is refused.
 */
class ManifestServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-04T09:15:00Z");

  /** Records what was saved without needing a database. */
  private static class RecordingStore extends ManifestStore {
    private final List<Manifest> saved = new ArrayList<>();

    RecordingStore() {
      super(null);
    }

    @Override
    public void save(Manifest manifest) {
      saved.add(manifest);
    }
  }

  private static class InMemorySchemaStore extends SchemaStore {
    private final Map<String, ManifestSchema> byId = new HashMap<>();

    InMemorySchemaStore() {
      super(null);
      for (ManifestSchema schema : Manifests.allSchemas()) {
        byId.put(schema.id(), schema);
      }
    }

    @Override
    public Optional<ManifestSchema> find(String customerId, FreightMode mode) {
      return Optional.ofNullable(byId.get(ManifestSchema.idFor(customerId, mode)));
    }
  }

  private final RecordingStore store = new RecordingStore();
  private final ManifestService service =
      new ManifestService(
          store,
          new ManifestValidator(new InMemorySchemaStore()),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void storesAValidManifestWithTheSchemaVersionItWasAcceptedAgainst() {
    var result =
        service.accept(
            "SHP-HYD-0002",
            Manifests.PHARMA_CUSTOMER,
            FreightMode.PHARMA_COLD_CHAIN,
            Manifests.pharmaBody());

    assertThat(result).isInstanceOf(ManifestService.SubmissionResult.Accepted.class);

    Manifest stored = store.saved.getFirst();
    assertThat(stored.shipmentId()).isEqualTo("SHP-HYD-0002");
    assertThat(stored.mode()).isEqualTo(FreightMode.PHARMA_COLD_CHAIN);
    assertThat(stored.schemaVersion()).isEqualTo(Manifests.VERSION);
    assertThat(stored.body()).containsKey("drugLicenceNo");
  }

  @Test
  void stampsCreatedAtFromTheClockRatherThanFromTheSubmission() {
    // The customer's own timestamp, if they sent one, is theirs and lives in the body. This field
    // is when the platform accepted it, which is the only thing this service can actually attest.
    service.accept(
        "SHP-BLR-0003", Manifests.LTL_CUSTOMER, FreightMode.LTL, Manifests.ltlBody());

    assertThat(store.saved.getFirst().createdAt()).isEqualTo(NOW);
  }

  /** The ordering guarantee this service exists to hold: validate, and only then store. */
  @Test
  void storesNothingWhenTheBodyIsInvalid() {
    Map<String, Object> broken = Manifests.parcelBody();
    broken.put("weightKg", 500.0);

    var result =
        service.accept("SHP-DEL-0001", Manifests.PARCEL_CUSTOMER, FreightMode.PARCEL, broken);

    assertThat(result).isInstanceOf(ManifestService.SubmissionResult.Rejected.class);
    assertThat(store.saved).isEmpty();
  }

  @Test
  void storesNothingWhenNoSchemaIsOnFile() {
    // A manifest this platform cannot check is not a manifest it may keep. Storing it "for now"
    // would put an unvalidated document in the collection that every later reader trusts.
    var result =
        service.accept(
            "SHP-BOM-0004", "NEVER-ONBOARDED", FreightMode.PARCEL, Manifests.parcelBody());

    assertThat(result).isInstanceOf(ManifestService.SubmissionResult.SchemaMissing.class);
    assertThat(store.saved).isEmpty();
  }

  @Test
  void handsBackTheViolationsSoTheCallerCanFixTheDocument() {
    Map<String, Object> broken = Manifests.retailBody();
    broken.remove("deliveryWindow");

    var rejected =
        (ManifestService.SubmissionResult.Rejected)
            service.accept(
                "SHP-DEL-0005",
                Manifests.RETAIL_CUSTOMER,
                FreightMode.RETAIL_REPLENISHMENT,
                broken);

    assertThat(rejected.violations()).isNotEmpty();
    assertThat(rejected.schemaVersion()).isEqualTo(Manifests.VERSION);
  }

  @Test
  void acceptsAllFourShapesThroughTheSameEntryPoint() {
    // One service, one collection, four shapes that share no fields.
    service.accept("SHP-HYD-0002", Manifests.PHARMA_CUSTOMER, FreightMode.PHARMA_COLD_CHAIN, Manifests.pharmaBody());
    service.accept("SHP-DEL-0001", Manifests.RETAIL_CUSTOMER, FreightMode.RETAIL_REPLENISHMENT, Manifests.retailBody());
    service.accept("SHP-BLR-0003", Manifests.LTL_CUSTOMER, FreightMode.LTL, Manifests.ltlBody());
    service.accept("SHP-BOM-0004", Manifests.PARCEL_CUSTOMER, FreightMode.PARCEL, Manifests.parcelBody());

    assertThat(store.saved).hasSize(4);
    assertThat(store.saved).extracting(Manifest::mode).containsExactlyInAnyOrder(FreightMode.values());
  }

  @Test
  void aStoredBodyCannotBeChangedThroughTheMapThatWasSubmitted() {
    // The submitted map is still held by the caller. A manifest that changes after validation is
    // a manifest that was never really validated, so the record copies at construction.
    Map<String, Object> submitted = Manifests.parcelBody();
    service.accept("SHP-BOM-0008", Manifests.PARCEL_CUSTOMER, FreightMode.PARCEL, submitted);

    submitted.put("weightKg", 999.0);

    assertThat(store.saved.getFirst().body()).containsEntry("weightKg", 1.85);
  }
}
