package com.fleettracking.exceptions.rule;

import com.fleettracking.events.GeoPoint;
import com.fleettracking.reference.Distance;
import com.fleettracking.reference.ScheduledStop;
import java.util.List;

/**
 * How far a position is from the path a shipment was supposed to take.
 *
 * <h2>What "the planned path" is here, and what it is not</h2>
 *
 * <p>It is the chain of straight lines joining the scheduled stops in order. It is not a road. This
 * platform has no route geometry — the itinerary is a list of places, and turning that into a
 * driveable path is what a routing engine does, which is the same gap the ETA calculation papers
 * over with a circuity factor.
 *
 * <p>That has a direct consequence for the threshold, and it is the reason the corridor is drawn
 * generously. A highway that swings wide around a range of hills is following the road perfectly
 * while sitting a long way off the straight line between two cities. A tight corridor would raise a
 * deviation for every truck that drove the actual route, which is the worst possible failure for an
 * alerting rule: it would be loudest when everything was working. So the corridor is wide enough
 * that only a genuine diversion escapes it, and the honest statement of what this rule detects is
 * "went somewhere else", not "left the road".
 *
 * <p>A tighter, more useful version of this rule is one of the clearest things a routing engine
 * would buy. It is written down here rather than in a backlog because the width of the corridor is
 * the whole accuracy of the rule, and a future reader tightening it without adding route geometry
 * would be making the rule worse while appearing to make it stricter.
 *
 * <h2>Distance to a segment, not to a line</h2>
 *
 * <p>The cross-track distance from a great circle is the standard formula, and on its own it is
 * wrong here: an infinite great circle through Delhi and Mumbai passes near a truck in the Arabian
 * Sea, which would be reported as perfectly on route. The along-track distance says where the
 * closest approach falls, and when that lands outside the two stops the answer is the distance to
 * the nearer stop instead. Every leg is measured and the smallest wins, so a truck running its
 * itinerary out of order is on route rather than catastrophically off it.
 */
public final class Corridor {

  private Corridor() {}

  /**
   * The distance in kilometres from a position to the nearest leg of a plan.
   *
   * <p>An itinerary with fewer than two stops has no legs and therefore no corridor; the answer is
   * zero, which reads as "on route" and is the right behaviour. A single-stop plan cannot express a
   * path, and inventing a deviation from one would be raising an exception about missing reference
   * data rather than about a truck.
   */
  public static double kilometersOffPlan(GeoPoint position, List<ScheduledStop> stops) {
    if (stops == null || stops.size() < 2) {
      return 0.0;
    }
    double nearest = Double.MAX_VALUE;
    for (int i = 0; i < stops.size() - 1; i++) {
      double leg =
          metersFromSegment(position, stops.get(i).location(), stops.get(i + 1).location());
      nearest = Math.min(nearest, leg);
    }
    return nearest / 1000.0;
  }

  /**
   * The distance in metres from a point to the great-circle segment between two others.
   *
   * <p>Clamped at both ends: past either endpoint the answer is the distance to that endpoint,
   * which is what makes this a segment rather than an infinite circle.
   */
  static double metersFromSegment(GeoPoint point, GeoPoint from, GeoPoint to) {
    double toStart = Distance.metersBetween(from, point);
    double toEnd = Distance.metersBetween(to, point);
    double legLength = Distance.metersBetween(from, to);

    if (legLength < 1.0) {
      // Two stops in the same place. There is no direction to project onto, and the nearer of two
      // identical answers is the right one.
      return Math.min(toStart, toEnd);
    }

    double angularToPoint = toStart / Distance.EARTH_RADIUS_METERS;
    double bearingToPoint = bearingRadians(from, point);
    double bearingAlongLeg = bearingRadians(from, to);

    double crossTrack =
        Math.asin(Math.sin(angularToPoint) * Math.sin(bearingToPoint - bearingAlongLeg))
            * Distance.EARTH_RADIUS_METERS;

    // How far along the leg the closest approach falls. Negative means the point is behind the
    // start; longer than the leg means it is past the end.
    double alongTrack =
        Math.acos(
                clamp(
                    Math.cos(angularToPoint)
                        / Math.cos(crossTrack / Distance.EARTH_RADIUS_METERS)))
            * Distance.EARTH_RADIUS_METERS;

    if (bearingDifferenceIsBehind(bearingToPoint, bearingAlongLeg)) {
      return toStart;
    }
    if (alongTrack > legLength) {
      return toEnd;
    }
    return Math.abs(crossTrack);
  }

  /** Initial bearing from one point to another, in radians. */
  private static double bearingRadians(GeoPoint from, GeoPoint to) {
    double lat1 = Math.toRadians(from.latitude());
    double lat2 = Math.toRadians(to.latitude());
    double deltaLon = Math.toRadians(to.longitude() - from.longitude());

    double y = Math.sin(deltaLon) * Math.cos(lat2);
    double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);
    return Math.atan2(y, x);
  }

  /**
   * Whether the point lies behind the start of the leg.
   *
   * <p>The along-track formula returns a magnitude and loses the sign, so the direction has to come
   * from the bearings: more than a right angle between "towards the point" and "along the leg" means
   * the closest approach is behind the starting stop.
   */
  private static boolean bearingDifferenceIsBehind(double bearingToPoint, double bearingAlongLeg) {
    double difference = Math.abs(normalize(bearingToPoint - bearingAlongLeg));
    return difference > Math.PI / 2;
  }

  /** Wraps an angle into (-pi, pi]. */
  private static double normalize(double radians) {
    double wrapped = radians % (2 * Math.PI);
    if (wrapped > Math.PI) {
      wrapped -= 2 * Math.PI;
    }
    if (wrapped <= -Math.PI) {
      wrapped += 2 * Math.PI;
    }
    return wrapped;
  }

  private static double clamp(double value) {
    return Math.max(-1.0, Math.min(1.0, value));
  }
}
