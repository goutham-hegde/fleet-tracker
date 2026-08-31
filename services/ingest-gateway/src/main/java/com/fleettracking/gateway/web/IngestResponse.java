package com.fleettracking.gateway.web;

import com.fleettracking.gateway.IngestOutcome;

/**
 * What the gateway tells a producer it did with their message.
 *
 * <p>Deliberately more informative than a bare status code. A telematics vendor whose firmware
 * started sending a field in the wrong unit gets {@code DEAD_LETTERED} and {@code INVALID_VALUE}
 * with the failing property named, rather than a {@code 400} that tells them only that something,
 * somewhere, was unacceptable.
 *
 * @param outcome what happened
 * @param published how many canonical events were produced
 * @param deadLettered how many parts of the message were rejected
 * @param reason the rejection category, when there was one
 * @param detail what specifically was wrong, when there was one
 */
public record IngestResponse(
    Outcome outcome, int published, int deadLettered, String reason, String detail) {

  public enum Outcome {
    /** Everything in the message became a canonical event. */
    ACCEPTED,
    /** Some of it did and some of it did not — only possible for a batch feed. */
    PARTIAL,
    /** None of it did; the original is durably on the dead-letter topic. */
    DEAD_LETTERED
  }

  public static IngestResponse of(IngestOutcome outcome) {
    Outcome verdict;
    if (outcome.deadLettered() == 0) {
      verdict = Outcome.ACCEPTED;
    } else if (outcome.published() == 0) {
      verdict = Outcome.DEAD_LETTERED;
    } else {
      verdict = Outcome.PARTIAL;
    }
    return new IngestResponse(
        verdict,
        outcome.published(),
        outcome.deadLettered(),
        outcome.reason() == null ? null : outcome.reason().name(),
        outcome.detail());
  }
}
