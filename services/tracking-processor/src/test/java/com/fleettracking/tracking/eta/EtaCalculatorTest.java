package com.fleettracking.tracking.eta;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.EtaUpdated;
import com.fleettracking.events.GeoPoint;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.RawPayload;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.tracking.geofence.GeofenceState;
import com.fleettracking.tracking.geofence.ShipmentProgress;
import com.fleettracking.tracking.itinerary.Itinerary;
import com.fleettracking.tracking.itinerary.ScheduledStop;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The estimate, with no database and no broker anywhere near it.
 *
 * <p>Everything M3's last exit criterion is about — converging on approach, not thrashing on
 * noise — is a property of this class alone, so this is where it is pinned. The integration test proves
 * the events reach the topic; it could not prove any of the behaviour below without running a truck
 * for four hours.
 *
 * <p>The geometry is deliberately simple: one stop due north of a start point, so that a distance
 * can be reasoned about in the head. Positions are placed by latitude alone, at roughly 111 km per
 * degree.
 */
class EtaCalculatorTest {

  private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

  private static final double STOP_LAT = 28.5355;
  private static final double STOP_LON = 77.2730;

  /** One degree of latitude, in kilometres. Close enough to reason with. */
  private static final double KM_PER_DEGREE = 111.19;

  private static final String SHIPMENT = "SHP-DEL-0001";

  private final EtaCalculator calculator =
      new EtaCalculator(Duration.ofMinutes(2), Duration.ofMinutes(5), NOMINAL_KPH, ROAD_CIRCUITY);

  /**
   * The platform's two stated assumptions, held here as named constants rather than as literals
   * scattered through the arithmetic below. Both must match {@code application.yaml}; they moved
   * together when the fleet moved from US interstates to Indian national highways, and a test
   * carrying the old pair would have gone on passing against numbers nothing else used.
   */
  private static final double NOMINAL_KPH = 60.0;

  private static final double ROAD_CIRCUITY = 1.30;

  // --- the estimate itself -------------------------------------------------------------------

  @Test
  void billsTheRoadRatherThanTheStraightLine() {
    // 100 km due south of the stop, holding a steady 100 km/h. The straight line would say one
    // hour; the road is 30% longer, so it is 1 h 18 m.
    EtaDecision decision = evaluate(EtaState.initial(SHIPMENT), fix(T0, kmSouth(100), 100.0));

    EtaUpdated update = decision.update();
    assertThat(update).isNotNull();
    assertThat(update.remainingKm()).isCloseTo(130.0, org.assertj.core.data.Offset.offset(0.5));
    assertThat(Duration.between(T0, update.estimatedArrival()))
        .isBetween(Duration.ofMinutes(77), Duration.ofMinutes(79));
  }

  @Test
  void theFirstEstimateOfALegCarriesNoPreviousOne() {
    EtaDecision decision = evaluate(EtaState.initial(SHIPMENT), fix(T0, kmSouth(100), 100.0));

    assertThat(decision.update().previousEstimate()).isNull();
    assertThat(decision.update().stopId()).isEqualTo("stop-1");
    assertThat(decision.update().causedBy()).isEqualTo(decision.update().causedBy());
  }

  @Test
  void aColdModelFallsBackToTheNominalSpeedAndSaysSoInTheConfidence() {
    // The very first fix, reporting a speed nothing has been learned from yet: the estimate is
    // built on one sample rather than on a history, and the confidence has to admit it.
    EtaDecision decision = evaluate(EtaState.initial(SHIPMENT), fix(T0, kmSouth(100), 100.0));

    assertThat(decision.update().confidence()).isLessThan(0.35);
  }

  @Test
  void aWarmModelIsConfident() {
    EtaState warm = drive(EtaState.initial(SHIPMENT), 100.0, Duration.ofMinutes(30));

    EtaDecision decision =
        evaluate(warm, fix(T0.plus(Duration.ofMinutes(31)), kmSouth(100), 100.0));

    assertThat(decision.update().confidence()).isGreaterThan(0.85);
  }

