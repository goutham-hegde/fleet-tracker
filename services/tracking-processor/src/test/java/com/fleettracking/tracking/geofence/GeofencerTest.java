package com.fleettracking.tracking.geofence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.GeoPoint;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.RawPayload;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.tracking.itinerary.ScheduledStop;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The geofence state machine, with no database and no broker.
 *
 * <p>Everything that makes geofencing difficult is decided here — hysteresis, dwell, accuracy,
 * out-of-order fixes, and whether an arrival has already been announced — so all of it can be
 * tested in milliseconds. The integration test proves the wiring; this proves the thinking.
 *
 * <p>The stop used throughout is a 400 m yard, matching the real distribution centres on these
 * lanes, with a dock-sized 120 m fence where the difference matters.
 */
class GeofencerTest {

  private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");
  private static final Duration DWELL = Duration.ofMinutes(3);

  /** A 400 m yard at the Okhla DC coordinates. */
  private static final ScheduledStop YARD =
      new ScheduledStop("del-okhla", 0, "Okhla DC", "Delhi", "DL", 28.5355, 77.2730, 400, "PICKUP");

  /** A 120 m kerbside dock, at the same coordinates so only the radius differs. */
  private static final ScheduledStop DOCK =
      new ScheduledStop("knl-clinic", 1, "Clinic dock", "Kurnool", "AP", 28.5355, 77.2730, 120, "DELIVERY");

  private final Geofencer geofencer = new Geofencer(DWELL);

  // --- entering and arriving -------------------------------------------------------------------

  @Test
  void crossingIntoTheFenceIsNotYetAnArrival() {
    GeofenceDecision decision = evaluate(initial(), YARD, atStop(0));

    assertThat(decision.changed()).isTrue();
    assertThat(decision.state().inside()).isTrue();
    assertThat(decision.state().enteredAt()).isEqualTo(T0);
    assertThat(decision.arrivalEvent()).isEmpty();
  }

  @Test
  void stayingInsidePastTheDwellThresholdAnnouncesAnArrival() {
    GeofenceState state = evaluate(initial(), YARD, atStop(0)).state();

    GeofenceDecision decision = evaluate(state, YARD, atStop(4));

    assertThat(decision.arrivalEvent()).isPresent();
    assertThat(decision.state().arrivalAnnounced()).isTrue();
  }

  /**
   * The arrival is stamped with the moment the vehicle crossed in, not the moment we became sure.
   * Those differ by the dwell threshold, and using the later one would make every arrival on the
   * platform look three minutes late against its schedule.
   */
  @Test
  void theArrivalIsStampedWithTheCrossing() {
    GeofenceState state = evaluate(initial(), YARD, atStop(0)).state();

    var arrival = evaluate(state, YARD, atStop(4)).arrivalEvent().orElseThrow();

    assertThat(arrival.occurredAt()).isEqualTo(T0);
    assertThat(arrival.stopId()).isEqualTo("del-okhla");
    assertThat(arrival.causedBy()).isEqualTo(atStop(4).eventId());
  }

  @Test
  void announcesTheArrivalOnlyOnceHoweverLongTheTruckStays() {
    GeofenceState state = evaluate(initial(), YARD, atStop(0)).state();
    state = evaluate(state, YARD, atStop(4)).state();

    for (int minute = 5; minute < 60; minute++) {
      assertThat(evaluate(state, YARD, atStop(minute)).arrivalEvent()).isEmpty();
      state = evaluate(state, YARD, atStop(minute)).state();
    }
    assertThat(state.arrivalAnnounced()).isTrue();
  }

  /** Driving past the gate without stopping is not an arrival. */
  @Test
  void passingStraightThroughAnnouncesNothing() {
    GeofenceState state = evaluate(initial(), YARD, atStop(0)).state();
    state = evaluate(state, YARD, farAway(1)).state();

    assertThat(state.inside()).isFalse();
    assertThat(state.arrivalAnnounced()).isFalse();
  }

  // --- the noise problem -----------------------------------------------------------------------

  /**
   * The case this whole class exists for. A truck parked with its cab on the fence line produces
   * fixes that fall alternately inside and outside for as long as it sits there. Without
   * hysteresis this announces an arrival and a departure per wobble.
   */
  @Test
  void aTruckParkedOnTheBoundaryDoesNotFlap() {
    GeofenceState state = initial();
    int arrivals = 0;
    int departures = 0;

    // Settle inside first, so there is an arrival to be un-done.
    state = evaluate(state, YARD, atStop(0)).state();
    state = evaluate(state, YARD, atStop(4)).state();

    // Then wobble either side of the radius for half an hour, six metres at a time.
    for (int minute = 5; minute < 35; minute++) {
      double meters = (minute % 2 == 0) ? 396 : 404;
      GeofenceDecision decision = evaluate(state, YARD, northOfStop(minute, meters));
      if (decision.arrivalEvent().isPresent()) {
        arrivals++;
      }
      if (decision.departureEvent().isPresent()) {
        departures++;
      }
      state = decision.state();
    }

    assertThat(arrivals).isZero();
    assertThat(departures).isZero();
    assertThat(state.inside()).isTrue();
  }

