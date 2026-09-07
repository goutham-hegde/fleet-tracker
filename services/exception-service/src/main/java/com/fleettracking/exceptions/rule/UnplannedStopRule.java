package com.fleettracking.exceptions.rule;

import com.fleettracking.events.ExceptionType;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.Severity;
import com.fleettracking.exceptions.ExceptionProperties;
import com.fleettracking.exceptions.incident.Finding;
import com.fleettracking.reference.Distance;
import com.fleettracking.reference.Itinerary;
import com.fleettracking.reference.ScheduledStop;
import java.time.Duration;

/**
 * A truck that has stopped moving somewhere it was never supposed to stop.
 *
 * <h2>Two questions, and the second is the one that needs reference data</h2>
 *
 * <p>"Is it stationary" is arithmetic on the reported speed. "Is it somewhere it should be" needs
 * the plan, which is why this rule reads the same scheduled stops the geofencer does. Without the
 * second question the rule would fire at every loading dock, every delivery and every scheduled
 * break — the places a truck is most reliably stationary — and would be useless.
 *
 * <p>The margin around each stop is deliberately wider than the geofence itself. The geofence radius
 * is drawn to answer "has it arrived", and a truck queueing on the approach road to a busy yard has
 * not arrived. It has also not made an unscheduled stop, and reporting it as one would generate an
 * exception for the single most ordinary event in freight.
 *
 * <h2>Zero is not stopped</h2>
 *
 * <p>A parked truck does not report zero. Its position wanders within the receiver's noise, so the
 * speed derived from it wanders too, and a threshold of exactly zero would see a stationary vehicle
 * as permanently in motion. A few km/h is below anything a moving truck sustains and above what
 * noise produces.
 *
 * <h2>The severity depends on what is in the trailer</h2>
 *
 * <p>The same breakdown is a delay for a pallet of dry goods and a countdown for a reefer, whose
 * temperature is now being held by a unit running off its own fuel with nobody watching. This is the
 * clearest case in the platform for severity being a property of the incident rather than of the
 * rule, and it is decided by whether the customer committed to a temperature band — not by the
 * customer's name, and not by the freight mode.
 */
public class UnplannedStopRule {

  private final double movingSpeedKph;
  private final Duration threshold;
  private final double stopMarginRatio;
  private final double accuracyGateMeters;

  public UnplannedStopRule(ExceptionProperties.UnplannedStop settings) {
    this.movingSpeedKph = settings.movingSpeedKph();
    this.threshold = settings.threshold();
    this.stopMarginRatio = settings.stopMarginRatio();
    this.accuracyGateMeters = settings.accuracyGateMeters();
  }

  /**
   * Applies one position fix.
   *
   * @param coldChain whether this load's customer committed to a temperature band, which is what
   *     decides how serious a halt is
   */
  public RuleOutcome evaluate(
      ConditionState state, PositionEvent event, Itinerary plan, boolean coldChain) {

    if (event.speedKph() == null) {
      // A fix with no speed cannot answer the first question. Deriving one from the previous
      // position is possible and is deliberately not done: it would be a second, worse speed
      // estimate living beside the one the ETA calculation already maintains.
      return RuleOutcome.nothing(state);
    }
    if (event.accuracyMeters() != null && event.accuracyMeters() > accuracyGateMeters) {
      // A poor fix is not consulted, and leaves no trace -- the same defence the geofencer uses.
      // Leaving no trace matters: a later trustworthy fix bearing an earlier instant must still be
      // considered, which it would not be if this one had advanced the clock.
      return RuleOutcome.nothing(state);
    }
    if (!state.isNewerThanApplied(event.occurredAt())) {
      return RuleOutcome.nothing(state);
    }

    boolean moving = event.speedKph() >= movingSpeedKph;
    double metersToNearestStop = metersToNearestStop(event, plan);
    boolean somewhereItShouldBe = isAtAPlannedStop(event, plan);

    if (moving || somewhereItShouldBe) {
      if (!state.holding()) {
        return RuleOutcome.nothing(state.quiet(event.occurredAt()));
      }
      return RuleOutcome.clear(
          state.ending(event.occurredAt()),
          new Clearance(
              ExceptionType.UNPLANNED_STOP,
              event.shipmentId(),
              null,
              event.occurredAt(),
              event.eventId(),
              moving ? Clearance.RESUMED : Clearance.RECOVERED));
    }

    double stoppedKm = metersToNearestStop / 1000.0;
    ConditionState updated =
        state.holding()
            ? state.continuing(event.occurredAt(), stoppedKm)
            : state.beginning(
                event.occurredAt(),
                stoppedKm,
                event.position().latitude(),
                event.position().longitude());

    Duration held = updated.heldFor(event.occurredAt());
    if (held.compareTo(threshold) < 0) {
      return RuleOutcome.nothing(updated);
    }

    return RuleOutcome.raise(
        updated,
        new Finding(
            ExceptionType.UNPLANNED_STOP,
            event.shipmentId(),
            // No stop id, and that is the whole point of the rule: it fires precisely where there
            // is no stop to name.
            null,
            updated.since(),
            event.occurredAt(),
            event.eventId(),
            coldChain ? Severity.CRITICAL : Severity.WARNING,
            detail(held, stoppedKm, coldChain),
            (double) held.toMinutes(),
            (double) threshold.toMinutes()));
  }

  /**
   * Whether the vehicle is close enough to a scheduled stop to count as being there.
   *
   * <p>A plan with no stops -- a load nobody scheduled -- answers false, so a stationary truck with
   * no itinerary is still reported. That is the right way round: an unplanned load sitting still is
   * more suspicious than a planned one, not less.
   */
  private boolean isAtAPlannedStop(PositionEvent event, Itinerary plan) {
    if (plan == null || plan.stops() == null) {
      return false;
    }
    for (ScheduledStop stop : plan.stops()) {
      double meters = Distance.metersBetween(event.position(), stop.location());
      if (meters <= stop.radiusMeters() * stopMarginRatio) {
        return true;
      }
    }
    return false;
  }

  private static double metersToNearestStop(PositionEvent event, Itinerary plan) {
    if (plan == null || plan.stops() == null || plan.stops().isEmpty()) {
      return Double.NaN;
    }
    double nearest = Double.MAX_VALUE;
    for (ScheduledStop stop : plan.stops()) {
      nearest = Math.min(nearest, Distance.metersBetween(event.position(), stop.location()));
    }
    return nearest;
  }

  private static String detail(Duration held, double kmFromNearestStop, boolean coldChain) {
    String where =
        Double.isNaN(kmFromNearestStop)
            ? "with no scheduled stops on file"
            : String.format("%.0f km from the nearest scheduled stop", kmFromNearestStop);
    String cargo = coldChain ? " The load is temperature-controlled." : "";
    return "Stationary for " + held.toMinutes() + " minutes " + where + "." + cargo;
  }
}
