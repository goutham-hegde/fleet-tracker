package com.fleettracking.exceptions.manifest;

import java.time.Duration;
import java.util.Optional;

/**
 * The temperature range a load must stay inside, and how long it may sit outside before the
 * consignment is written off.
 *
 * <p>A range rather than a single threshold, because a cold chain can be broken in both directions:
 * a vaccine ruined by warmth is the obvious case, and a load frozen by a unit that overshoots is
 * the one people forget. The committed schemas carry both bounds for exactly this reason.
 *
 * <p>Distinct from the setpoint the reefer unit reports on every reading. The setpoint is what the
 * <em>equipment</em> was told to hold; this is what the <em>customer</em> agreed the freight would
 * experience, and the two are not the same promise. A unit set to 4°C against a 2–8°C band has
 * four degrees of headroom on either side, and an excursion is a breach of the band, not a
 * disagreement with the dial.
 *
 * @param minC the coldest the load may get
 * @param maxC the warmest
 * @param tolerance how long it may sit outside before it counts, when the customer stated one.
 *     Null means the customer committed to the range but not to a grace period, and the rule
 *     supplies its own default rather than treating a single stray reading as a breach
 */
public record TemperatureBand(double minC, double maxC, Duration tolerance) {

  /** Whether a measurement is inside the committed range. Both bounds are inclusive. */
  public boolean contains(double celsius) {
    return celsius >= minC && celsius <= maxC;
  }

  /**
   * The bound this measurement broke, or empty if it broke neither.
   *
   * <p>Returned rather than recomputed by the caller because it is what goes on the event as
   * {@code thresholdValue}: an exception that says "9.4°C" without saying "against a maximum of
   * 8.0°C" cannot be judged without going back to the manifest.
   */
  public Optional<Double> breachedBound(double celsius) {
    if (celsius > maxC) {
      return Optional.of(maxC);
    }
    if (celsius < minC) {
      return Optional.of(minC);
    }
    return Optional.empty();
  }

  /** How far outside the range a measurement is, in degrees. Zero when inside. */
  public double excess(double celsius) {
    return breachedBound(celsius).map(bound -> Math.abs(celsius - bound)).orElse(0.0);
  }

  /** The grace period the customer stated, if any. */
  public Optional<Duration> statedTolerance() {
    return Optional.ofNullable(tolerance);
  }

  @Override
  public String toString() {
    return minC + "-" + maxC + "C";
  }
}
