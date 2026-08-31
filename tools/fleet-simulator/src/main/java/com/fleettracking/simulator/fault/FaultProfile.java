package com.fleettracking.simulator.fault;

import com.fleettracking.events.GeoPoint;
import com.fleettracking.simulator.route.Geo;
import java.util.random.RandomGenerator;

/**
 * Rolls the dice for one consumer of faults.
 *
 * <p>Each emitter and the sink get their <b>own</b> instance with its own generator, for the reason
 * that governs seeding everywhere in this simulator: shared randomness couples things that should
 * be independent. Two receivers on one truck do not share a GPS error, and switching the mobile app
 * off should not change a single byte of what telematics emits.
 *
 * <p>Faults are drawn from the run's seed, so a fault is <em>reproducible</em>. That matters more
 * than it sounds: a bug that only appears when a duplicate lands next to an out-of-order fix is
 * close to undebuggable if the next run produces a different set of faults. Same seed, same
 * corruption, same failure.
 */
public class FaultProfile {

  private final FaultProperties properties;
  private final RandomGenerator random;

  public FaultProfile(FaultProperties properties, RandomGenerator random) {
    this.properties = properties;
    this.random = random;
  }

  /** A profile that never injects anything, for tests asserting on clean output. */
  public static FaultProfile none() {
    return new FaultProfile(
        new FaultProperties(false, 0, 0, 1500, 0, 0, 0), new java.util.Random(0));
  }

  /**
   * Degrades a true position into one a receiver would actually report.
   *
   * <p>Ordinary jitter is a Gaussian offset in a uniformly random direction, which is a reasonable
   * approximation of a stationary receiver's error and is what makes a parked truck's reported
   * position wander within a few metres rather than sitting perfectly still. A bad fix instead
   * lands the truck hundreds of metres away in one step — the thing that separates a geofence which
   * checks reported accuracy from one which does not.
   */
  public GeoPoint perturb(GeoPoint truth) {
    if (!properties.enabled()) {
      return truth;
    }
    if (properties.badFixProbability() > 0 && random.nextDouble() < properties.badFixProbability()) {
      double bearing = random.nextDouble() * 360.0;
      double distance = properties.badFixRadiusMeters() * (0.5 + random.nextDouble());
      return Geo.destination(truth, bearing, distance);
    }
    if (properties.gpsNoiseMeters() <= 0) {
      return truth;
    }
    double bearing = random.nextDouble() * 360.0;
    double distance = Math.abs(random.nextGaussian()) * properties.gpsNoiseMeters();
    return Geo.destination(truth, bearing, distance);
  }

  /**
   * The accuracy figure a receiver would report for a fix it has just perturbed.
   *
   * <p>Reported honestly, because a device that lies about its own accuracy is a different and much
   * rarer problem than one that is simply imprecise. A consumer is entitled to trust this number,
   * which is exactly why a wide one must suppress a geofence trigger rather than being ignored.
   */
  public double reportedAccuracyMeters(double baseline) {
    if (!properties.enabled()) {
      return baseline;
    }
    return baseline + properties.gpsNoiseMeters();
  }

  /** Whether this message is lost in transit. */
  public boolean drops() {
    return properties.enabled()
        && properties.dropProbability() > 0
        && random.nextDouble() < properties.dropProbability();
  }

  /** Whether this message is delivered a second time. */
  public boolean duplicates() {
    return properties.enabled()
        && properties.duplicateProbability() > 0
        && random.nextDouble() < properties.duplicateProbability();
  }

  /** Whether this message arrives corrupted. */
  public boolean malforms() {
    return properties.enabled()
        && properties.malformedProbability() > 0
        && random.nextDouble() < properties.malformedProbability();
  }

  /**
   * Corrupts a payload the way a real transport does.
   *
   * <p>Deliberately not "replace the body with garbage". The corruptions that actually reach a
   * gateway are partial: a truncated write, a mangled number, a lost delimiter. Each of these still
   * looks broadly like the format it claims to be, which is what makes them a real test of a
   * normalizer's error handling rather than of its ability to reject obvious rubbish.
   */
  public String malform(String body) {
    if (body.isEmpty()) {
      return body;
    }
    return switch (random.nextInt(4)) {
      // Truncated in transit: valid up to the cut, then nothing.
      case 0 -> body.substring(0, Math.max(1, (int) (body.length() * (0.3 + random.nextDouble() * 0.5))));
      // A number that is no longer a number.
      case 1 -> body.replaceFirst("[0-9]+\\.[0-9]+", "NaN");
      // A lost delimiter: JSON loses a quote, EDI loses a segment terminator.
      case 2 -> body.contains("~") ? body.replaceFirst("~", "") : body.replaceFirst("\"", "");
      // Something upstream wrapped it in an error page.
      default -> "<html><body>502 Bad Gateway</body></html>";
    };
  }

  /** The configuration behind this profile. */
  public FaultProperties properties() {
    return properties;
  }
}
