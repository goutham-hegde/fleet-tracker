package com.fleettracking.exceptions.rule;

import com.fleettracking.events.EtaUpdated;
import com.fleettracking.events.ExceptionType;
import com.fleettracking.events.Severity;
import com.fleettracking.events.ShipmentArrived;
import com.fleettracking.exceptions.ExceptionProperties;
import com.fleettracking.exceptions.incident.Finding;
import com.fleettracking.exceptions.manifest.DeliveryWindow;
import com.fleettracking.exceptions.manifest.SlaTerms;
import com.fleettracking.reference.Itinerary;
import com.fleettracking.reference.ScheduledStop;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

/**
 * Will this load miss the slot the consignee booked — and did it?
 *
 * <h2>The only rule that can fire before anything has gone wrong</h2>
 *
 * <p>The other four watch for a condition that already exists: a warm box, a stopped truck, a silent
 * device. This one watches a <em>prediction</em>. It reads the revised estimates the tracking
 * processor publishes and compares them against a deadline on the manifest, so an exception can be
 * raised while the truck is still four hours out and everything about it is working perfectly.
 *
 * <p>That is the useful kind of alert — the one somebody can still do something about — and it is
 * only possible because M3 built an ETA that converges and does not thrash. An estimate that
 * flickered by twenty minutes on every fix would produce an exception that raised and cleared all
 * afternoon.
 *
 * <h2>A deadline nobody set cannot be missed</h2>
 *
 * <p>This rule is silent for any shipment whose manifest carries no delivery window, and that is
 * correct rather than a gap. Of the four seeded customers only the retail replenishment books a
 * dock slot; a part-load and a parcel are delivered when they get there. Inventing a deadline from
 * the itinerary would mean the platform judging a carrier against a commitment nobody made.
 *
 * <p>It follows that lateness is a property of the <em>customer contract</em>, which is the same
 * argument the temperature rule makes about bands. Both are the answer to "why is a manifest more
 * than paperwork" — the manifest is where the SLA actually lives.
 *
 * <h2>The window belongs to the delivery, so only the last stop counts</h2>
 *
 * <p>A booked dock slot is a commitment about arriving at the dock. Intermediate stops on a
 * multi-drop run have no window on the manifest, and estimates for them are ignored. The tracking
 * processor only estimates the <em>next</em> stop anyway, so in practice this rule wakes up on the
 * final leg — which is also when the estimate is most trustworthy.
 *
 * <h2>Both halves of "late", and both are the same incident</h2>
 *
 * <ul>
 *   <li>A revised estimate lands past the deadline: raise a {@link Severity#WARNING}. It is still a
 *       prediction, and predictions come back.
 *   <li>A later estimate comes back inside: clear it. This is the case that makes the rule worth
 *       having rather than a nightly report.
 *   <li>The shipment actually arrives past the deadline: the prediction became fact. The incident is
 *       restated as {@link Severity#CRITICAL} with what actually happened, and then closed — because
 *       there is nothing left to watch, not because anything recovered.
 *   <li>It arrives in time: clear, whatever the estimates said along the way.
 * </ul>
 */
public class LateArrivalRule {

  private final Duration grace;

  public LateArrivalRule(ExceptionProperties.LateArrival settings) {
    this.grace = settings.grace();
  }

  /** Applies a revised estimate. */
  public RuleOutcome evaluate(EtaUpdated event, SlaTerms terms, Itinerary plan) {
    Optional<Deadline> deadline = deadlineFor(terms, plan, event.stopId());
    if (deadline.isEmpty()) {
      return RuleOutcome.nothing(null);
    }
    DeliveryWindow window = deadline.get().window();
    Instant projected = event.estimatedArrival();

    if (!isPastGrace(window, projected)) {
      return RuleOutcome.clear(
          null,
          new Clearance(
              ExceptionType.LATE_ARRIVAL,
              event.shipmentId(),
              event.stopId(),
              event.occurredAt(),
              event.eventId(),
              Clearance.BACK_WITHIN_WINDOW));
    }

    Duration late = window.lateness(projected);
    return RuleOutcome.raise(
        null,
        new Finding(
            ExceptionType.LATE_ARRIVAL,
            event.shipmentId(),
            event.stopId(),
            // The onset is when this became true, which for a projection is the fix that produced
            // it. A later estimate that is still late restates the same incident rather than
            // opening another, because the incident service matches on type, shipment and stop.
            event.occurredAt(),
            event.occurredAt(),
            event.eventId(),
            Severity.WARNING,
            projectedDetail(late, window, event),
            (double) late.toMinutes(),
            (double) grace.toMinutes()));
  }