  /**
   * Leaving requires crossing a wider line than entering did. A fix just beyond the radius, which
   * would be "outside" under a single threshold, leaves the vehicle inside.
   */
  @Test
  void leavingRequiresCrossingTheOuterBoundary() {
    GeofenceState state = evaluate(initial(), YARD, atStop(0)).state();

    assertThat(evaluate(state, YARD, northOfStop(1, 450)).state().inside()).isTrue();
    assertThat(evaluate(state, YARD, northOfStop(1, 520)).state().inside()).isFalse();
  }

  /**
   * A fix whose own error bar is a large fraction of the fence cannot answer the question, so it is
   * not consulted at all — and, importantly, leaves no trace, so a later trustworthy fix bearing an
   * earlier instant is still considered.
   */
  @Test
  void ignoresAFixTooImpreciseToDecide() {
    GeofenceDecision decision = evaluate(initial(), YARD, atStop(0, 150.0));

    assertThat(decision.changed()).isFalse();
    assertThat(decision.state().lastFixAt()).isNull();
  }

  @Test
  void trustsAFixWellInsideTheAccuracyBudget() {
    assertThat(evaluate(initial(), YARD, atStop(0, 6.0)).state().inside()).isTrue();
  }

  /** The gate scales with the fence: 40 m is fine at a 400 m yard and useless at a 120 m dock. */
  @Test
  void appliesTheAccuracyGateRelativeToTheFenceSize() {
    assertThat(evaluate(initial(), YARD, atStop(0, 40.0)).changed()).isTrue();
    assertThat(evaluate(initial(), DOCK, atStop(0, 40.0)).changed()).isFalse();
  }

  /**
   * A fix exactly at the gate is trusted; the rule is "worse than", not "at least as bad as".
   * Pinned because it is the kind of boundary that gets flipped by a later tidy-up without anyone
   * noticing which side it was on.
   */
  @Test
  void trustsAFixExactlyAtTheAccuracyLimit() {
    assertThat(evaluate(initial(), DOCK, atStop(0, 30.0)).changed()).isTrue();
    assertThat(evaluate(initial(), DOCK, atStop(0, 30.001)).changed()).isFalse();
  }

  // --- leaving and departing -------------------------------------------------------------------

  @Test
  void leavingAndStayingOutAnnouncesADeparture() {
    GeofenceState state = arrived();

    state = evaluate(state, YARD, farAway(50)).state();
    var departure = evaluate(state, YARD, farAway(54)).departureEvent().orElseThrow();

    assertThat(departure.occurredAt()).isEqualTo(T0.plus(Duration.ofMinutes(50)));
    assertThat(departure.stopId()).isEqualTo("del-okhla");
  }

  /**
   * Detention time, computed once here rather than left for each consumer to reconstruct. It runs
   * from the crossing in to the crossing out, not from when either was confirmed.
   */
  @Test
  void theDepartureStatesHowLongTheTruckWasThere() {
    GeofenceState state = arrived();
    state = evaluate(state, YARD, farAway(50)).state();

    var departure = evaluate(state, YARD, farAway(54)).departureEvent().orElseThrow();

    assertThat(departure.dwell()).isEqualTo(Duration.ofMinutes(50));
  }

  @Test
  void announcesTheDepartureOnlyOnce() {
    GeofenceState state = arrived();
    state = evaluate(state, YARD, farAway(50)).state();
    state = evaluate(state, YARD, farAway(54)).state();

    for (int minute = 55; minute < 90; minute++) {
      assertThat(evaluate(state, YARD, farAway(minute)).departureEvent()).isEmpty();
    }
  }

  /** No arrival was ever announced, so there is nothing to depart from. */
  @Test
  void doesNotAnnounceADepartureForAStopItNeverArrivedAt() {
    GeofenceState state = evaluate(initial(), YARD, atStop(0)).state();

    // Left again after one minute, well inside the dwell threshold.
    state = evaluate(state, YARD, farAway(1)).state();
    GeofenceDecision decision = evaluate(state, YARD, farAway(10));

    assertThat(decision.departureEvent()).isEmpty();
    assertThat(decision.arrivalEvent()).isEmpty();
  }

