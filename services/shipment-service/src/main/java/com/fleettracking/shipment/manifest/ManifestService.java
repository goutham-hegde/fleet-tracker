package com.fleettracking.shipment.manifest;

import com.fleettracking.shipment.schema.ManifestValidator;
import com.fleettracking.shipment.schema.ValidationResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Accepts a manifest, or explains why it was not accepted.
 *
 * <p>The order of operations is validate-then-store, and it is not negotiable: an invalid body must
 * never reach the collection, because a document stored is a document some later consumer will
 * trust. This is the mirror image of the tracking processor's publish-then-record ordering, where
 * repeating an event is cheap and losing one is not. Here the asymmetry runs the other way — a
 * rejected manifest costs the customer a retry, and an accepted-but-wrong one costs a wrong
 * shipment.
 *
 * <h2>Validation lives here, not in the controller</h2>
 *
 * <p>Same reasoning as {@code IngestService} in the gateway: one central place, so that every route
 * into this service is held to the same rules. A second entry point added later — a Kafka consumer
 * for a customer that prefers to publish, a bulk import — gets the validation by construction
 * rather than by its author remembering.
 */
public class ManifestService {

  private static final Logger log = LoggerFactory.getLogger(ManifestService.class);

  private final ManifestStore store;
  private final ManifestValidator validator;
  private final Clock clock;

  public ManifestService(ManifestStore store, ManifestValidator validator, Clock clock) {
    this.store = store;
    this.validator = validator;
    this.clock = clock;
  }

  /**
   * Validates a submitted manifest against its customer's schema and stores it if it passes.
   *
   * @return what happened, as a value — this never throws for bad input
   */
  public SubmissionResult accept(
      String shipmentId, String customerId, FreightMode mode, Map<String, Object> body) {

    ValidationResult validation = validator.validate(customerId, mode, body);

    return switch (validation) {
      case ValidationResult.NoSchema noSchema -> {
        // Logged at ERROR because this is the platform's own gap, not the customer's. Nobody is
        // going to notice a customer's manifests silently failing unless this is loud.
        log.error(
            "No manifest schema on file for customer {} in mode {}; manifest for shipment {}"
                + " cannot be validated and was not stored",
            customerId,
            mode,
            shipmentId);
        yield new SubmissionResult.SchemaMissing(customerId, mode);
      }

      case ValidationResult.Invalid invalid -> {
        // INFO, not WARN or ERROR: a customer sending an invalid manifest is this endpoint working
        // as designed. Logging it as a fault would train whoever reads these to ignore them.
        log.info(
            "Rejected manifest for shipment {} from customer {}: {} violation(s) of schema {}",
            shipmentId,
            customerId,
            invalid.violations().size(),
            invalid.schemaVersion());
        yield new SubmissionResult.Rejected(invalid.schemaVersion(), invalid.violations());
      }

      case ValidationResult.Valid valid -> {
        Instant now = clock.instant();
        Manifest manifest =
            new Manifest(shipmentId, customerId, mode, valid.schemaVersion(), now, body);
        store.save(manifest);
        log.info(
            "Stored {} manifest for shipment {} from customer {} against schema {}",
            mode,
            shipmentId,
            customerId,
            valid.schemaVersion());
        yield new SubmissionResult.Accepted(manifest);
      }
    };
  }

  /** What became of a submitted manifest. */
  public sealed interface SubmissionResult {

    /** Validated and stored. */
    record Accepted(Manifest manifest) implements SubmissionResult {}

    /** The customer's own schema refused it; the violations say where. */
    record Rejected(String schemaVersion, List<ValidationResult.Violation> violations)
        implements SubmissionResult {}

    /** This platform has no contract on file to check it against. Not the customer's fault. */
    record SchemaMissing(String customerId, FreightMode mode) implements SubmissionResult {}
  }
}
