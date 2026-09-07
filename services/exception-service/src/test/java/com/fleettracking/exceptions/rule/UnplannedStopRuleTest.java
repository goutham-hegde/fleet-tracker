package com.fleettracking.exceptions.rule;

import static com.fleettracking.exceptions.Fixtures.T0;
import static com.fleettracking.exceptions.Fixtures.plan;
import static com.fleettracking.exceptions.Fixtures.position;
import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.ExceptionType;
import com.fleettracking.events.Severity;
import com.fleettracking.exceptions.ExceptionProperties;
import com.fleettracking.exceptions.Fixtures;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class UnplannedStopRuleTest {

  private static final ExceptionProperties.UnplannedStop SETTINGS =
      new ExceptionProperties.UnplannedStop(5.0, Duration.ofMinutes(20), 2.0, 150.0);

  private final UnplannedStopRule rule = new UnplannedStopRule(SETTINGS);

  /** Somewhere on the highway between Hyderabad and Kurnool, well away from any stop. */
  private static final double ROADSIDE_LAT = 16.7;
  private static final double ROADSIDE_LON = 78.3;

  private ConditionState fresh() {
    return ConditionState.initial(Fixtures.SHIPMENT, ExceptionType.UNPLANNED_STOP);
  }

  @Test
  void aMovingTruckIsNeverAnUnplannedStop() {
    RuleOutcome outcome =
        rule.evaluate(fresh(), position(Duration.ZERO, ROADSIDE_LAT, ROADSIDE_LON, 74.0), plan(), false);

    assertThat(outcome.raised()).isEmpty();
    assertThat(outcome.state().holding()).isFalse();
  }

  @Test
  void aBriefHaltIsTrafficRatherThanABreakdown() {
    ConditionState state = fresh();
    state = rule.evaluate(state, position(Duration.ZERO, ROADSIDE_LAT, ROADSIDE_LON, 0.4), plan(), false).state();

    RuleOutcome outcome =
        rule.evaluate(
            state, position(Duration.ofMinutes(4), ROADSIDE_LAT, ROADSIDE_LON, 0.2), plan(), false);

    assertThat(outcome.raised()).isEmpty();
    assertThat(outcome.state().holding()).isTrue();
  }

  @Test
  void stationaryPastTheThresholdIsRaisedAndStampedAtTheHalt() {
    ConditionState state = fresh();
    state = rule.evaluate(state, position(Duration.ZERO, ROADSIDE_LAT, ROADSIDE_LON, 0.4), plan(), false).state();

    RuleOutcome outcome =
        rule.evaluate(
            state, position(Duration.ofMinutes(25), ROADSIDE_LAT, ROADSIDE_LON, 0.1), plan(), false);

    assertThat(outcome.raised()).isPresent();
    var finding = outcome.raised().get();
    assertThat(finding.type()).isEqualTo(ExceptionType.UNPLANNED_STOP);
    assertThat(finding.onsetAt()).isEqualTo(T0);
    // No stop is named, which is the point of the rule: it fires where there is no stop to name.
    assertThat(finding.stopId()).isNull();
    assertThat(finding.detail()).contains("Stationary for 25 minutes");
  }

  @Test
  void aTruckParkedAtOneOfItsOwnStopsIsNotAnUnplannedStop() {
    // The most ordinary event in freight: a vehicle sitting on a dock for an hour.
    ConditionState state = fresh();
    RuleOutcome first =
        rule.evaluate(
            state,
            position(Duration.ZERO, Fixtures.HYDERABAD.latitude(), Fixtures.HYDERABAD.longitude(), 0.0),
            plan(),
            false);
    RuleOutcome outcome =
        rule.evaluate(
            first.state(),
            position(
                Duration.ofMinutes(90),
                Fixtures.HYDERABAD.latitude(),
                Fixtures.HYDERABAD.longitude(),
                0.0),
            plan(),
            false);

    assertThat(outcome.raised()).isEmpty();
    assertThat(outcome.state().holding()).isFalse();
  }

  @Test
  void queueingJustOutsideAYardGateStillCountsAsBeingAtTheStop() {
    // The geofence radius answers "has it arrived". A truck on the approach road has not arrived,
    // and it has not made an unscheduled stop either -- the margin is what separates the two.
    // 600 m north of a 400 m yard: outside the fence, inside twice the radius.
    double justOutside = Fixtures.HYDERABAD.latitude() + 0.0054;

    ConditionState state = fresh();
    state =
        rule.evaluate(
                state,
                position(Duration.ZERO, justOutside, Fixtures.HYDERABAD.longitude(), 0.0),
                plan(),
                false)
            .state();

    RuleOutcome outcome =
        rule.evaluate(
            state,
            position(Duration.ofMinutes(40), justOutside, Fixtures.HYDERABAD.longitude(), 0.0),
            plan(),
            false);

    assertThat(outcome.raised()).isEmpty();
  }

  @Test
  void movingAgainClearsTheStop() {
    ConditionState state = fresh();
    state = rule.evaluate(state, position(Duration.ZERO, ROADSIDE_LAT, ROADSIDE_LON, 0.2), plan(), false).state();
    state =
        rule.evaluate(
                state, position(Duration.ofMinutes(25), ROADSIDE_LAT, ROADSIDE_LON, 0.2), plan(), false)
            .state();

    RuleOutcome outcome =
        rule.evaluate(
            state, position(Duration.ofMinutes(40), ROADSIDE_LAT, ROADSIDE_LON, 61.0), plan(), false);

    assertThat(outcome.cleared()).isPresent();
    assertThat(outcome.cleared().get().resolution()).isEqualTo(Clearance.RESUMED);
    assertThat(outcome.state().holding()).isFalse();
  }

  @Test
  void aPoorFixIsNotConsultedAndLeavesNoTrace() {
    ConditionState before = fresh();

    RuleOutcome outcome =
        rule.evaluate(
            before, position(Duration.ZERO, ROADSIDE_LAT, ROADSIDE_LON, 0.0, 900.0), plan(), false);

    // The same instance. Leaving no trace matters: a later trustworthy fix bearing an earlier
    // instant must still be considered, which it would not be if this one had advanced the clock.
    assertThat(outcome.state()).isSameAs(before);
  }

  @Test
  void aStoppedReeferIsCriticalWhereAStoppedDryVanIsOnlyAWarning() {
    ConditionState dry = fresh();
    dry = rule.evaluate(dry, position(Duration.ZERO, ROADSIDE_LAT, ROADSIDE_LON, 0.0), plan(), false).state();
    var dryOutcome =
        rule.evaluate(
            dry, position(Duration.ofMinutes(25), ROADSIDE_LAT, ROADSIDE_LON, 0.0), plan(), false);

    ConditionState cold = fresh();
    cold = rule.evaluate(cold, position(Duration.ZERO, ROADSIDE_LAT, ROADSIDE_LON, 0.0), plan(), true).state();
    var coldOutcome =
        rule.evaluate(
            cold, position(Duration.ofMinutes(25), ROADSIDE_LAT, ROADSIDE_LON, 0.0), plan(), true);

    assertThat(dryOutcome.raised().orElseThrow().severity()).isEqualTo(Severity.WARNING);
    assertThat(coldOutcome.raised().orElseThrow().severity()).isEqualTo(Severity.CRITICAL);
    assertThat(coldOutcome.raised().orElseThrow().detail()).contains("temperature-controlled");
  }

  @Test
  void aStoppedTruckWithNoPlanIsStillReported() {
    // An unplanned load sitting still is more suspicious than a planned one, not less.
    ConditionState state = fresh();
    state = rule.evaluate(state, position(Duration.ZERO, ROADSIDE_LAT, ROADSIDE_LON, 0.0), null, false).state();

    RuleOutcome outcome =
        rule.evaluate(
            state, position(Duration.ofMinutes(25), ROADSIDE_LAT, ROADSIDE_LON, 0.0), null, false);

    assertThat(outcome.raised()).isPresent();
    assertThat(outcome.raised().get().detail()).contains("no scheduled stops on file");
  }
}
