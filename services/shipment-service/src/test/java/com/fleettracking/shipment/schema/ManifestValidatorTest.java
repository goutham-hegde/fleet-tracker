package com.fleettracking.shipment.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.shipment.Manifests;
import com.fleettracking.shipment.manifest.FreightMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The validator, against the four schemas actually committed to {@code docs/schemas/manifests}.
 *
 * <p>Every test here goes through a real JSON Schema document rather than a hand-written stub, so a
 * change to a customer contract that breaks a rule this platform depends on fails the build.
 */
class ManifestValidatorTest {

  /**
   * A schema store backed by a map rather than by MongoDB.
   *
   * <p>Its counterpart is {@code InMemoryIdentityResolver} in the gateway's test sources, and it
   * exists for the same reason: these tests are about the validation rules, and starting a database
   * to read four documents back would make them slower without making them stricter.
   */
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

  private final ManifestValidator validator = new ManifestValidator(new InMemorySchemaStore());

  // --- the four shapes all pass their own schema -------------------------------------------------

  @Test
  void acceptsAValidBodyForEveryFreightMode() {
    assertThat(
            validator.validate(
                Manifests.PHARMA_CUSTOMER, FreightMode.PHARMA_COLD_CHAIN, Manifests.pharmaBody()))
        .isInstanceOf(ValidationResult.Valid.class);
    assertThat(
            validator.validate(
                Manifests.RETAIL_CUSTOMER,
                FreightMode.RETAIL_REPLENISHMENT,
                Manifests.retailBody()))
        .isInstanceOf(ValidationResult.Valid.class);
    assertThat(validator.validate(Manifests.LTL_CUSTOMER, FreightMode.LTL, Manifests.ltlBody()))
        .isInstanceOf(ValidationResult.Valid.class);
    assertThat(
            validator.validate(
                Manifests.PARCEL_CUSTOMER, FreightMode.PARCEL, Manifests.parcelBody()))
        .isInstanceOf(ValidationResult.Valid.class);
  }

  @Test
  void reportsTheSchemaVersionThatAcceptedTheBody() {
    // Recorded on the stored manifest, so a document that would fail today's schema can be
    // recognised as one accepted under an older contract rather than as a corruption.
    var result =
        (ValidationResult.Valid)
            validator.validate(
                Manifests.PHARMA_CUSTOMER, FreightMode.PHARMA_COLD_CHAIN, Manifests.pharmaBody());

    assertThat(result.schemaVersion()).isEqualTo(Manifests.VERSION);
  }

  /**
   * The property that makes one collection defensible: each body satisfies its own contract and
   * nothing else's.
   */
  @Test
  void aBodyValidForOneModeIsRejectedByEveryOther() {
    assertThat(
            validator.validate(
                Manifests.RETAIL_CUSTOMER, FreightMode.RETAIL_REPLENISHMENT, Manifests.pharmaBody()))
        .isInstanceOf(ValidationResult.Invalid.class);
    assertThat(
            validator.validate(Manifests.LTL_CUSTOMER, FreightMode.LTL, Manifests.parcelBody()))
        .isInstanceOf(ValidationResult.Invalid.class);
    assertThat(
            validator.validate(
                Manifests.PARCEL_CUSTOMER, FreightMode.PARCEL, Manifests.ltlBody()))
        .isInstanceOf(ValidationResult.Invalid.class);
  }

  // --- rejection carries something a customer can act on -----------------------------------------

  @Test
  void namesTheMissingFieldWhenARequiredOneIsAbsent() {
    Map<String, Object> body = Manifests.pharmaBody();
    body.remove("temperature");

    var result =
        (ValidationResult.Invalid)
            validator.validate(Manifests.PHARMA_CUSTOMER, FreightMode.PHARMA_COLD_CHAIN, body);

    assertThat(result.violations()).isNotEmpty();
    assertThat(result.violations())
        .anySatisfy(
            violation -> {
              assertThat(violation.constraint()).isEqualTo("required");
              assertThat(violation.message()).contains("temperature");
            });
  }

  @Test
  void pointsAtTheOffendingFieldRatherThanAtTheDocument() {
    // The instance location is the single most useful part of the response: it sends the customer
    // to the field rather than to the schema.
    Map<String, Object> body = Manifests.pharmaBody();
    @SuppressWarnings("unchecked")
    Map<String, Object> temperature = (Map<String, Object>) body.get("temperature");
    temperature.put("maxC", 400.0);

    var result =
        (ValidationResult.Invalid)
            validator.validate(Manifests.PHARMA_CUSTOMER, FreightMode.PHARMA_COLD_CHAIN, body);

    assertThat(result.violations())
        .anySatisfy(violation -> assertThat(violation.field()).contains("temperature").contains("maxC"));
  }

