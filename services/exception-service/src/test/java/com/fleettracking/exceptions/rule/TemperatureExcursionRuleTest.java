package com.fleettracking.exceptions.rule;

import static com.fleettracking.exceptions.Fixtures.T0;
import static com.fleettracking.exceptions.Fixtures.bareTerms;
import static com.fleettracking.exceptions.Fixtures.coldChainTerms;
import static com.fleettracking.exceptions.Fixtures.heartbeat;
import static com.fleettracking.exceptions.Fixtures.reading;
import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.ExceptionType;
import com.fleettracking.events.Severity;
import com.fleettracking.exceptions.ExceptionProperties;
import com.fleettracking.exceptions.Fixtures;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TemperatureExcursionRuleTest {

  private static final ExceptionProperties.Temperature SETTINGS =
      new ExceptionProperties.Temperature(Duration.ofMinutes(20), 3.0);

  private final TemperatureExcursionRule rule = new TemperatureExcursionRule(SETTINGS);

  private ConditionState fresh() {
    return ConditionState.initial(Fixtures.SHIPMENT, ExceptionType.TEMPERATURE_EXCURSION);
  }

  @Test
  void aReadingInsideTheBandChangesNothingAtAll() {
    ConditionState before = fresh();

    RuleOutcome outcome = rule.evaluate(before, reading(Duration.ZERO, 4.2, 4.0), coldChainTerms(2, 8, 30));

    assertThat(outcome.raised()).isEmpty();
    assertThat(outcome.cleared()).isEmpty();
    assertThat(outcome.state().holding()).isFalse();
  }

  @Test
  void aStatusThatIsNotATemperatureReadingIsIgnoredWithoutTouchingTheState() {
    ConditionState before = fresh();

    RuleOutcome outcome = rule.evaluate(before, heartbeat(Duration.ZERO), coldChainTerms(2, 8, 30));

    // The same instance, not merely an equal one. Most of what arrives on the status topic is not
    // a reefer reading, and each one must cost nothing -- not a write, not even a cache refresh.
    assertThat(outcome.state()).isSameAs(before);
  }

  @Test
  void oneWarmReadingIsNotAnExcursion() {
    RuleOutcome outcome =
        rule.evaluate(fresh(), reading(Duration.ZERO, 9.5, 4.0), coldChainTerms(2, 8, 30));

    assertThat(outcome.raised()).isEmpty();
    // The clock is running, though, and the onset is this reading.
    assertThat(outcome.state().holding()).isTrue();
    assertThat(outcome.state().since()).isEqualTo(T0);
  }

  @Test
  void staysWarmPastTheStatedToleranceAndIsRaised() {
    ConditionState state = fresh();
    state = rule.evaluate(state, reading(Duration.ZERO, 9.5, 4.0), coldChainTerms(2, 8, 30)).state();
    state = rule.evaluate(state, reading(Duration.ofMinutes(15), 9.8, 4.0), coldChainTerms(2, 8, 30)).state();

    RuleOutcome outcome =
        rule.evaluate(state, reading(Duration.ofMinutes(31), 9.1, 4.0), coldChainTerms(2, 8, 30));

    assertThat(outcome.raised()).isPresent();
    var finding = outcome.raised().get();
    assertThat(finding.type()).isEqualTo(ExceptionType.TEMPERATURE_EXCURSION);
    // Stamped when the temperature left the band, not when the tolerance expired. Using the later
    // instant would report every excursion as starting half an hour after it did.
    assertThat(finding.onsetAt()).isEqualTo(T0);
    assertThat(finding.confirmedAt()).isEqualTo(T0.plus(Duration.ofMinutes(31)));
    assertThat(finding.observedValue()).isEqualTo(9.1);
    assertThat(finding.thresholdValue()).isEqualTo(8.0);
  }

  @Test
  void theCustomersOwnToleranceWinsOverTheDefault() {
    // MediVault's schema carries excursionToleranceMinutes. A load contracted with five minutes of
    // grace must be raised at five minutes, not at the platform's twenty.
    ConditionState state = fresh();
    state = rule.evaluate(state, reading(Duration.ZERO, 9.5, 4.0), coldChainTerms(2, 8, 5)).state();

    RuleOutcome outcome =
        rule.evaluate(state, reading(Duration.ofMinutes(6), 9.5, 4.0), coldChainTerms(2, 8, 5));

    assertThat(outcome.raised()).isPresent();
  }

  @Test
  void aBandFromTheManifestIsCriticalAndTheSetpointFallbackIsOnlyAWarning() {
    ConditionState withBand = fresh();
    withBand = rule.evaluate(withBand, reading(Duration.ZERO, 9.5, 4.0), coldChainTerms(2, 8, 30)).state();
    var contracted =
        rule.evaluate(withBand, reading(Duration.ofMinutes(31), 9.5, 4.0), coldChainTerms(2, 8, 30));

    ConditionState noBand = fresh();
    noBand = rule.evaluate(noBand, reading(Duration.ZERO, 9.5, 4.0), null).state();
    var uncontracted = rule.evaluate(noBand, reading(Duration.ofMinutes(31), 9.5, 4.0), null);

    // Both breached. Only one of them breached something a customer agreed to.
    assertThat(contracted.raised().orElseThrow().severity()).isEqualTo(Severity.CRITICAL);
    assertThat(uncontracted.raised().orElseThrow().severity()).isEqualTo(Severity.WARNING);
  }

  @Test
  void aLoadInsideItsBandButAwayFromItsSetpointIsNotAnExcursion() {
    // The whole reason the band is read from the manifest. A unit set to 4C carrying freight
    // contracted at 2-8C has headroom, and 7.5C is the equipment drifting, not a breach.
    ConditionState state = fresh();
    state = rule.evaluate(state, reading(Duration.ZERO, 7.5, 4.0), coldChainTerms(2, 8, 30)).state();

    RuleOutcome outcome =
        rule.evaluate(state, reading(Duration.ofMinutes(45), 7.5, 4.0), coldChainTerms(2, 8, 30));

    assertThat(outcome.raised()).isEmpty();
    assertThat(outcome.state().holding()).isFalse();
  }

  @Test
  void tooColdIsAnExcursionToo() {
    ConditionState state = fresh();
    state = rule.evaluate(state, reading(Duration.ZERO, -2.0, 4.0), coldChainTerms(2, 8, 10)).state();

    RuleOutcome outcome =
        rule.evaluate(state, reading(Duration.ofMinutes(11), -2.0, 4.0), coldChainTerms(2, 8, 10));

    assertThat(outcome.raised()).isPresent();
    assertThat(outcome.raised().get().thresholdValue()).isEqualTo(2.0);
  }

  @Test
  void comingBackInsideTheBandClearsImmediately() {
    ConditionState state = fresh();
    state = rule.evaluate(state, reading(Duration.ZERO, 9.5, 4.0), coldChainTerms(2, 8, 10)).state();
    state = rule.evaluate(state, reading(Duration.ofMinutes(11), 9.5, 4.0), coldChainTerms(2, 8, 10)).state();

    RuleOutcome outcome =
        rule.evaluate(state, reading(Duration.ofMinutes(20), 5.0, 4.0), coldChainTerms(2, 8, 10));

    assertThat(outcome.cleared()).isPresent();
    assertThat(outcome.cleared().get().resolution()).isEqualTo(Clearance.RECOVERED);
    // And the clock is reset, so a later excursion is a new incident rather than the old one.
    assertThat(outcome.state().holding()).isFalse();
  }

  @Test
  void aReadingOlderThanOneAlreadyAppliedIsRefused() {
    // The mobile feed dumps backlogs out of order after a connectivity gap. An old reading must
    // not walk the excursion clock backwards.
    ConditionState state = fresh();
    state = rule.evaluate(state, reading(Duration.ofMinutes(30), 9.5, 4.0), coldChainTerms(2, 8, 10)).state();

    RuleOutcome outcome =
        rule.evaluate(state, reading(Duration.ofMinutes(5), 4.0, 4.0), coldChainTerms(2, 8, 10));

    assertThat(outcome.state()).isSameAs(state);
    assertThat(outcome.cleared()).isEmpty();
  }

  @Test
  void aReadingWithNoBandAndNoSetpointCannotBeJudged() {
    RuleOutcome outcome = rule.evaluate(fresh(), reading(Duration.ZERO, 9.5, null), bareTerms());

    assertThat(outcome.raised()).isEmpty();
    assertThat(outcome.state().holding()).isFalse();
  }

  @Test
  void theDetailNamesWhereTheBandCameFrom() {
    ConditionState state = fresh();
    state = rule.evaluate(state, reading(Duration.ZERO, 9.5, 4.0), coldChainTerms(2, 8, 10)).state();
    var contracted =
        rule.evaluate(state, reading(Duration.ofMinutes(11), 9.5, 4.0), coldChainTerms(2, 8, 10));

    // Somebody deciding whether to reject a consignment has to know whether the platform is
    // comparing against a contract or against a dial.
    assertThat(contracted.raised().orElseThrow().detail()).contains("the agreed 2.0 to 8.0C band");
  }
}
