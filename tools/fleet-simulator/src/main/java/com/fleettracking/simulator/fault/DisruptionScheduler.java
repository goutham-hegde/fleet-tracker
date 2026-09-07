package com.fleettracking.simulator.fault;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

/**
 * Decides when something goes wrong with a truck.
 *
 * <h2>Per hour of simulated time, converted per tick</h2>
 *
 * <p>The settings state a chance per truck per simulated hour, and this turns that into a chance for
 * one tick. The conversion is the exponential one rather than a naive division, so a run with long
 * ticks and a run with short ones produce the same rate of disruption rather than the same number
 * per tick.
 *
 * <p>Why that matters here more than it usually would: this simulator's whole point is that
 * {@code time-scale} and {@code tick-interval} are independent knobs. Somebody running the demo
 * faster by shortening the tick must not thereby break down the entire fleet, and somebody
 * lengthening it must not accidentally produce a flawless run and conclude the rules do not work.
 *
 * <h2>Drawn from the run's seed</h2>
 *
 * <p>Same rule as every other source of randomness in this simulator, and it earns its place
 * immediately: "the reefer on truck three failed forty minutes in" is only a usable test case if the
 * next run does it again. A disruption that appeared at random would make an exception that fired
 * once and not the second time impossible to tell from a flaky rule.
 */
public class DisruptionScheduler {

  private static final double SECONDS_PER_HOUR = 3600.0;

  private final DisruptionProperties properties;
  private final RandomGenerator random;

  public DisruptionScheduler(DisruptionProperties properties, RandomGenerator random) {
    this.properties = properties;
    this.random = random;
  }

  /** A scheduler that never disrupts anything, for tests asserting on a clean run. */
  public static DisruptionScheduler none() {
    return new DisruptionScheduler(DisruptionProperties.none(), new java.util.Random(0));
  }

  /**
   * Rolls for a new disruption on one truck.
   *
   * @param refrigerated whether this truck has a refrigeration unit that could fail. A dry van is
   *     never offered a reefer failure — inventing one would produce a temperature excursion on a
   *     load that has no thermometer
   * @param now simulated time at the end of the tick
   * @param tickDelta how much simulated time the tick covered
   * @return a disruption to start, or null for the overwhelmingly common case of nothing happening
   */
  public Disruption rollFor(boolean refrigerated, Instant now, Duration tickDelta) {
    if (!properties.enabled()) {
      return null;
    }
    double tickHours = tickDelta.toNanos() / 1_000_000_000.0 / SECONDS_PER_HOUR;

    for (Disruption.Kind kind : Disruption.Kind.values()) {
      if (kind == Disruption.Kind.REEFER_FAILURE && !refrigerated) {
        continue;
      }
      double perHour = properties.probabilityOf(kind);
      if (perHour <= 0) {
        continue;
      }
      // The chance of at least one occurrence in this tick, given a rate per hour. Naively
      // multiplying the hourly probability by the fraction of an hour would exceed 1 for a coarse
      // tick and a high rate, and would understate a low one.
      double perTick = 1 - Math.exp(-perHour * tickHours);
      if (random.nextDouble() < perTick) {
        return new Disruption(kind, now.plus(properties.durationOf(kind)));
      }
    }
    return null;
  }

  /** What fraction of its cruise speed a slowed truck manages. */
  public double slowdownSpeedRatio() {
    return properties.slowdownSpeedRatio();
  }

  /** How far off its bearing a detouring truck turns. */
  public double detourBearingOffsetDegrees() {
    return properties.detourBearingOffsetDegrees();
  }

  /** Whether anything can go wrong at all. Logged at startup. */
  public boolean anyActive() {
    return properties.anyActive();
  }
}
