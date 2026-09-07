package com.fleettracking.exceptions.rule;

import com.fleettracking.events.ExceptionType;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.Severity;
import com.fleettracking.exceptions.ExceptionProperties;
import com.fleettracking.exceptions.incident.Finding;
import com.fleettracking.reference.Itinerary;
import java.time.Duration;

/**
 * A vehicle that is not where its plan says it should be going.
 *
 * <h2>The rule most easily fooled by a bad fix, and what actually defends it</h2>
 *
 * <p>{@code ExceptionType} says so in its own documentation, and it is worth being precise about
 * why. Every other rule tolerates a wrong coordinate reasonably well: a spurious position rarely
 * looks stationary, and a reefer reading has no position at all. This one converts a wrong
 * coordinate directly into its own trigger — a reflected signal in a city puts a truck a kilometre
 * sideways, and a receiver reacquiring after a tunnel can put it several.
 *
 * <p>Three defences, in increasing order of how much work they do:
 *
 * <ul>
 *   <li><b>The accuracy gate.</b> A fix that reports poor accuracy is not consulted. Cheap, and it
 *       catches the honest half of the problem — a receiver that knows it is struggling says so.
 *   <li><b>A generous corridor.</b> Wide enough that ordinary road geometry never escapes it. See
 *       {@link Corridor} for why this is a property of having no route engine rather than a lazy
 *       threshold.
 *   <li><b>Duration.</b> The one that does the real work. A bad fix is a single reading; a diversion
 *       is a quarter of an hour of readings. Nothing about a wrong coordinate persists, and
 *       persistence is exactly what is being measured.
 * </ul>
 *
 * <p>The third is why this rule shares {@link ConditionState} with the other two sustained rules
 * rather than counting consecutive bad fixes. Counting fixes would mean the rule behaved differently
 * for a telematics unit reporting every ten seconds and a phone reporting every two minutes — the
 * same trap the ETA calculation avoids by expressing its smoothing as a half-life in event time
 * rather than as a weight per message.
 */
public class RouteDeviationRule {

  private final double corridorKm;
  private final Duration threshold;
  private final double accuracyGateMeters;

  public RouteDeviationRule(ExceptionProperties.RouteDeviation settings) {
    this.corridorKm = settings.corridorKm();
    this.threshold = settings.threshold();
    this.accuracyGateMeters = settings.accuracyGateMeters();
  }

  /** Applies one position fix against the shipment's plan. */
  public RuleOutcome evaluate(ConditionState state, PositionEvent event, Itinerary plan) {
    if (plan == null || plan.stops() == null || plan.stops().size() < 2) {
      // No plan, or a plan with no legs, means no corridor to be outside of. A load nobody
      // scheduled cannot deviate from a route it was never given, and saying otherwise would turn
      // missing reference data into a fleet-wide alert storm.
      return RuleOutcome.nothing(state);
    }
    if (event.accuracyMeters() != null && event.accuracyMeters() > accuracyGateMeters) {
      return RuleOutcome.nothing(state);
    }
    if (!state.isNewerThanApplied(event.occurredAt())) {
      return RuleOutcome.nothing(state);
    }

    double offPlanKm = Corridor.kilometersOffPlan(event.position(), plan.stops());

    if (offPlanKm <= corridorKm) {
      if (!state.holding()) {
        return RuleOutcome.nothing(state.quiet(event.occurredAt()));
      }
      return RuleOutcome.clear(
          state.ending(event.occurredAt()),
          new Clearance(
              ExceptionType.ROUTE_DEVIATION,
              event.shipmentId(),
              null,
              event.occurredAt(),
              event.eventId(),
              Clearance.BACK_ON_ROUTE));
    }

    ConditionState updated =
        state.holding()
            ? state.continuing(event.occurredAt(), offPlanKm)
            : state.beginning(
                event.occurredAt(),
                offPlanKm,
                event.position().latitude(),
                event.position().longitude());

    Duration held = updated.heldFor(event.occurredAt());
    if (held.compareTo(threshold) < 0) {
      return RuleOutcome.nothing(updated);
    }

    return RuleOutcome.raise(
        updated,
        new Finding(
            ExceptionType.ROUTE_DEVIATION,
            event.shipmentId(),
            null,
            updated.since(),
            event.occurredAt(),
            event.eventId(),
            Severity.WARNING,
            detail(offPlanKm, updated.worst(), held),
            offPlanKm,
            corridorKm));
  }

  private static String detail(double offPlanKm, Double worst, Duration held) {
    String peak =
        worst == null || worst <= offPlanKm
            ? ""
            : String.format(", having reached %.0f km", worst);
    return String.format(
        "%.0f km off the planned route for %d minutes%s.", offPlanKm, held.toMinutes(), peak);
  }
}