  /** Applies an actual arrival. */
  public RuleOutcome evaluate(ShipmentArrived event, SlaTerms terms, Itinerary plan) {
    Optional<Deadline> deadline = deadlineFor(terms, plan, event.stopId());
    if (deadline.isEmpty()) {
      return RuleOutcome.nothing(null);
    }
    DeliveryWindow window = deadline.get().window();

    if (!window.isLate(event.occurredAt())) {
      return RuleOutcome.clear(
          null,
          new Clearance(
              ExceptionType.LATE_ARRIVAL,
              event.shipmentId(),
              event.stopId(),
              event.occurredAt(),
              event.eventId(),
              Clearance.ARRIVED_ON_TIME));
    }

    Duration late = window.lateness(event.occurredAt());

    // Raise before clearing, so that the incident exists even when no estimate ever predicted it --
    // a truck delayed in the last twenty minutes of a run is late with nothing having warned about
    // it. When one was already open, this restates it at the higher severity and with what actually
    // happened, and the clear that follows closes it.
    Finding arrived =
        new Finding(
            ExceptionType.LATE_ARRIVAL,
            event.shipmentId(),
            event.stopId(),
            // Stamped at the moment the window closed. That is when the shipment became late, and
            // it is a fact about the booking rather than about whichever event revealed it.
            window.closesAt(),
            event.occurredAt(),
            event.eventId(),
            Severity.CRITICAL,
            String.format(
                "Arrived %d minutes after the delivery window closed.", late.toMinutes()),
            (double) late.toMinutes(),
            0.0);

    Clearance closed =
        new Clearance(
            ExceptionType.LATE_ARRIVAL,
            event.shipmentId(),
            event.stopId(),
            event.occurredAt(),
            event.eventId(),
            Clearance.ARRIVED_LATE);

    return RuleOutcome.raiseThenClear(null, arrived, closed);
  }

  /**
   * The window this stop is held to, if it is the stop the window was booked for.
   *
   * <p>Empty for an intermediate stop, for a customer who booked nothing, and for a shipment with no
   * itinerary — in every case because there is no commitment to compare against, not because
   * anything is wrong.
   */
  private Optional<Deadline> deadlineFor(SlaTerms terms, Itinerary plan, String stopId) {
    if (terms == null || plan == null || stopId == null) {
      return Optional.empty();
    }
    Optional<DeliveryWindow> window = terms.delivery();
    if (window.isEmpty()) {
      return Optional.empty();
    }
    return finalStop(plan)
        .filter(stop -> stop.stopId().equals(stopId))
        .map(stop -> new Deadline(stop, window.get()));
  }

  /** The last stop on the plan, which is the delivery the window was booked for. */
  private static Optional<ScheduledStop> finalStop(Itinerary plan) {
    if (plan.stops() == null || plan.stops().isEmpty()) {
      return Optional.empty();
    }
    return plan.stops().stream().max(Comparator.comparingInt(ScheduledStop::seq));
  }

  /**
   * Whether a projection is far enough past the deadline to be worth saying.
   *
   * <p>The grace period is not politeness. An estimate one minute past a deadline will very
   * probably drift back inside within a fix or two, and raising on it produces an exception that
   * clears itself before anybody has read it — noise that trains people to ignore the channel.
   */
  private boolean isPastGrace(DeliveryWindow window, Instant projected) {
    return projected.isAfter(window.closesAt().plus(grace));
  }

  private static String projectedDetail(Duration late, DeliveryWindow window, EtaUpdated event) {
    String confidence =
        event.confidence() == null
            ? ""
            : String.format(" (confidence %.0f%%)", event.confidence() * 100);
    String remaining =
        event.remainingKm() == null
            ? ""
            : String.format(", %.0f km still to run", event.remainingKm());
    return String.format(
        "Projected to arrive %d minutes after the delivery window closes at %s%s%s.",
        late.toMinutes(), window.closesAt(), remaining, confidence);
  }

  /** A stop and the window booked for it. */
  private record Deadline(ScheduledStop stop, DeliveryWindow window) {}
}
