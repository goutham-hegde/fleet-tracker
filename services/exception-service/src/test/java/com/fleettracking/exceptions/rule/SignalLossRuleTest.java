package com.fleettracking.exceptions.rule;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.ExceptionType;
import com.fleettracking.events.Severity;
import com.fleettracking.exceptions.ExceptionProperties;
import com.fleettracking.exceptions.incident.Finding;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two-clock rule.
 *
 * <p>These tests are mostly about the second clock, because the first one is obvious and the second
 * one is the whole reason this rule does not flood on startup.
 */
class SignalLossRuleTest {

  private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");
  private static final ExceptionProperties.SignalLoss SETTINGS =
      new ExceptionProperties.SignalLoss(Duration.ofMinutes(30), Duration.ofSeconds(10));

  /** Wall-clock time under the test's control, so the catch-up grace can be stepped past. */
  private static final class MovableClock extends Clock {
    private Instant now = Instant.parse("2026-09-01T12:00:00Z");

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }

  private final MovableClock clock = new MovableClock();
  private final SignalLossRule rule = new SignalLossRule(SETTINGS, clock);

  /** Nothing is cold chain unless a test says so. */
  private static final java.util.function.Predicate<String> NO_COLD_CHAIN = id -> false;

  @Test
  void aShipmentNeverSeenIsNotWatched() {
    // A service that has just started genuinely does not know whether a truck it has never heard
    // from broke down or was simply never assigned to its partitions.
    assertThat(rule.sweep(NO_COLD_CHAIN)).isEmpty();
    assertThat(rule.watching()).isZero();
  }

  @Test
  void aShipmentReportingNormallyIsNotSilent() {
    rule.seen("SHP-A", T0, "evt-1");
    rule.seen("SHP-A", T0.plus(Duration.ofMinutes(5)), "evt-2");
    clock.advance(Duration.ofMinutes(1));

    assertThat(rule.sweep(NO_COLD_CHAIN)).isEmpty();
  }

  @Test
  void aShipmentTheFleetHasOutrunByMoreThanTheThresholdIsReported() {
    rule.seen("SHP-QUIET", T0, "evt-last");
    // Another truck carries the fleet's clock forward. That is where the current event time comes
    // from: there is no event from the silent shipment to read one off.
    rule.seen("SHP-BUSY", T0.plus(Duration.ofMinutes(45)), "evt-busy");
    clock.advance(Duration.ofMinutes(1));

    List<Finding> found = rule.sweep(NO_COLD_CHAIN);

    assertThat(found).hasSize(1);
    Finding finding = found.get(0);
    assertThat(finding.type()).isEqualTo(ExceptionType.SIGNAL_LOSS);
    assertThat(finding.shipmentId()).isEqualTo("SHP-QUIET");
    // Stamped at the last thing heard, so the incident says the silence began when the messages
    // stopped rather than when the sweep noticed.
    assertThat(finding.onsetAt()).isEqualTo(T0);
    assertThat(finding.causedBy()).isEqualTo("evt-last");
    // Deliberately null, both. The evidence is that nothing was measured at all.
    assertThat(finding.observedValue()).isNull();
    assertThat(finding.thresholdValue()).isNull();
  }

  @Test
  void aStartupReplayDoesNotReportTheWholeFleetAsSilent() {
    // The failure this rule is built to avoid. A consumer working through an hour of retained
    // topic sees event times an hour apart within a few milliseconds of each other, and by event
    // time alone every shipment but the newest looks like it has stopped reporting.
    rule.seen("SHP-A", T0, "evt-a");
    rule.seen("SHP-B", T0.plus(Duration.ofMinutes(20)), "evt-b");
    rule.seen("SHP-C", T0.plus(Duration.ofHours(3)), "evt-c");

    // No wall-clock time has passed: the replay is still in progress.
    assertThat(rule.sweep(NO_COLD_CHAIN)).isEmpty();
  }

