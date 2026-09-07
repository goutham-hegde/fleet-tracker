package com.fleettracking.exceptions.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fleettracking.events.GeoPoint;
import com.fleettracking.exceptions.Fixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The geometry the route-deviation rule stands on.
 *
 * <p>Tested separately from the rule because the two can fail for completely unrelated reasons: a
 * rule that never fires because its threshold is wrong looks identical to one that never fires
 * because the distance is always coming back as zero. These pin the arithmetic so that a failing
 * deviation test can only be about the rule.
 */
class CorridorTest {

  private static final GeoPoint HYDERABAD = new GeoPoint(17.61, 78.58);
  private static final GeoPoint BENGALURU = new GeoPoint(12.9716, 77.5946);

  @Test
  void aPointOnTheLineIsOnTheLine() {
    // Roughly the midpoint of the great circle between the two.
    GeoPoint midpoint = new GeoPoint(15.2966, 78.0975);

    double km = Corridor.metersFromSegment(midpoint, HYDERABAD, BENGALURU) / 1000.0;

    assertThat(km).isLessThan(5.0);
  }

  @Test
  void aPointBesideTheLineIsMeasuredAcrossIt() {
    // A degree of longitude at 15N is about 107 km, so a point that far east of the midpoint
    // should come back at roughly that distance -- not at the distance to either endpoint, which
    // would be several hundred kilometres.
    GeoPoint aside = new GeoPoint(15.2966, 79.1);

    double km = Corridor.metersFromSegment(aside, HYDERABAD, BENGALURU) / 1000.0;

    assertThat(km).isCloseTo(107, within(12.0));
  }

  @Test
  void aPointBeyondTheEndIsMeasuredToTheEndAndNotToAnImaginaryLineBeyondIt() {
    // This is the case that makes it a segment. A truck 200 km south of Bengaluru is 200 km from
    // the plan, but it sits almost exactly on the great circle through both cities extended --
    // so a cross-track calculation alone would report it as perfectly on route.
    GeoPoint pastTheEnd = new GeoPoint(11.0, 77.2);

    double km = Corridor.metersFromSegment(pastTheEnd, HYDERABAD, BENGALURU) / 1000.0;

    assertThat(km).isGreaterThan(200.0);
  }

  @Test
  void aPointBehindTheStartIsMeasuredToTheStart() {
    GeoPoint behind = new GeoPoint(19.5, 79.0);

    double km = Corridor.metersFromSegment(behind, HYDERABAD, BENGALURU) / 1000.0;

    assertThat(km).isGreaterThan(200.0);
  }

  @Test
  void thePlanIsTheNearestOfItsLegs() {
    // A truck near Kurnool is on the Hyderabad-Kurnool leg, so the Kurnool-Bengaluru leg must not
    // drag the answer up. A shipment running its stops out of order is on route, not lost.
    GeoPoint nearKurnool = new GeoPoint(15.83, 78.04);

    double km =
        Corridor.kilometersOffPlan(
            nearKurnool, List.of(Fixtures.HYDERABAD, Fixtures.KURNOOL, Fixtures.BENGALURU));

    assertThat(km).isLessThan(2.0);
  }

  @Test
  void aPlanWithNoLegsHasNoCorridorToLeave() {
    // A single-stop plan cannot express a path. Reporting a deviation from one would be raising an
    // exception about missing reference data rather than about a truck.
    double km = Corridor.kilometersOffPlan(new GeoPoint(20.0, 80.0), List.of(Fixtures.HYDERABAD));

    assertThat(km).isZero();
  }
}
