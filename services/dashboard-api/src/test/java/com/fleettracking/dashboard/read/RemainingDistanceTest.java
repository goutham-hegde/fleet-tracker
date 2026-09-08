package com.fleettracking.dashboard.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleettracking.events.GeoPoint;
import com.fleettracking.reference.ScheduledStop;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The distance the dashboard measures rather than reads.
 *
 * <p>These are arithmetic tests, and the one that matters is
 * {@link #theRoadIsLongerThanTheLine()}: a straight-line answer here would look entirely plausible
 * beside an ETA and be wrong by thirty per cent for the whole of every journey.
 */
class RemainingDistanceTest {

  private static final double CIRCUITY = 1.30;

  private final RemainingDistance distances = new RemainingDistance(CIRCUITY);

  /** Pune and Mumbai, roughly 120 km apart on the map and further by road. */
  private static final GeoPoint PUNE = new GeoPoint(18.5204, 73.8567);
  private static final GeoPoint MUMBAI = new GeoPoint(19.0760, 72.8777);

  private static ScheduledStop stop(String id, int seq, double lat, double lon) {
    return new ScheduledStop(id, seq, "Stop " + id, "City", "ST", lat, lon, 400.0, "DELIVERY");
  }

  @Test
  @DisplayName("the road is longer than the line, by exactly the circuity")
  void theRoadIsLongerThanTheLine() {
    double straightKm =
        com.fleettracking.reference.Distance.metersBetween(PUNE, MUMBAI) / 1000.0;

    double road = distances.toStopKm(PUNE, stop("S2", 1, MUMBAI.latitude(), MUMBAI.longitude()));

    assertThat(road).isCloseTo(straightKm * CIRCUITY, org.assertj.core.data.Offset.offset(0.001));
    // Sanity: the great-circle distance between these two cities is about 120 km.
    assertThat(straightKm).isBetween(110.0, 130.0);
  }

  @Test
  @DisplayName("the remaining road runs through every stop left, not straight to the last one")
  void throughEveryRemainingStop() {
    // A dog-leg: north, then east. Going via the corner is measurably further than cutting across.
    GeoPoint here = new GeoPoint(18.0, 73.0);
    ScheduledStop corner = stop("S2", 1, 19.0, 73.0);
    ScheduledStop end = stop("S3", 2, 19.0, 74.0);

    double viaCorner = distances.throughRemainingKm(here, List.of(corner, end));
    double direct = distances.toStopKm(here, end);

    assertThat(viaCorner).isGreaterThan(direct);
  }

  @Test
  @DisplayName("a shipment with no stops left has no road left")
  void nothingLeft() {
    assertThat(distances.throughRemainingKm(PUNE, List.of())).isZero();
  }

  @Test
  @DisplayName("progress is measured against the plan's own length")
  void progressAgainstThePlan() {
    ScheduledStop origin = stop("S1", 0, 18.0, 73.0);
    ScheduledStop middle = stop("S2", 1, 19.0, 73.0);
    ScheduledStop end = stop("S3", 2, 20.0, 73.0);
    List<ScheduledStop> plan = List.of(origin, middle, end);

    // Sitting on the origin with everything ahead: nothing done.
    assertThat(distances.fractionComplete(origin.location(), plan, List.of(middle, end)))
        .isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));

    // At the middle stop, with only the last leg to go: half the plan, since the legs are equal.
    assertThat(distances.fractionComplete(middle.location(), plan, List.of(end)))
        .isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01));

    // Arrived: nothing remaining.
    assertThat(distances.fractionComplete(end.location(), plan, List.of()))
        .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
  }

  @Test
  @DisplayName("a plan too short to have a length reports no progress rather than zero progress")
  void aSingleStopPlanHasNoFraction() {
    ScheduledStop only = stop("S1", 0, 18.0, 73.0);

    // Null, not 0.0. A zero would draw an empty progress bar, which reads as a truck that has not
    // moved -- a definite claim about a shipment there is nothing definite to say about.
    assertThat(distances.fractionComplete(only.location(), List.of(only), List.of())).isNull();
  }

  @Test
  @DisplayName("progress cannot go negative when a truck overshoots its plan")
  void progressIsClamped() {
    ScheduledStop origin = stop("S1", 0, 18.0, 73.0);
    ScheduledStop end = stop("S2", 1, 19.0, 73.0);
    // Far past the destination, with the destination still not announced as arrived.
    GeoPoint wayPastIt = new GeoPoint(25.0, 73.0);

    Double fraction = distances.fractionComplete(wayPastIt, List.of(origin, end), List.of(end));

    assertThat(fraction).isNotNull().isBetween(0.0, 1.0);
  }

  @Test
  @DisplayName("a road shorter than the straight line is a misconfiguration, not a setting")
  void circuityBelowOneIsRefused() {
    assertThatThrownBy(() -> new RemainingDistance(0.9))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("road circuity");
  }
}
