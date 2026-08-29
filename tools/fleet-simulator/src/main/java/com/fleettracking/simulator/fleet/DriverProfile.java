package com.fleettracking.simulator.fleet;

import java.time.Duration;

/**
 * How one truck drives: how fast it wants to go, how hard it can accelerate and brake, and how much
 * its speed wanders while cruising.
 *
 * <p>Separating this from {@link Truck} is what makes a heterogeneous fleet possible — a loaded
 * reefer on a mountain lane and an empty van on an interstate share the same state machine and
 * differ only in these numbers. It also makes the physics tests explicit: a test that wants to
 * check the braking curve can hand in a profile with absurd deceleration and watch what happens,
 * without touching the movement code.
 *
 * <p>The defaults describe a loaded class-8 tractor-trailer, which is what most of this platform's
 * freight moves on.
 *
 * @param cruiseSpeedKph the speed the driver settles at on open road
 * @param accelerationMps2 how quickly it gains speed, m/s². A loaded semi manages roughly 0.4 —
 *     about 70 seconds from a standstill to 100 km/h, and anyone who has been overtaken by one
 *     pulling out of a truck stop will recognise the number
 * @param decelerationMps2 comfortable service braking, m/s². Deliberately not emergency braking
 *     (which is 4-6): a truck approaching a scheduled stop brakes gently, and using the emergency
 *     figure would make trucks arrive with implausibly little warning
 * @param speedVariationKph one standard deviation of the wander around cruise speed — traffic,
 *     gradients, and a driver who is not a cruise control
 * @param speedVariationPeriod roughly how long one such wander lasts before it reverts toward
 *     cruise. Seconds would look like sensor noise; several minutes looks like traffic
 */
public record DriverProfile(
    double cruiseSpeedKph,
    double accelerationMps2,
    double decelerationMps2,
    double speedVariationKph,
    Duration speedVariationPeriod) {

  /** A loaded tractor-trailer running interstate lanes. */
  public static final DriverProfile LOADED_SEMI =
      new DriverProfile(100.0, 0.4, 0.8, 6.0, Duration.ofMinutes(3));

  public DriverProfile {
    if (cruiseSpeedKph <= 0) {
      throw new IllegalArgumentException("cruiseSpeedKph must be positive: " + cruiseSpeedKph);
    }
    if (accelerationMps2 <= 0) {
      throw new IllegalArgumentException("accelerationMps2 must be positive: " + accelerationMps2);
    }
    if (decelerationMps2 <= 0) {
      throw new IllegalArgumentException("decelerationMps2 must be positive: " + decelerationMps2);
    }
    if (speedVariationKph < 0) {
      throw new IllegalArgumentException(
          "speedVariationKph must not be negative: " + speedVariationKph);
    }
    if (speedVariationPeriod == null || speedVariationPeriod.isZero() || speedVariationPeriod.isNegative()) {
      throw new IllegalArgumentException(
          "speedVariationPeriod must be positive: " + speedVariationPeriod);
    }
  }

  /** Cruise speed in metres per second, which is the unit all the physics is done in. */
  public double cruiseSpeedMps() {
    return cruiseSpeedKph / 3.6;
  }

  /** Speed variation in metres per second. */
  public double speedVariationMps() {
    return speedVariationKph / 3.6;
  }
}