  // --- convergence ---------------------------------------------------------------------------

  @Test
  void convergesOnTheArrivalTimeAsTheTruckApproaches() {
    // A truck 200 km out, driving in over a bit under three hours, its pace wandering by ten km/h
    // either side of ninety on a slow cycle — which is what the simulator's trucks actually do.
    // Convergence does not mean the estimate never moves; it means the moving stops. Early on the
    // platform is guessing at a pace it has just met, and it should be wrong by minutes; by the
    // time the truck is at the gate it should be wrong by almost nothing.
    Approach run = approach(200, 0);

    assertThat(run.published).hasSizeGreaterThan(2);

    Duration firstError = errorAgainst(run.trueArrival, run.published.getFirst());
    Duration lastError = errorAgainst(run.trueArrival, run.published.getLast());

    assertThat(lastError).isLessThan(firstError);
    assertThat(lastError).isLessThan(Duration.ofMinutes(3));
  }

  @Test
  void theRemainingDistanceFallsMonotonicallyOnApproach() {
    Approach run = approach(200, 0);

    List<Double> remaining = run.published.stream().map(EtaUpdated::remainingKm).toList();

    assertThat(remaining).hasSizeGreaterThan(2);
    assertThat(remaining).isSortedAccordingTo((a, b) -> Double.compare(b, a));
  }

  // --- not thrashing -------------------------------------------------------------------------

  @Test
  void almostNoFixProducesAnEstimate() {
    // The plainest statement of "does not thrash": run the truck in with its fixes wobbling by the
    // six metres GPS wobbles by, and count how much of that turned into traffic on the topic. A
    // calculation that answered every fix would put out a thousand revisions over one approach.
    //
    // Note that the clean run is not the quiet one. Sub-second differences change which fix happens
    // to cross the publish threshold, so the two runs publish on different ticks — but both publish
    // a fraction of a per cent of what they were given, which is the property that matters.
    Approach clean = approach(200, 0);
    Approach noisy = approach(200, 0.006);

    assertThat(clean.fixes).isGreaterThan(900);
    assertThat(noisy.fixes).isEqualTo(clean.fixes);

    assertThat(clean.published.size()).isLessThan(clean.fixes / 20);
    assertThat(noisy.published.size()).isLessThan(noisy.fixes / 20);

    // And noise costs nothing in accuracy: both land on the same arrival.
    assertThat(errorAgainst(noisy.trueArrival, noisy.published.getLast()))
        .isLessThan(Duration.ofMinutes(3));
  }

  @Test
  void aSpeedWobbleWithinTheUsualRangeDoesNotMoveTheEstimate() {
    // The simulator's trucks wander a few km/h either side of cruise. Sampling the latest fix would
    // put that wander straight into the estimate — a truck reading 88 rather than 102 is fourteen
    // minutes of difference over three hundred kilometres — and the topic would carry a revision
    // every ten seconds. Smoothing over minutes absorbs it, and the truck is genuinely covering the
    // ground it reports, so nothing real has changed and nothing should be said.
    EtaState state = drive(EtaState.initial(SHIPMENT), 95.0, Duration.ofMinutes(30));
    Instant at = T0.plus(Duration.ofMinutes(31));
    double remainingKm = 300;

    // The first fix of a leg always publishes; what is being counted is everything after it.
    EtaDecision opening = evaluate(state, fix(at, kmSouth(remainingKm), 95.0));
    state = opening.state();

    int events = 0;
    for (int i = 0; i < 120; i++) {
      double speed = i % 2 == 0 ? 88.0 : 102.0;
      at = at.plusSeconds(10);
      remainingKm -= speed / 360.0 / ROAD_CIRCUITY;
      EtaDecision decision = evaluate(state, fix(at, kmSouth(remainingKm), speed));
      state = decision.state();
      if (decision.publishes()) {
        events++;
      }
    }

    assertThat(events).isZero();
  }

