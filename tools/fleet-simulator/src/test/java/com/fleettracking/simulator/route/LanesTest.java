package com.fleettracking.simulator.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Sanity checks on the shipped lanes.
 *
 * <p>These are fixture tests, and the thing they defend against is a typo in a coordinate. A
 * transposed digit in a longitude does not fail to compile and does not look wrong in a diff; it
 * shows up as a truck driving into the Gulf of Mexico, three milestones later, on a map.
 */
class LanesTest {

  static List<Route> lanes() {
    return Lanes.ALL;
  }

  @Test
  @DisplayName("every lane has a unique id")
  void idsAreUnique() {
    assertThat(Lanes.ALL.stream().map(Route::id).distinct().count()).isEqualTo(Lanes.ALL.size());
  }

  @Test
  @DisplayName("looks a lane up by id, and says so clearly when there is no such lane")
  void lookup() {
    assertThat(Lanes.byId("del-bom-nh48")).isSameAs(Lanes.DELHI_MUMBAI);
    assertThatThrownBy(() -> Lanes.byId("no-such-lane"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no such lane");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lanes")
  @DisplayName("stop ids are unique within a lane")
  void stopIdsAreUniqueWithinALane(Route route) {
    assertThat(route.stops().stream().map(Stop::id).distinct().count())
        .isEqualTo(route.stops().size());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lanes")
  @DisplayName("every stop sits inside mainland India")
  void stopsAreInMainlandIndia(Route route) {
    // A bounding box, not a precise border - it is here to catch a sign error or a transposed
    // pair, both of which land a stop thousands of kilometres outside it. A dropped minus sign
    // used to put a truck in the Pacific; now it puts one in the Atlantic, which the longitude
    // bound catches just as well.
    assertThat(route.stops())
        .allSatisfy(
            s -> {
              assertThat(s.location().latitude()).isBetween(6.0, 37.0);
              assertThat(s.location().longitude()).isBetween(68.0, 98.0);
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lanes")
  @DisplayName("legs are long enough to be a real drive and short enough to be one leg")
  void legsArePlausible(Route route) {
    // The floor is 15 km rather than the 80 the US lanes used, because the port shuttle really
    // does have a short first leg: Nhava Sheva to Panvel is under twenty kilometres and is a
    // genuine drayage movement, not a typo. The ceiling still catches a coordinate that has
    // wandered to another country.
    assertThat(route.legs())
        .allSatisfy(
            leg -> {
              double km = leg.distanceMeters() / 1000;
              assertThat(km).isGreaterThan(15).isLessThan(1600);
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lanes")
  @DisplayName("every route starts with a pickup")
  void routesStartWithAPickup(Route route) {
    assertThat(route.origin().kind()).isEqualTo(Stop.StopKind.PICKUP);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lanes")
  @DisplayName("every stop dwells long enough to be a real stop")
  void dwellsArePlausible(Route route) {
    assertThat(route.stops())
        .allSatisfy(
            s -> {
              assertThat(s.dwell().toMinutes()).isGreaterThanOrEqualTo(15);
              assertThat(s.dwell().toHours()).isLessThanOrEqualTo(4);
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lanes")
  @DisplayName("geofence radii are sized for the kind of facility")
  void geofencesArePlausible(Route route) {
    assertThat(route.stops())
        .allSatisfy(
            s -> assertThat(s.geofenceRadiusMeters()).isBetween(50.0, 800.0));
  }

  @Test
  @DisplayName("the lanes differ in shape, so a bug cannot hide behind uniformity")
  void lanesAreNotAllAlike() {
    List<Integer> stopCounts = Lanes.ALL.stream().map(r -> r.stops().size()).distinct().toList();
    assertThat(stopCounts).hasSizeGreaterThan(1);

    // The part-truckload run is the dense one; the port shuttle is the short one.
    assertThat(Lanes.BENGALURU_CHENNAI.stops()).hasSize(5);
    assertThat(Lanes.NHAVA_SHEVA_PUNE.totalDistanceMeters())
        .isLessThan(Lanes.DELHI_MUMBAI.totalDistanceMeters());
  }
}