  @Test
  void rejectsAFieldTheCustomerNeverAgreedToSend() {
    // additionalProperties is false on every one of these schemas. Without it a typo in a field
    // name is stored silently and read by nobody -- the junk-drawer failure that "schemaless"
    // is usually accused of, prevented by the contract rather than by the database.
    Map<String, Object> body = Manifests.parcelBody();
    body.put("servicelevel", "NEXT_DAY");

    var result =
        (ValidationResult.Invalid)
            validator.validate(Manifests.PARCEL_CUSTOMER, FreightMode.PARCEL, body);

    assertThat(result.violations())
        .anySatisfy(violation -> assertThat(violation.constraint()).isEqualTo("additionalProperties"));
  }

  @Test
  void enforcesAPatternRatherThanMerelyAType() {
    // A GSTIN is a string, but not every string is a GSTIN. A type-only check would accept this.
    Map<String, Object> body = Manifests.ltlBody();
    @SuppressWarnings("unchecked")
    Map<String, Object> billTo = (Map<String, Object>) body.get("billTo");
    billTo.put("gstin", "NOT-A-GSTIN");

    var result =
        (ValidationResult.Invalid) validator.validate(Manifests.LTL_CUSTOMER, FreightMode.LTL, body);

    assertThat(result.violations())
        .anySatisfy(violation -> assertThat(violation.constraint()).isEqualTo("pattern"));
  }

  @Test
  void enforcesAnEnumOnFreightClass() {
    Map<String, Object> body = Manifests.ltlBody();
    body.put("freightClass", "42");

    var result =
        (ValidationResult.Invalid) validator.validate(Manifests.LTL_CUSTOMER, FreightMode.LTL, body);

    assertThat(result.violations())
        .anySatisfy(violation -> assertThat(violation.constraint()).isEqualTo("enum"));
  }

  @Test
  void reportsEveryViolationAtOnceRatherThanTheFirst() {
    // A customer fixing one field at a time, one round trip each, is a bad interface. Reporting
    // the whole set lets their next submission be correct.
    Map<String, Object> body = Manifests.parcelBody();
    body.put("trackingNumber", "nope");
    body.put("serviceLevel", "TELEPORT");
    body.put("weightKg", 999.0);

    var result =
        (ValidationResult.Invalid)
            validator.validate(Manifests.PARCEL_CUSTOMER, FreightMode.PARCEL, body);

    assertThat(result.violations()).hasSizeGreaterThanOrEqualTo(3);
  }

  // --- a missing contract is not the customer's fault --------------------------------------------

  @Test
  void distinguishesAnAbsentSchemaFromAnInvalidBody() {
    // These are different people's problem: an invalid body is the customer's to fix, a missing
    // schema is this platform's. Collapsing them sends whoever is on call to the wrong system.
    var result = validator.validate("NEVER-ONBOARDED", FreightMode.PARCEL, Manifests.parcelBody());

    assertThat(result).isInstanceOf(ValidationResult.NoSchema.class);
  }

  @Test
  void doesNotFallBackToAnotherModesSchemaForTheSameCustomer() {
    // MEDIVAULT has a cold-chain contract and no parcel contract. Answering with the one it does
    // have would validate a parcel against a pharma schema and reject it as though the customer
    // had sent nonsense.
    var result =
        validator.validate(Manifests.PHARMA_CUSTOMER, FreightMode.PARCEL, Manifests.parcelBody());

    assertThat(result).isInstanceOf(ValidationResult.NoSchema.class);
  }

  @Test
  void acceptsABodyThatOmitsOptionalFields() {
    // Only the required set is required. A customer sending the minimum must not be rejected for
    // declining fields their contract marks optional.
    Map<String, Object> minimal =
        Manifests.map(
            "trackingNumber", "QS0000000001IN",
            "serviceLevel", "STANDARD",
            "weightKg", 0.5,
            "recipient", Manifests.map("name", "S. Rao", "pincode", "560001"));

    assertThat(validator.validate(Manifests.PARCEL_CUSTOMER, FreightMode.PARCEL, minimal))
        .isInstanceOf(ValidationResult.Valid.class);
  }

  @Test
  void rejectsAnEmptyBody() {
    assertThat(validator.validate(Manifests.LTL_CUSTOMER, FreightMode.LTL, Map.of()))
        .isInstanceOf(ValidationResult.Invalid.class);
  }

  @Test
  void enforcesConstraintsInsideAnArray() {
    // The pieces array is where LTL keeps its real content, and a per-item constraint is easy to
    // write and easy to get wrong. A negative weight must not pass.
    Map<String, Object> body = Manifests.ltlBody();
    body.put(
        "pieces", List.of(Manifests.map("description", "Broken piece", "weightKg", -5.0)));

    var result =
        (ValidationResult.Invalid) validator.validate(Manifests.LTL_CUSTOMER, FreightMode.LTL, body);

    assertThat(result.violations()).isNotEmpty();
  }
}
