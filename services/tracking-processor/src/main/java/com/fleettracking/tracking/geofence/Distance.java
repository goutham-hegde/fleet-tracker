package com.fleettracking.tracking.geofence;

import com.fleettracking.events.GeoPoint;

/**
 * How far apart two points on the earth are, in metres.
 *
 * <h2>Why this is not shared with the simulator's copy</h2>
 *
 * <p>The simulator has the same formula, and duplicating a well-known equation is normally the
 * wrong instinct. It is the right one here. The simulator is the thing being measured and this
 * service is the ruler: if the geofencer computed distance by calling into the simulator, then a
 * mistake in the formula would move the trucks and move the geofences by exactly the same amount,
 * and every test would still pass. The whole value of grading the platform against the simulator's
 * ground truth comes from the two arriving at their answers independently.
 *
 * <p>It is also eight lines of arithmetic with no state and no dependencies, so the usual cost of
 * duplication — the two copies drifting into disagreement — is bounded by the fact that neither can
 * change without the other's tests noticing that arrivals moved.
 *
 * <h2>The formula</h2>
 *
 * <p>Haversine, on a sphere of the earth's mean radius. It is wrong by up to about 0.5% against the
 * true ellipsoid, which at a 400 m geofence is two metres — well inside the GPS noise the fences
 * already have to tolerate, and in a fixed direction rather than a wobbling one. Something more
 * exact would be more precise about a boundary that is itself an approximation of a fence line
 * somebody drew on a map.
 */
public final class Distance {

  /** Mean earth radius, in metres. The same value the simulator uses, from the same standard. */
  public static final double EARTH_RADIUS_METERS = 6_371_008.8;

  private Distance() {}

  /** Great-circle distance between two points, in metres. */
  public static double metersBetween(GeoPoint from, GeoPoint to) {
    double lat1 = Math.toRadians(from.latitude());
    double lat2 = Math.toRadians(to.latitude());
    double deltaLat = lat2 - lat1;
    double deltaLon = Math.toRadians(to.longitude() - from.longitude());

    double sinHalfLat = Math.sin(deltaLat / 2);
    double sinHalfLon = Math.sin(deltaLon / 2);
    double a = sinHalfLat * sinHalfLat + Math.cos(lat1) * Math.cos(lat2) * sinHalfLon * sinHalfLon;

    return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(a)));
  }
}
