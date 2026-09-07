package com.fleettracking.exceptions.rule;

import static com.fleettracking.exceptions.Fixtures.T0;
import static com.fleettracking.exceptions.Fixtures.plan;
import static com.fleettracking.exceptions.Fixtures.position;
import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.ExceptionType;
import com.fleettracking.exceptions.ExceptionProperties;
import com.fleettracking.exceptions.Fixtures;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RouteDeviationRuleTest {

  private static final ExceptionProperties.RouteDeviation SETTINGS =
      new ExceptionProperties.RouteDeviation(12.0, Duration.ofMinutes(15), 150.0);

  private final RouteDeviationRule rule = new RouteDeviationRule(SETTINGS);

  /** On the Hyderabad-Kurnool leg. */
  private static final double ON_ROUTE_LAT = 16.7;
  private static final double ON_ROUTE_LON = 78.3;

  /** Roughly 90 km east of it. */
  private static final double DIVERTED_LON = 79.15;

  private ConditionState fresh() {
    return ConditionState.initial(Fixtures.SHIPMENT, ExceptionType.ROUTE_DEVIATION);
  }

  @Test
  void aTruckOnItsPlannedLineIsNotDeviating() {
    RuleOutcome outcome =
        rule.evaluate(fresh(), position(Duration.ZERO, ON_ROUTE_LAT, ON_ROUTE_LON, 70.0), plan());

    assertThat(outcome.raised()).isEmpty();
    assertThat(outcome.state().holding()).isFalse();
  }

  @Test
  void oneStrayFixIsNotADeviation() {
    // The failure this rule is most prone to. A reflected signal in a city, or a receiver
    // reacquiring after a tunnel, puts a truck kilometres sideways for exactly one reading.
    ConditionState state = fresh();
    state = rule.evaluate(state, position(Duration.ZERO, ON_ROUTE_LAT, ON_ROUTE_LON, 70.0), plan()).state();
    state = rule.evaluate(state, position(Duration.ofMinutes(1), ON_ROUTE_LAT, DIVERTED_LON, 70.0), plan()).state();

    RuleOutcome outcome =
        rule.evaluate(state, position(Duration.ofMinutes(2), ON_ROUTE_LAT, ON_ROUTE_LON, 70.0), plan());

    assertThat(outcome.raised()).isEmpty();
    assertThat(outcome.cleared()).isPresent();
    assertThat(outcome.cleared().get().resolution()).isEqualTo(Clearance.BACK_ON_ROUTE);
  }

  @Test
  void sustainedDivergenceIsRaisedAndStampedAtTheFirstStrayFix() {
    ConditionState state = fresh();
    state = rule.evaluate(state, position(Duration.ZERO, ON_ROUTE_LAT, DIVERTED_LON, 60.0), plan()).state();

    RuleOutcome outcome =
        rule.evaluate(state, position(Duration.ofMinutes(20), ON_ROUTE_LAT, DIVERTED_LON, 60.0), plan());

    assertThat(outcome.raised()).isPresent();
    var finding = outcome.raised().get();
    assertThat(finding.type()).isEqualTo(ExceptionType.ROUTE_DEVIATION);
    assertThat(finding.onsetAt()).isEqualTo(T0);
    assertThat(finding.observedValue()).isGreaterThan(12.0);
    assertThat(finding.thresholdValue()).isEqualTo(12.0);
    assertThat(finding.detail()).contains("off the planned route for 20 minutes");
  }

  @Test
  void rejoiningTheCorridorClears() {
    ConditionState state = fresh();
    state = rule.evaluate(state, position(Duration.ZERO, ON_ROUTE_LAT, DIVERTED_LON, 60.0), plan()).state();
    state =
        rule.evaluate(state, position(Duration.ofMinutes(20), ON_ROUTE_LAT, DIVERTED_LON, 60.0), plan())
            .state();

    RuleOutcome outcome =
        rule.evaluate(state, position(Duration.ofMinutes(50), ON_ROUTE_LAT, ON_ROUTE_LON, 60.0), plan());

    assertThat(outcome.cleared()).isPresent();
    assertThat(outcome.state().holding()).isFalse();
  }

  @Test
  void aPoorFixIsNotConsultedAndLeavesNoTrace() {
    ConditionState before = fresh();

    RuleOutcome outcome =
        rule.evaluate(before, position(Duration.ZERO, ON_ROUTE_LAT, DIVERTED_LON, 60.0, 900.0), plan());

    assertThat(outcome.state()).isSameAs(before);
  }

  @Test
  void aShipmentWithNoPlanCannotDeviateFromIt() {
    ConditionState before = fresh();

    RuleOutcome outcome =
        rule.evaluate(before, position(Duration.ZERO, 20.0, 85.0, 60.0), null);

    // Missing reference data must not become a fleet-wide alert storm.
    assertThat(outcome.state()).isSameAs(before);
    assertThat(outcome.raised()).isEmpty();
  }

  @Test
  void theDetailReportsTheWorstOffsetAndNotJustTheLatest() {
    ConditionState state = fresh();
    state = rule.evaluate(state, position(Duration.ZERO, ON_ROUTE_LAT, 79.6, 60.0), plan()).state();

    RuleOutcome outcome =
        rule.evaluate(state, position(Duration.ofMinutes(20), ON_ROUTE_LAT, DIVERTED_LON, 60.0), plan());

    // The reading that tips the tolerance over says nothing about how far the truck actually went.
    assertThat(outcome.raised().orElseThrow().detail()).contains("having reached");
  }
}
