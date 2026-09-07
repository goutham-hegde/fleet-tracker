package com.fleettracking.exceptions.rule;

import com.fleettracking.events.EtaUpdated;
import com.fleettracking.events.ExceptionType;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.ShipmentArrived;
import com.fleettracking.events.StatusEvent;
import com.fleettracking.exceptions.incident.Finding;
import com.fleettracking.exceptions.incident.IncidentService;
import com.fleettracking.exceptions.manifest.ManifestLookup;
import com.fleettracking.exceptions.manifest.SlaTerms;
import com.fleettracking.reference.Itinerary;
import com.fleettracking.reference.ItineraryStore;
import java.util.List;
import java.util.Optional;

/**
 * Runs the rules against an event and does what they conclude.
 *
 * <p>The rules are pure and this is where the world gets touched: reference data is read, state is
 * loaded and saved, and findings become events on a topic. Keeping the two apart is what lets every
 * rule be tested by calling it and asserting on the answer, with no broker and no database in the
 * way — and it means the ordering guarantees live in one file rather than in five.
 *
 * <h2>One lookup, several rules</h2>
 *
 * <p>Both position rules need the itinerary and both need to know whether the load is
 * temperature-controlled. Reading either twice per fix would double the cost of the busiest path in
 * the service for no benefit, so each is fetched once here and handed to both — the same reasoning
 * that has the tracking processor pass its geofence conclusions straight into the ETA calculation
 * rather than letting it re-derive them.
 *
 * <h2>State is written only when it changed shape</h2>
 *
 * <p>A rule returns the state it now holds. If that is the same instance it was given, nothing
 * happened and nothing is written — not to MongoDB and not even to the cache. If a condition began,
 * ended, or produced a finding, the state is persisted. Everything in between only refreshes memory.
 * See {@link ConditionStateStore}: this is the split that keeps a rarely-firing rule from costing a
 * database write per truck per fix.
 */
public class RuleService {

  private final ItineraryStore itineraries;
  private final ManifestLookup manifests;
  private final ConditionStateStore states;
  private final IncidentService incidents;

  private final TemperatureExcursionRule temperature;
  private final UnplannedStopRule unplannedStop;
  private final RouteDeviationRule routeDeviation;
  private final LateArrivalRule lateArrival;
  private final SignalLossRule signalLoss;

  public RuleService(
      ItineraryStore itineraries,
      ManifestLookup manifests,
      ConditionStateStore states,
      IncidentService incidents,
      TemperatureExcursionRule temperature,
      UnplannedStopRule unplannedStop,
      RouteDeviationRule routeDeviation,
      LateArrivalRule lateArrival,
      SignalLossRule signalLoss) {
    this.itineraries = itineraries;
    this.manifests = manifests;
    this.states = states;
    this.incidents = incidents;
    this.temperature = temperature;
    this.unplannedStop = unplannedStop;
    this.routeDeviation = routeDeviation;
    this.lateArrival = lateArrival;
    this.signalLoss = signalLoss;
  }

  /** The two rules that judge where a vehicle is and whether it is moving. */
  public void onPosition(PositionEvent event) {
    noteSighting(event.shipmentId(), event.occurredAt(), event.eventId());

    Itinerary plan = itineraries.forShipment(event.shipmentId()).orElse(null);
    boolean coldChain = terms(event.shipmentId()).map(SlaTerms::coldChain).orElse(false);

    ConditionState stopBefore = states.get(event.shipmentId(), ExceptionType.UNPLANNED_STOP);
    apply(unplannedStop.evaluate(stopBefore, event, plan, coldChain), stopBefore);

    ConditionState routeBefore = states.get(event.shipmentId(), ExceptionType.ROUTE_DEVIATION);
    apply(routeDeviation.evaluate(routeBefore, event, plan), routeBefore);
  }

  /** The cold-chain rule, which is the only thing on the status topic this service cares about. */
  public void onStatus(StatusEvent event) {
    noteSighting(event.shipmentId(), event.occurredAt(), event.eventId());

    ConditionState before = states.get(event.shipmentId(), ExceptionType.TEMPERATURE_EXCURSION);
    apply(temperature.evaluate(before, event, terms(event.shipmentId()).orElse(null)), before);
  }