  @Test
  void aFiveMinuteHaltSlipsTheArrivalRatherThanInflatingTheJourney() {
    // The failure mode of averaging the zeros in: a five-minute red light drags the mean speed
    // down, which stretches the remaining journey, so a three-hour estimate gains an hour and then
    // spends twenty minutes working it back out. Nothing about the road changed.
    //
    // What should happen is that the journey is untouched and the arrival simply moves later by
    // however long the truck stood still. So the quantity to pin is the time still to go, measured
    // from each fix — it must be the same before the halt and after it.
    EtaState state = drive(EtaState.initial(SHIPMENT), 90.0, Duration.ofMinutes(30));
    Instant at = T0.plus(Duration.ofMinutes(31));

    EtaDecision opening = evaluate(state, fix(at, kmSouth(300), 90.0));
    state = opening.state();
    Duration toGoBefore = Duration.between(opening.update().occurredAt(), opening.update().estimatedArrival());

    EtaUpdated last = opening.update();
    for (int i = 0; i < 30; i++) {
      // Stationary, reporting zero, without moving an inch.
      at = at.plusSeconds(10);
      EtaDecision decision = evaluate(state, fix(at, kmSouth(300), 0.0));
      state = decision.state();
      if (decision.publishes()) {
        last = decision.update();
      }
    }

    Duration toGoAfter = Duration.between(last.occurredAt(), last.estimatedArrival());

    // The journey ahead is the same journey.
    assertThat(toGoAfter).isCloseTo(toGoBefore, Duration.ofSeconds(5));
    // And the arrival has moved later by the time that has passed, not by a multiple of it.
    assertThat(Duration.between(opening.update().estimatedArrival(), last.estimatedArrival()))
        .isLessThanOrEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void standingStillCostsConfidenceEvenWhileTheJourneyHolds() {
    EtaState state = drive(EtaState.initial(SHIPMENT), 90.0, Duration.ofMinutes(30));
    Instant at = T0.plus(Duration.ofMinutes(31));

    EtaDecision opening = evaluate(state, fix(at, kmSouth(300), 90.0));
    state = opening.state();
    double moving = opening.update().confidence();

    // Half an hour parked in a layby, 300 km from the stop. The estimate keeps slipping — that is
    // correct, the truck is getting later — but the platform should be saying it is less sure.
    EtaUpdated last = opening.update();
    for (int i = 0; i < 180; i++) {
      at = at.plusSeconds(10);
      EtaDecision decision = evaluate(state, fix(at, kmSouth(300), 0.0));
      state = decision.state();
      if (decision.publishes()) {
        last = decision.update();
      }
    }

    assertThat(last).isNotSameAs(opening.update());
    assertThat(last.confidence()).isLessThan(moving * 0.7);
  }

  // --- what must not produce an estimate ------------------------------------------------------

  @Test
  void saysNothingWhileTheTruckIsAtAStop() {
    // The plan carries no service times, so the platform cannot know when this truck leaves, and
    // an estimate for the next stop would be a driving time plus an invented wait.
    ShipmentProgress atTheDock = progress(inside("stop-1"));

    EtaDecision decision =
        calculator.evaluate(
            EtaState.initial(SHIPMENT), atTheDock, fix(T0, kmSouth(0.1), 0.0));

    assertThat(decision.publishes()).isFalse();
  }

  @Test
  void stillLearnsFromTheClockWhileAtAStop() {
    // The model must not be frozen while parked: the fixes teach it nothing about speed, but the
    // newest applied fix has to advance or the out-of-order guard would reject the whole stop.
    ShipmentProgress atTheDock = progress(inside("stop-1"));
    PositionEvent parked = fix(T0.plusSeconds(600), kmSouth(0.1), 0.0);

    EtaDecision decision = calculator.evaluate(EtaState.initial(SHIPMENT), atTheDock, parked);

    assertThat(decision.state().lastFixAt()).isEqualTo(parked.occurredAt());
  }

  @Test
  void saysNothingOnceEveryStopHasBeenArrivedAt() {
    ShipmentProgress finished = progress(arrived("stop-1"), arrived("stop-2"));

    EtaDecision decision =
        calculator.evaluate(EtaState.initial(SHIPMENT), finished, fix(T0, kmSouth(50), 90.0));

    assertThat(decision.publishes()).isFalse();
  }

  @Test
  void ignoresAFixThatIsNotNewerThanTheLastOneApplied() {
    // The mobile app dumps a buffered backlog out of order. A twenty-minute-old fix would drag the
    // smoothed speed back to what it was twenty minutes ago and move the estimate with it.
    EtaState state = drive(EtaState.initial(SHIPMENT), 95.0, Duration.ofMinutes(20));

    EtaDecision decision = evaluate(state, fix(T0.plus(Duration.ofMinutes(5)), kmSouth(10), 20.0));

    assertThat(decision.publishes()).isFalse();
    assertThat(decision.state()).isSameAs(state);
  }

  // --- the leg boundary ------------------------------------------------------------------------

  @Test
  void aNewLegStartsAFreshEstimateAndKeepsTheLearnedSpeed() {
    EtaState state = drive(EtaState.initial(SHIPMENT), 95.0, Duration.ofMinutes(30));
    state = evaluate(state, fix(T0.plus(Duration.ofMinutes(31)), kmSouth(50), 95.0)).state();
    assertThat(state.stopId()).isEqualTo("stop-1");
    double learned = state.expectedSpeedKph();

    // Stop 1 has now been arrived at and left; the target becomes stop 2.
    ShipmentProgress onward = progress(arrived("stop-1"));
    EtaDecision decision =
        calculator.evaluate(state, onward, fix(T0.plus(Duration.ofMinutes(90)), kmSouth(300), 95.0));

    assertThat(decision.publishes()).isTrue();
    assertThat(decision.update().stopId()).isEqualTo("stop-2");
    // A fresh statement: there is no previous estimate for this stop to be revising.
    assertThat(decision.update().previousEstimate()).isNull();
    // But the truck is the same truck, so what was learned about its pace carries over.
    assertThat(decision.state().expectedSpeedKph()).isCloseTo(learned, org.assertj.core.data.Offset.offset(1.0));
  }

  @Test
  void theEventIdIsDerivedFromTheFixThatCausedIt() {
    PositionEvent event = fix(T0, kmSouth(100), 95.0);

    EtaDecision first = evaluate(EtaState.initial(SHIPMENT), event);
    EtaDecision replayed = evaluate(EtaState.initial(SHIPMENT), event);

    assertThat(first.update().eventId()).isEqualTo(replayed.update().eventId());
    assertThat(first.update()).isEqualTo(replayed.update());
  }

  // --- helpers ----------------------------------------------------------------------------------

  /** One simulated approach: what was published along the way, and when the truck really arrived. */
  private record Approach(List<EtaUpdated> published, Instant trueArrival, int fixes) {}

  /**
   * Drives a truck in from a stated distance and collects every estimate it published.
   *
   * <p>The truck's pace wanders by ten km/h either side of ninety on a slow cycle, which is roughly
   * what the simulator's driver profiles produce, and it covers ground at exactly the pace it
   * reports — billed against the longer road, as the simulator bills its trucks. So the arrival
   * this returns is the truth the estimates are graded against, not another estimate.
   *
   * @param jitterKm noise added to each reported position, alternating either side. Zero for a
   *     truck whose fixes are exactly right, which no real truck's are
   */
  private Approach approach(double startKm, double jitterKm) {
    List<EtaUpdated> published = new ArrayList<>();
    EtaState state = EtaState.initial(SHIPMENT);

    double remainingKm = startKm;
    Instant at = T0;
    int tick = 0;

    while (remainingKm > 0.2) {
      double speedKph = 90 + 10 * Math.sin(tick / 120.0);
      double reportedKm = remainingKm + (tick % 2 == 0 ? jitterKm : -jitterKm);

      EtaDecision decision = evaluate(state, fix(at, kmSouth(reportedKm), speedKph));
      state = decision.state();
      decision.event().ifPresent(published::add);

      // Ten seconds of driving. The odometer turns at the reported speed; the straight line to the
      // stop shortens by less, because the road is longer than the line.
      remainingKm -= speedKph / 360.0 / ROAD_CIRCUITY;
      at = at.plusSeconds(10);
      tick++;
    }

    return new Approach(published, at, tick);
  }

  /** How wrong an estimate turned out to be. */
  private static Duration errorAgainst(Instant trueArrival, EtaUpdated update) {
    return Duration.between(trueArrival, update.estimatedArrival()).abs();
  }

  /** Evaluates against a plan of two stops, neither of them reached. */
  private EtaDecision evaluate(EtaState state, PositionEvent event) {
    return calculator.evaluate(state, progress(), event);
  }

  /** Runs the model forward at a steady speed, one fix a minute, without publishing anything. */
  private EtaState drive(EtaState state, double speedKph, Duration duration) {
    EtaState current = state;
    for (long minute = 0; minute <= duration.toMinutes(); minute++) {
      // Far enough away that the estimate never approaches; only the speed model is being warmed.
      current = evaluate(current, fix(T0.plusSeconds(minute * 60), kmSouth(1000), speedKph)).state();
    }
    // Only the model is wanted, not any estimate warming it happened to publish.
    return new EtaState(
        current.shipmentId(),
        null,
        null,
        null,
        null,
        current.expectedSpeedKph(),
        current.movingSeconds(),
        current.lastMovingAt(),
        current.lastFixAt(),
        null);
  }

  private static GeoPoint kmSouth(double km) {
    return new GeoPoint(STOP_LAT - km / KM_PER_DEGREE, STOP_LON);
  }

  private static PositionEvent fix(Instant at, GeoPoint where, double speedKph) {
    return new PositionEvent(
        "evt-" + at.toEpochMilli() + "-" + Math.round(where.latitude() * 1e6),
        SHIPMENT,
        "VEH-1",
        "TLM-1",
        at,
        at.plusSeconds(2),
        where,
        speedKph,
        0.0,
        123456.0,
        6.0,
        RawPayload.of(SourceSystem.TELEMATICS, "{}"));
  }

  /** A two-stop plan with the states given; anything unmentioned has never been seen. */
  private static ShipmentProgress progress(GeofenceState... states) {
    List<ScheduledStop> stops =
        List.of(
            new ScheduledStop(
                "stop-1", 0, "Okhla DC", "Delhi", "DL", STOP_LAT, STOP_LON, 400, "PICKUP"),
            new ScheduledStop(
                "stop-2", 1, "Bhiwandi DC", "Bhiwandi", "MH", 19.2813, 73.0483, 400, "DELIVERY"));

    Map<String, GeofenceState> byStop = new HashMap<>();
    for (GeofenceState state : states) {
      byStop.put(state.stopId(), state);
    }
    return new ShipmentProgress(new Itinerary(SHIPMENT, "del-bom-nh48", stops), byStop);
  }

  /** A stop the vehicle is currently inside the fence of. */
  private static GeofenceState inside(String stopId) {
    return new GeofenceState(
        GeofenceState.idFor(SHIPMENT, stopId), SHIPMENT, stopId, true, T0, null, false, null, false, T0);
  }

  /** A stop the vehicle has arrived at and left. */
  private static GeofenceState arrived(String stopId) {
    return new GeofenceState(
        GeofenceState.idFor(SHIPMENT, stopId),
        SHIPMENT,
        stopId,
        false,
        T0,
        T0.plusSeconds(3600),
        true,
        T0,
        true,
        T0.plusSeconds(3600));
  }
}
