package com.fleettracking.exceptions.rule;

import static com.fleettracking.exceptions.Fixtures.T0;
import static com.fleettracking.exceptions.Fixtures.bareTerms;
import static com.fleettracking.exceptions.Fixtures.plan;
import static com.fleettracking.exceptions.Fixtures.windowTerms;
import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.EtaUpdated;
import com.fleettracking.events.ExceptionType;
import com.fleettracking.events.GeoPoint;
import com.fleettracking.events.Severity;
import com.fleettracking.events.ShipmentArrived;
import com.fleettracking.exceptions.ExceptionProperties;
import com.fleettracking.exceptions.Fixtures;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LateArrivalRuleTest {

  private static final ExceptionProperties.LateArrival SETTINGS =
      new ExceptionProperties.LateArrival(Duration.ofMinutes(15));

  private final LateArrivalRule rule = new LateArrivalRule(SETTINGS);

  private static final Instant OPENS = T0.plus(Duration.ofHours(6));
  private static final Instant CLOSES = T0.plus(Duration.ofHours(10));

  /** The final stop on the plan, which is the one the window was booked for. */
  private static final String DELIVERY = Fixtures.BENGALURU.stopId();

  private static EtaUpdated estimate(Duration afterT0, String stopId, Instant estimatedArrival) {
    Instant occurredAt = T0.plus(afterT0);
    return new EtaUpdated(
        "evt-eta-" + occurredAt.toEpochMilli(),
        Fixtures.SHIPMENT,
        occurredAt,
        "evt-pos-" + occurredAt.toEpochMilli(),
        stopId,
        estimatedArrival,
        null,
        180.0,
        0.82);
  }

  private static ShipmentArrived arrival(Duration afterT0, String stopId) {
    Instant occurredAt = T0.plus(afterT0);
    return new ShipmentArrived(
        "evt-arr-" + occurredAt.toEpochMilli(),
        Fixtures.SHIPMENT,
        occurredAt,
        "evt-pos-" + occurredAt.toEpochMilli(),
        stopId,
        new GeoPoint(12.9716, 77.5946),
        null);
  }

  @Test
  void anEstimateComfortablyInsideTheWindowSaysNothingIsWrong() {
    RuleOutcome outcome =
        rule.evaluate(
            estimate(Duration.ofHours(2), DELIVERY, CLOSES.minus(Duration.ofHours(1))),
            windowTerms(OPENS, CLOSES),
            plan());

    // It clears rather than staying silent, which is what makes a projection that came back inside
    // close the exception it opened.
    assertThat(outcome.raised()).isEmpty();
    assertThat(outcome.cleared()).isPresent();
    assertThat(outcome.cleared().get().resolution()).isEqualTo(Clearance.BACK_WITHIN_WINDOW);
  }

  @Test
  void anEstimateJustPastTheDeadlineIsInsideTheGrace() {
    // An estimate a few minutes over will very probably drift back within a fix or two. Raising on
    // it produces an exception that clears itself before anybody has read it.
    RuleOutcome outcome =
        rule.evaluate(
            estimate(Duration.ofHours(2), DELIVERY, CLOSES.plus(Duration.ofMinutes(8))),
            windowTerms(OPENS, CLOSES),
            plan());

    assertThat(outcome.raised()).isEmpty();
  }

  @Test
  void anEstimateWellPastTheDeadlineIsRaisedAsAWarning() {
    RuleOutcome outcome =
        rule.evaluate(
            estimate(Duration.ofHours(2), DELIVERY, CLOSES.plus(Duration.ofMinutes(95))),
            windowTerms(OPENS, CLOSES),
            plan());

    assertThat(outcome.raised()).isPresent();
    var finding = outcome.raised().get();
    assertThat(finding.type()).isEqualTo(ExceptionType.LATE_ARRIVAL);
    // Still a prediction: the truck is fine, it is just not going to make it.
    assertThat(finding.severity()).isEqualTo(Severity.WARNING);
    assertThat(finding.stopId()).isEqualTo(DELIVERY);
    assertThat(finding.observedValue()).isEqualTo(95.0);
    assertThat(finding.detail()).contains("Projected to arrive 95 minutes after");
  }

  @Test
  void anEstimateForAnIntermediateStopIsNotJudgedAgainstTheDeliveryWindow() {
    // The window is a commitment about the dock, not about every stop on the way to it.
    RuleOutcome outcome =
        rule.evaluate(
            estimate(Duration.ofHours(2), Fixtures.KURNOOL.stopId(), CLOSES.plus(Duration.ofHours(4))),
            windowTerms(OPENS, CLOSES),
            plan());

    assertThat(outcome.raised()).isEmpty();
    assertThat(outcome.cleared()).isEmpty();
  }

  @Test
  void aCustomerWhoBookedNoWindowCannotBeLate() {
    // A part-load and a parcel are delivered when they get there. Inventing a deadline from the
    // itinerary would be judging a carrier against a commitment nobody made.
    RuleOutcome outcome =
        rule.evaluate(
            estimate(Duration.ofHours(2), DELIVERY, T0.plus(Duration.ofDays(3))),
            bareTerms(),
            plan());

    assertThat(outcome.raised()).isEmpty();
    assertThat(outcome.cleared()).isEmpty();
  }

  @Test
  void arrivingInTimeClearsWhateverTheEstimatesSaidAlongTheWay() {
    RuleOutcome outcome =
        rule.evaluate(
            arrival(Duration.ofHours(9), DELIVERY), windowTerms(OPENS, CLOSES), plan());

    assertThat(outcome.cleared()).isPresent();
    assertThat(outcome.cleared().get().resolution()).isEqualTo(Clearance.ARRIVED_ON_TIME);
    assertThat(outcome.raised()).isEmpty();
  }

  @Test
  void arrivingLateBothRaisesAndCloses() {
    RuleOutcome outcome =
        rule.evaluate(
            arrival(Duration.ofHours(11), DELIVERY), windowTerms(OPENS, CLOSES), plan());

    // Raised so the breach exists on the record even when no estimate ever predicted it, and
    // closed because there is nothing left to watch.
    assertThat(outcome.raised()).isPresent();
    assertThat(outcome.cleared()).isPresent();

    var finding = outcome.raised().get();
    // The prediction became fact, so it is no longer a warning.
    assertThat(finding.severity()).isEqualTo(Severity.CRITICAL);
    // Stamped at the moment the window closed: that is when the shipment became late, and it is a
    // fact about the booking rather than about whichever event revealed it.
    assertThat(finding.onsetAt()).isEqualTo(CLOSES);
    assertThat(finding.observedValue()).isEqualTo(60.0);
    assertThat(outcome.cleared().get().resolution()).isEqualTo(Clearance.ARRIVED_LATE);
  }

  @Test
  void thisRuleKeepsNoState() {
    // It is the one rule with nothing to accumulate: whether a projection is past a deadline is
    // decided entirely by the event in hand and the window on the manifest.
    RuleOutcome outcome =
        rule.evaluate(
            estimate(Duration.ofHours(2), DELIVERY, CLOSES.plus(Duration.ofHours(2))),
            windowTerms(OPENS, CLOSES),
            plan());

    assertThat(outcome.state()).isNull();
  }
}