  @Test
  void onceTheReplayIsOverTheGenuinelySilentAreStillFound() {
    rule.seen("SHP-A", T0, "evt-a");
    rule.seen("SHP-C", T0.plus(Duration.ofHours(3)), "evt-c");

    assertThat(rule.sweep(NO_COLD_CHAIN)).isEmpty();

    // The catch-up grace is three sweep intervals. Past it, the shipment has genuinely had a
    // chance to be heard from and has not been.
    clock.advance(Duration.ofSeconds(31));

    assertThat(rule.sweep(NO_COLD_CHAIN)).hasSize(1);
  }

  @Test
  void aSilenceIsReportedOnceRatherThanOnEverySweep() {
    rule.seen("SHP-QUIET", T0, "evt-last");
    rule.seen("SHP-BUSY", T0.plus(Duration.ofMinutes(45)), "evt-busy");
    clock.advance(Duration.ofMinutes(1));

    assertThat(rule.sweep(NO_COLD_CHAIN)).hasSize(1);
    // The incident service would suppress a repeat anyway, but it would pay a MongoDB query per
    // silent shipment per sweep to reach an answer this object already knows.
    assertThat(rule.sweep(NO_COLD_CHAIN)).isEmpty();
  }

  @Test
  void thefirstMessageBackClearsImmediatelyRatherThanWaitingForASweep() {
    rule.seen("SHP-QUIET", T0, "evt-last");
    rule.seen("SHP-BUSY", T0.plus(Duration.ofMinutes(45)), "evt-busy");
    clock.advance(Duration.ofMinutes(1));
    rule.sweep(NO_COLD_CHAIN);

    Clearance clearance =
        rule.seen("SHP-QUIET", T0.plus(Duration.ofMinutes(50)), "evt-back");

    assertThat(clearance).isNotNull();
    assertThat(clearance.type()).isEqualTo(ExceptionType.SIGNAL_LOSS);
    assertThat(clearance.resolution()).isEqualTo(Clearance.REPORTING_AGAIN);
  }

  @Test
  void aShipmentThatWasNeverReportedSilentClearsNothingWhenItReports() {
    rule.seen("SHP-A", T0, "evt-1");

    assertThat(rule.seen("SHP-A", T0.plus(Duration.ofMinutes(1)), "evt-2")).isNull();
  }

  @Test
  void aCompletedShipmentStopsBeingWatched() {
    // Without this, every delivered load becomes a permanent signal loss a few minutes after the
    // driver switches off -- the most common way an alerting system fills with things nobody can
    // action.
    rule.seen("SHP-DONE", T0, "evt-1");
    rule.completed("SHP-DONE", T0.plus(Duration.ofMinutes(1)), "evt-arrival");
    rule.seen("SHP-BUSY", T0.plus(Duration.ofHours(4)), "evt-busy");
    clock.advance(Duration.ofMinutes(1));

    assertThat(rule.sweep(NO_COLD_CHAIN)).isEmpty();
    assertThat(rule.watching()).isEqualTo(1);
  }

  @Test
  void aCompletedShipmentThatWasReportedSilentIsClearedAsCompletedNotAsRecovered() {
    rule.seen("SHP-DONE", T0, "evt-1");
    rule.seen("SHP-BUSY", T0.plus(Duration.ofMinutes(45)), "evt-busy");
    clock.advance(Duration.ofMinutes(1));
    rule.sweep(NO_COLD_CHAIN);

    Clearance clearance =
        rule.completed("SHP-DONE", T0.plus(Duration.ofMinutes(50)), "evt-arrival");

    assertThat(clearance).isNotNull();
    // Its silence is expected, not resolved, and the resolution has to say which.
    assertThat(clearance.resolution()).isEqualTo(Clearance.SHIPMENT_COMPLETED);
  }

  @Test
  void losingSightOfAReeferIsCritical() {
    rule.seen("SHP-COLD", T0, "evt-1");
    rule.seen("SHP-BUSY", T0.plus(Duration.ofMinutes(45)), "evt-busy");
    clock.advance(Duration.ofMinutes(1));

    List<Finding> found = rule.sweep("SHP-COLD"::equals);

    assertThat(found).hasSize(1);
    assertThat(found.get(0).severity()).isEqualTo(Severity.CRITICAL);
  }