  /**
   * Once a stop has been arrived at and departed from it is finished. A truck that comes back is a
   * second visit the plan does not describe, and inventing a second arrival for it would break the
   * one guarantee this class owes the rest of the platform.
   */
  @Test
  void aCompletedStopIsNotReopenedByAReturnVisit() {
    GeofenceState state = arrived();
    state = evaluate(state, YARD, farAway(50)).state();
    state = evaluate(state, YARD, farAway(54)).state();
    assertThat(state.isComplete()).isTrue();

    GeofenceDecision decision = evaluate(state, YARD, atStop(200));

    assertThat(decision.changed()).isFalse();
    assertThat(decision.arrivalEvent()).isEmpty();
  }

  // --- staying cheap ---------------------------------------------------------------------------

  /**
   * A truck driving an eight-hour leg is nowhere near any of its stops, and must cost nothing.
   *
   * <p>Every fix is evaluated against every stop on the plan, which is only affordable because a
   * fix far from a fence changes nothing and therefore writes nothing. When this regressed, the
   * state row was still rewritten each time purely to record the fix instant, turning one position
   * into a write per stop.
   */
  @Test
  void aFixFarFromTheFenceChangesNothingAtAll() {
    GeofenceState state = initial();

    for (int minute = 0; minute < 60; minute++) {
      GeofenceDecision decision = evaluate(state, YARD, farAway(minute));
      assertThat(decision.changed()).isFalse();
      assertThat(decision.state()).isSameAs(state);
    }
  }

  /** Having driven past without stopping is also dormant: nothing was announced, so nothing is owed. */
  @Test
  void aStopDrivenPastWithoutStoppingGoesQuietAgain() {
    GeofenceState state = evaluate(initial(), YARD, atStop(0)).state();
    state = evaluate(state, YARD, farAway(1)).state();

    assertThat(evaluate(state, YARD, farAway(2)).changed()).isFalse();
  }

  /** But a stop that was arrived at and not yet departed from is still live, and must be tracked. */
  @Test
  void aStopWithAnUnfinishedArrivalKeepsBeingEvaluated() {
    GeofenceState state = arrived();

    assertThat(evaluate(state, YARD, farAway(50)).changed()).isTrue();
  }

  // --- out-of-order fixes ----------------------------------------------------------------------

  /**
   * The mobile app buffers through a dead zone and then dumps its backlog in whatever order it
   * comes out. Applying an old fix would walk the state machine backwards — the vehicle un-arrives.
   */
  @Test
  void ignoresAFixOlderThanTheNewestAlreadyApplied() {
    GeofenceState state = arrived();
    state = evaluate(state, YARD, farAway(50)).state();

    // A fix from inside the yard, arriving late and out of order.
    GeofenceDecision decision = evaluate(state, YARD, atStop(30));

    assertThat(decision.changed()).isFalse();
    assertThat(decision.state().inside()).isFalse();
  }

  @Test
  void ignoresARepeatOfTheNewestFix() {
    GeofenceState state = evaluate(initial(), YARD, atStop(0)).state();

    assertThat(evaluate(state, YARD, atStop(0)).changed()).isFalse();
  }

  // --- helpers ---------------------------------------------------------------------------------

  private GeofenceDecision evaluate(GeofenceState state, ScheduledStop stop, PositionEvent event) {
    return geofencer.evaluate(state, stop, event);
  }

  private static GeofenceState initial() {
    return GeofenceState.initial("SHP-DEL-0001", "del-okhla");
  }

  /** A vehicle that entered at T0 and had its arrival confirmed four minutes later. */
  private GeofenceState arrived() {
    GeofenceState state = evaluate(initial(), YARD, atStop(0)).state();
    return evaluate(state, YARD, atStop(4)).state();
  }

  private static PositionEvent atStop(int minute) {
    return atStop(minute, 6.0);
  }

  /** Dead centre of the stop. */
  private static PositionEvent atStop(int minute, Double accuracyMeters) {
    return at(minute, 28.5355, 77.2730, accuracyMeters);
  }

  /** A given number of metres due north of the stop, for testing a boundary precisely. */
  private static PositionEvent northOfStop(int minute, double meters) {
    double degreesPerMeter = 1.0 / (Distance.EARTH_RADIUS_METERS * Math.PI / 180.0);
    return at(minute, 28.5355 + meters * degreesPerMeter, 77.2730, 6.0);
  }

  /** Comfortably outside any fence: roughly 9 km away. */
  private static PositionEvent farAway(int minute) {
    return at(minute, 28.6155, 77.2730, 6.0);
  }

  private static PositionEvent at(int minute, double lat, double lon, Double accuracyMeters) {
    Instant occurredAt = T0.plus(Duration.ofMinutes(minute));
    return new PositionEvent(
        "evt-" + occurredAt.toEpochMilli(),
        "SHP-DEL-0001",
        "VEH-0001",
        "TLM-0001",
        occurredAt,
        occurredAt.plusSeconds(2),
        new GeoPoint(lat, lon),
        12.0,
        180.0,
        123456.0,
        accuracyMeters,
        RawPayload.of(SourceSystem.TELEMATICS, "{}"));
  }
}
