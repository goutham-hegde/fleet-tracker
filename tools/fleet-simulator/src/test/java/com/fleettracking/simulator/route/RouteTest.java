package com.fleettracking.simulator.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fleettracking.events.GeoPoint;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RouteTest {

  private static Stop stop(String id, double lat, double lon, Duration dwell) {
    return new Stop(
        id, id, "City", "IL", new GeoPoint(lat, lon), 150, dwell, Stop.StopKind.DELIVERY);
  }

  @Test
  @DisplayName("derives one fewer leg than it has stops, in order")
  void derivesLegs() {
    Stop a = stop("a", 41.8781, -87.6298, Duration.ofMinutes(30));
    Stop b = stop("b", 38.6270, -90.1994, Duration.ofMinutes(45));
    Stop c = stop("c", 32.7767, -96.7970, Duration.ofHours(1));

    Route route = Route.of("chi-stl-dal", "Chicago to Dallas via St. Louis", List.of(a, b, c));

    assertThat(route.legs()).hasSize(2);
    assertThat(route.legs().get(0).from()).isEqualTo(a);
    assertThat(route.legs().get(0).to()).isEqualTo(b);
    assertThat(route.legs().get(1).from()).isEqualTo(b);
    assertThat(route.legs().get(1).to()).isEqualTo(c);
  }

  @Test
  @DisplayName("total distance is the sum of the legs, and road distance is longer")
  void totals() {
    Stop a = stop("a", 41.8781, -87.6298, Duration.ZERO);
    Stop b = stop("b", 38.6270, -90.1994, Duration.ZERO);
    Stop c = stop("c", 32.7767, -96.7970, Duration.ZERO);
    Route route = Route.of("r", "r", List.of(a, b, c));

    double expected =
        Geo.distanceMeters(a.location(), b.location()) + Geo.distanceMeters(b.location(), c.location());

    assertThat(route.totalDistanceMeters()).isCloseTo(expected, within(1e-6));
    // Going via St. Louis is a detour, so the two-leg total must exceed the direct line.
    assertThat(route.totalDistanceMeters())
        .isGreaterThan(Geo.distanceMeters(a.location(), c.location()));
    assertThat(route.totalRoadDistanceMeters())
        .isCloseTo(expected * Route.ROAD_CIRCUITY, within(1e-6));
  }

  @Test
  @DisplayName("sums dwell across every stop")
  void totalDwell() {
    Route route =
        Route.of(
            "r",
            "r",
            List.of(
                stop("a", 41.8781, -87.6298, Duration.ofMinutes(30)),
                stop("b", 38.6270, -90.1994, Duration.ofMinutes(45)),
                stop("c", 32.7767, -96.7970, Duration.ofHours(1))));

    assertThat(route.totalDwell()).isEqualTo(Duration.ofMinutes(135));
  }

  @Test
  @DisplayName("origin and destination are the ends of the itinerary")
  void ends() {
    Stop a = stop("a", 41.8781, -87.6298, Duration.ZERO);
    Stop c = stop("c", 32.7767, -96.7970, Duration.ZERO);
    Route route = Route.of("r", "r", List.of(a, stop("b", 38.6270, -90.1994, Duration.ZERO), c));

    assertThat(route.origin()).isEqualTo(a);
    assertThat(route.destination()).isEqualTo(c);
  }

  @Test
  @DisplayName("rejects a route that does not go anywhere")
  void rejectsSingleStopRoute() {
    List<Stop> one = List.of(stop("a", 41.8781, -87.6298, Duration.ZERO));
    assertThatThrownBy(() -> Route.of("r", "r", one))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least two stops");
  }

  @Test
  @DisplayName("is immutable even if the caller keeps the list it passed in")
  void copiesItsStops() {
    List<Stop> mutable =
        new java.util.ArrayList<>(
            List.of(
                stop("a", 41.8781, -87.6298, Duration.ZERO),
                stop("b", 32.7767, -96.7970, Duration.ZERO)));
    Route route = Route.of("r", "r", mutable);

    mutable.clear();

    assertThat(route.stops()).hasSize(2);
  }

  @Test
  @DisplayName("a stop must have a positive geofence radius")
  void rejectsNonPositiveGeofence() {
    assertThatThrownBy(
            () ->
                new Stop(
                    "a", "a", "Chicago", "IL", new GeoPoint(41.8781, -87.6298), 0,
                    Duration.ZERO, Stop.StopKind.PICKUP))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("geofenceRadiusMeters");
  }
}