  /** A revised estimate: the one rule that can fire before anything has actually gone wrong. */
  public void onEstimate(EtaUpdated event) {
    noteSighting(event.shipmentId(), event.occurredAt(), event.eventId());
    Itinerary plan = itineraries.forShipment(event.shipmentId()).orElse(null);
    apply(lateArrival.evaluate(event, terms(event.shipmentId()).orElse(null), plan), null);
  }

  /**
   * An arrival, which settles a projection and may end the shipment.
   *
   * <p>The order matters. The late-arrival verdict is reached first, because arriving at the final
   * stop both settles the question and completes the shipment, and completing it first would drop
   * the shipment from the watch list before the arrival had been judged.
   */
  public void onArrival(ShipmentArrived event) {
    noteSighting(event.shipmentId(), event.occurredAt(), event.eventId());
    Itinerary plan = itineraries.forShipment(event.shipmentId()).orElse(null);

    apply(lateArrival.evaluate(event, terms(event.shipmentId()).orElse(null), plan), null);

    if (isFinalStop(plan, event.stopId())) {
      // The load has been delivered. Its device going quiet from here is expected, and continuing
      // to watch for it would turn every completed delivery into an alert a few minutes later.
      Clearance completed =
          signalLoss.completed(event.shipmentId(), event.occurredAt(), event.eventId());
      clear(completed);
    }
  }

  /**
   * A departure. No rule judges one, but it still proves the shipment is alive.
   *
   * <p>Worth handling explicitly rather than letting it fall through the shape check, because
   * "nothing here cares about departures" and "departures accidentally do not count as a sighting"
   * look identical until a truck that has left a dock and driven for an hour without a position fix
   * is wrongly reported silent.
   */
  public void onDeparture(String shipmentId, java.time.Instant at, String eventId) {
    noteSighting(shipmentId, at, eventId);
  }

  /**
   * The periodic look for shipments that have gone silent.
   *
   * <p>Driven by a timer rather than by an event, because there is no event: see
   * {@link SignalLossRule}.
   */
  public void sweepForSilence() {
    List<Finding> silent =
        signalLoss.sweep(
            shipmentId -> terms(shipmentId).map(SlaTerms::coldChain).orElse(false));
    for (Finding finding : silent) {
      incidents.raise(finding);
    }
  }

  /** Every event on every topic counts as the shipment being heard from. */
  private void noteSighting(String shipmentId, java.time.Instant at, String eventId) {
    clear(signalLoss.seen(shipmentId, at, eventId));
  }

  /**
   * Applies one rule's conclusion.
   *
   * @param before the state the rule was given, so that "nothing changed" can be recognised by
   *     identity rather than by comparing fields
   */
  private void apply(RuleOutcome outcome, ConditionState before) {
    outcome.raised().ifPresent(incidents::raise);
    outcome.cleared().ifPresent(this::clear);

    ConditionState after = outcome.state();
    if (after == null || after == before) {
      // The rule declined to look, or looked and found the world unchanged. Writing here is what
      // regressed once in the tracking processor: a state updated unconditionally meant a truck on
      // an eight-hour leg wrote a row per stop per fix.
      return;
    }
    boolean shapeChanged =
        outcome.finding() != null
            || outcome.clearance() != null
            || (before != null && before.holding() != after.holding());
    if (shapeChanged) {
      states.persist(after);
    } else {
      states.remember(after);
    }
  }

  private void clear(Clearance clearance) {
    if (clearance == null) {
      return;
    }
    incidents.clear(
        clearance.type(),
        clearance.shipmentId(),
        clearance.stopId(),
        clearance.at(),
        clearance.causedBy(),
        clearance.resolution());
  }

  private Optional<SlaTerms> terms(String shipmentId) {
    return manifests.forShipment(shipmentId);
  }

  private static boolean isFinalStop(Itinerary plan, String stopId) {
    if (plan == null || plan.stops() == null || plan.stops().isEmpty() || stopId == null) {
      return false;
    }
    return plan.stops().stream()
        .max(java.util.Comparator.comparingInt(com.fleettracking.reference.ScheduledStop::seq))
        .map(stop -> stop.stopId().equals(stopId))
        .orElse(false);
  }
}