  @Test
  void aDelayedMessageDoesNotMakeAHealthyTruckLookSilent() {
    // Three of the four feeds are delayed by design. An EDI 214 interchange arriving now describes
    // something that happened before the batch window carried it, and letting it overwrite the
    // last-seen instant would move a healthy shipment's clock backwards.
    rule.seen("SHP-A", T0.plus(Duration.ofMinutes(50)), "evt-telematics");
    rule.seen("SHP-A", T0, "evt-edi-batch");
    rule.seen("SHP-BUSY", T0.plus(Duration.ofMinutes(55)), "evt-busy");
    clock.advance(Duration.ofMinutes(1));

    assertThat(rule.sweep(NO_COLD_CHAIN)).isEmpty();
  }

  @Test
  void aDelayedMessageStillCountsAsHavingHeardFromTheShipment() {
    // The event time does not move, because nothing newer happened. The wall clock does, because
    // something newer arrived -- and that is what the catch-up grace is measured against.
    rule.seen("SHP-QUIET", T0, "evt-1");
    rule.seen("SHP-BUSY", T0.plus(Duration.ofMinutes(45)), "evt-busy");
    clock.advance(Duration.ofMinutes(1));
    assertThat(rule.sweep(NO_COLD_CHAIN)).hasSize(1);

    // Delayed paperwork about an even earlier moment. The shipment has been heard from, so the
    // report is withdrawn -- and if it really is silent, the next sweep says so again.
    rule.seen("SHP-QUIET", T0.minus(Duration.ofMinutes(5)), "evt-late-edi");
    clock.advance(Duration.ofMinutes(1));

    assertThat(rule.sweep(NO_COLD_CHAIN)).hasSize(1);
  }

  @Test
  void aStragglingFixAfterDeliveryDoesNotPutTheLoadBackUnderWatch() {
    // The gap that a single-threaded reading of this class does not show. Arrivals come from the
    // derived topic and positions from the position topic, on different listener threads and at
    // different offsets, so the last fixes of the final leg routinely land *after* the arrival that
    // concluded the shipment. Merely removing the load from the watch list lets each of those put
    // it straight back -- and since it is delivered, nothing more is ever reported and it is
    // announced as silent half an hour later.
    //
    // Found in S13 by a live run that ended with three open signal losses against loads that had
    // all been delivered, which is exactly what completion exists to prevent.
    rule.seen("SHP-DONE", T0, "evt-1");
    rule.completed("SHP-DONE", T0.plus(Duration.ofMinutes(1)), "evt-arrival");

    rule.seen("SHP-DONE", T0.plus(Duration.ofSeconds(70)), "evt-trailing-fix");

    assertThat(rule.watching()).isZero();

    // And it stays gone, however far the rest of the fleet's day advances.
    rule.seen("SHP-BUSY", T0.plus(Duration.ofHours(6)), "evt-busy");
    clock.advance(Duration.ofMinutes(1));

    assertThat(rule.sweep(NO_COLD_CHAIN)).isEmpty();
  }

  @Test
  void aStragglingFixAfterDeliveryStillAdvancesTheFleetsClock() {
    // The load is finished, but the message is still evidence of how far the fleet's day has got --
    // and that watermark is what every other shipment is measured against.
    rule.completed("SHP-DONE", T0, "evt-arrival");
    rule.seen("SHP-DONE", T0.plus(Duration.ofHours(3)), "evt-trailing-fix");

    assertThat(rule.watermark()).isEqualTo(T0.plus(Duration.ofHours(3)));
  }

  @Test
  void anOutOfOrderEventDoesNotDragTheFleetsClockBackwards() {
    rule.seen("SHP-A", T0.plus(Duration.ofHours(2)), "evt-new");
    rule.seen("SHP-B", T0, "evt-old");

    assertThat(rule.watermark()).isEqualTo(T0.plus(Duration.ofHours(2)));
  }
}
