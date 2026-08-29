package com.fleettracking.simulator.route;

import com.fleettracking.events.GeoPoint;

/**
 * Great-circle geometry on a spherical earth.
 *
 * <p>Three operations, and the simulator needs exactly these three: how far apart two points are,
 * which way to face to get from one to the other, and where you end up having driven a given
 * distance on a given heading. Everything the trucks do is those three in a loop.
 *
 * <p><b>Why a sphere and not an ellipsoid.</b> The earth is flattened at the poles, and the exact
 * answer (Vincenty's formulae, or Karney's) accounts for that at the cost of an iterative solver
 * that occasionally fails to converge on near-antipodal points. Haversine on a mean-radius sphere
 * is off by up to ~0.5% — about 5 m in a 1 km leg. GPS itself is routinely worse than that, and
 * this code exists to feed a geofencing algorithm whose thresholds are in the tens of metres, so
 * the ellipsoid buys precision that is immediately thrown away. If a later milestone needs survey
 * accuracy, this is the one class to replace.
 *
 * <p>All angles crossing this class's boundary are in <b>degrees</b>, because that is what
 * {@link GeoPoint} and every wire format use; radians exist only inside the method bodies.
 */
public final class Geo {

  /**
   * IUGG mean earth radius, metres. Not the equatorial radius (6 378 137 m) — using that would
   * bias every distance in this project long by about 0.3%.
   */
  public static final double EARTH_RADIUS_METERS = 6_371_008.8;

  private Geo() {}

  /**
   * Great-circle distance between two points, in metres.
   *
   * <p>Uses the haversine form rather than the spherical law of cosines. Both are algebraically
   * equivalent; the law of cosines loses catastrophic precision for short distances, because it
   * feeds a value very close to 1 into {@code acos}, where the derivative is unbounded. Two points
   * a metre apart can come back as zero. Haversine is well conditioned at exactly the scale a
   * vehicle simulator spends all its time at.
   */
  public static double distanceMeters(GeoPoint from, GeoPoint to) {
    double lat1 = Math.toRadians(from.latitude());
    double lat2 = Math.toRadians(to.latitude());
    double deltaLat = lat2 - lat1;
    double deltaLon = Math.toRadians(to.longitude() - from.longitude());

    double sinHalfLat = Math.sin(deltaLat / 2);
    double sinHalfLon = Math.sin(deltaLon / 2);
    double a = sinHalfLat * sinHalfLat + Math.cos(lat1) * Math.cos(lat2) * sinHalfLon * sinHalfLon;

    return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(a)));
  }

  /**
   * Initial bearing from one point to another, in degrees clockwise from true north, normalized to
   * {@code [0, 360)}.
   *
   * <p><b>"Initial" is load-bearing.</b> A great-circle path does not hold a constant compass
   * heading — the shortest route from Chicago to Los Angeles starts out pointing noticeably south
   * of west and ends pointing north of it. So this is the heading right now, at {@code from}, and
   * a truck following a long leg has to recompute it as it goes rather than setting it once. At
   * the sub-kilometre steps this simulator ticks in, the drift per step is negligible; over a
   * 3 000 km leg it is tens of degrees, which is the difference between a plausible heading field
   * and an obviously fake one.
   */
  public static double initialBearingDegrees(GeoPoint from, GeoPoint to) {
    double lat1 = Math.toRadians(from.latitude());
    double lat2 = Math.toRadians(to.latitude());
    double deltaLon = Math.toRadians(to.longitude() - from.longitude());

    double y = Math.sin(deltaLon) * Math.cos(lat2);
    double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);

    return normalizeBearing(Math.toDegrees(Math.atan2(y, x)));
  }

  /**
   * The point reached by travelling {@code distanceMeters} from {@code from} along the great circle
   * leaving on {@code bearingDegrees} — the "direct" geodesic problem, and the one that actually
   * moves the trucks.
   *
   * <p>A negative distance travels backwards along the same great circle, which is well defined and
   * occasionally useful; the simulator does not rely on it.
   */
  public static GeoPoint destination(GeoPoint from, double bearingDegrees, double distanceMeters) {
    double angularDistance = distanceMeters / EARTH_RADIUS_METERS;
    double bearing = Math.toRadians(bearingDegrees);
    double lat1 = Math.toRadians(from.latitude());
    double lon1 = Math.toRadians(from.longitude());

    double sinLat2 =
        Math.sin(lat1) * Math.cos(angularDistance)
            + Math.cos(lat1) * Math.sin(angularDistance) * Math.cos(bearing);
    double lat2 = Math.asin(Math.max(-1.0, Math.min(1.0, sinLat2)));

    double lon2 =
        lon1
            + Math.atan2(
                Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(lat1),
                Math.cos(angularDistance) - Math.sin(lat1) * sinLat2);

    return new GeoPoint(Math.toDegrees(lat2), normalizeLongitude(Math.toDegrees(lon2)));
  }

  /**
   * Wraps a bearing into {@code [0, 360)}.
   *
   * <p>360 is deliberately excluded rather than clamped, because {@code PositionEvent} declares
   * heading as {@code [0, 360)} — north is 0, and a validator would reject 360 for the same
   * direction. Java's {@code %} keeps the sign of the dividend, so {@code -90 % 360} is
   * {@code -90}, not {@code 270}; the extra add-and-mod is what fixes that.
   */
  public static double normalizeBearing(double degrees) {
    double wrapped = ((degrees % 360) + 360) % 360;
    // A tiny negative input rounds up to exactly 360.0 through the double arithmetic above.
    return wrapped == 360.0 ? 0.0 : wrapped;
  }

  /** Wraps a longitude into {@code [-180, 180]}, the range {@link GeoPoint} validates against. */
  public static double normalizeLongitude(double degrees) {
    return ((degrees + 540) % 360) - 180;
  }
}
