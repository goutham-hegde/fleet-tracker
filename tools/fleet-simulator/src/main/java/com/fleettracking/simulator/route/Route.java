package com.fleettracking.simulator.route;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * An ordered sequence of stops and the legs between them — the itinerary one truck runs.
 *
 * <p>A route is immutable and stateless: it says where the stops are, not where any truck is. That
 * separation is what lets several trucks run the same lane at once without interfering, and it is
 * why the moving parts live in {@code Truck} rather than here.
 *
 * @param id stable identifier, e.g. "chi-dal-i55"
 * @param name human-readable lane description
 * @param stops at least two, in the order they are visited
 * @param legs derived from {@code stops}; always {@code stops.size() - 1} of them
 */
public record Route(String id, String name, List<Stop> stops, List<Leg> legs) {

  /**
   * How much longer a real road is than the straight line between its endpoints.
   *
   * <p>Roads bend around rivers, mountains and property lines, and in cities they follow a grid,
   * which costs distance whenever the destination is not due north or due east. Empirically the
   * driving distance between two US points averages 1.15 to 1.25 times the great-circle distance,
   * with the ratio highest for short urban hops and lowest for long interstate runs.
   *
   * <p>The simulator drives the straight line but bills the truck for the longer road, so that the
   * <em>time</em> a leg takes is realistic even though the <em>path</em> is simplified. Getting
   * this wrong in the optimistic direction would be the worst outcome for M3: every ETA the
   * platform computes would look impressively accurate, because the trucks would be cheating in
   * exactly the way the naive ETA calculation assumes they can.
   */
  public static final double ROAD_CIRCUITY = 1.18;

  public Route {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(stops, "stops");
    Objects.requireNonNull(legs, "legs");
    if (stops.size() < 2) {
      throw new IllegalArgumentException("a route needs at least two stops, got " + stops.size());
    }
    stops = List.copyOf(stops);
    legs = List.copyOf(legs);
  }

  /** Builds a route from its stops, deriving the legs. */
  public static Route of(String id, String name, List<Stop> stops) {
    Objects.requireNonNull(stops, "stops");
    if (stops.size() < 2) {
      throw new IllegalArgumentException("a route needs at least two stops, got " + stops.size());
    }
    List<Leg> legs =
        java.util.stream.IntStream.range(0, stops.size() - 1)
            .mapToObj(i -> Leg.between(stops.get(i), stops.get(i + 1)))
            .toList();
    return new Route(id, name, stops, legs);
  }

  /** The stop the route starts from. */
  public Stop origin() {
    return stops.getFirst();
  }

  /** The final stop. A truck that reaches it has finished the route. */
  public Stop destination() {
    return stops.getLast();
  }

  /** Straight-line distance over every leg, metres. */
  public double totalDistanceMeters() {
    return legs.stream().mapToDouble(Leg::distanceMeters).sum();
  }

  /** Total distance corrected for road circuity — what the odometer should roughly show. */
  public double totalRoadDistanceMeters() {
    return totalDistanceMeters() * ROAD_CIRCUITY;
  }

  /** Combined dwell across every stop, which is time the truck is stationary but still working. */
  public Duration totalDwell() {
    return stops.stream().map(Stop::dwell).reduce(Duration.ZERO, Duration::plus);
  }
}
