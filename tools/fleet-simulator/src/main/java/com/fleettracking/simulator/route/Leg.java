package com.fleettracking.simulator.route;

import com.fleettracking.events.GeoPoint;
import java.util.Objects;

/**
 * The drive between two consecutive stops.
 *
 * <p>Distance is computed once, when the route is built, because it never changes and the tick
 * loop asks for it constantly. The <em>bearing</em>, by contrast, is deliberately not cached: on a
 * great circle it changes continuously along the path (see {@link Geo#initialBearingDegrees}), so
 * a stored value would be right at the departure stop and progressively wrong afterwards. The
 * truck recomputes it from wherever it currently is.
 *
 * <p>Note that {@link #distanceMeters()} is the straight great-circle distance, which is shorter
 * than any road between the same two points — highways bend around terrain and follow the grid.
 * {@link Route#ROAD_CIRCUITY} exists to correct for that when the simulator reasons about how long
 * a leg should take, and the discrepancy is a deliberate, documented property of this model rather
 * than an oversight.
 *
 * @param from the stop being left
 * @param to the stop being driven to
 * @param distanceMeters great-circle distance between them, computed at construction
 */
public record Leg(Stop from, Stop to, double distanceMeters) {

  public Leg {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
  }

  /** Builds a leg between two stops, computing the distance. */
  public static Leg between(Stop from, Stop to) {
    return new Leg(from, to, Geo.distanceMeters(from.location(), to.location()));
  }

  /**
   * The heading to steer right now, given where the truck actually is.
   *
   * <p>Taking the truck's live position rather than {@code from.location()} is what makes the
   * heading track the curve of the great circle, and it also means a truck nudged off the line by
   * GPS noise steers back toward the destination instead of holding a stale course forever.
   */
  public double bearingFrom(GeoPoint current) {
    return Geo.initialBearingDegrees(current, to.location());
  }

  /** Distance still to run from a live position to the destination stop, in metres. */
  public double remainingMeters(GeoPoint current) {
    return Geo.distanceMeters(current, to.location());
  }
}
