package com.fleettracking.shipment.schema;

import java.util.List;

/**
 * The answer to "may this body be stored", as a value rather than as an exception.
 *
 * <p>Sealed, and modelled on {@code NormalizationResult} in the ingest gateway for the same reason:
 * <b>rejection is routine, not exceptional.</b> A customer's order system rolling out a bad release
 * can send thousands of invalid manifests an hour while this service works perfectly. Throwing on
 * each one would fill logs with stack traces describing normal operation, and — more importantly —
 * would make it easy for a caller to forget that rejection is a case it has to handle. A sealed
 * result makes the compiler ask.
 */
public sealed interface ValidationResult {

  /** The body satisfies the customer's schema, which was at this version when it was checked. */
  record Valid(String schemaVersion) implements ValidationResult {}

  /**
   * The body broke the customer's schema.
   *
   * <p>{@code violations} is what the exit criterion means by "a useful error": each entry names
   * the field and what was wrong with it, so the customer can fix their payload without reading
   * this service's source. A bare boolean would satisfy the validation and fail the requirement.
   */
  record Invalid(String schemaVersion, List<Violation> violations) implements ValidationResult {}

  /**
   * No schema is on file for this customer and mode.
   *
   * <p>Deliberately distinct from {@link Invalid}, because the two are different people's problem.
   * An invalid manifest is the customer's to fix; a missing schema is this platform's — the
   * customer was onboarded without their contract being loaded. Collapsing them would report a
   * configuration gap as though the customer had sent bad data, and send whoever is on call to the
   * wrong system.
   */
  record NoSchema(String customerId, String mode) implements ValidationResult {}

  /**
   * One thing wrong with the body.
   *
   * @param field where it was, as a path into the document ({@code $.temperature.maxC})
   * @param message what was wrong, in the validator's own words
   * @param constraint which schema keyword rejected it ({@code required}, {@code maximum})
   */
  record Violation(String field, String message, String constraint) {}
}
