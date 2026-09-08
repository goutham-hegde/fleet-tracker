package com.fleettracking.dashboard.read;

import com.fleettracking.reference.Itinerary;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The question "what is the fleet doing", answered once per request.
 *
 * <p>Reading is {@link FleetReader}'s job and judging is {@link FleetAssembler}'s; this is the thin
 * seam that runs one after the other. It is separate from both because the batching is the part that
 * has to be got right — the reader knows how to fetch a collection at a time and the assembler knows
 * how to fold one shipment, and something has to hold the fact that the second is called in a loop
 * over data the first fetched in bulk.
 */
public class FleetService {

  private final FleetReader reader;
  private final FleetAssembler assembler;
  private final int trackLimit;

  public FleetService(FleetReader reader, FleetAssembler assembler, int trackLimit) {
    this.reader = reader;
    this.assembler = assembler;
    this.trackLimit = trackLimit;
  }

  /**
   * Every shipment with a known position.
   *
   * <p>Ordered worst-first: a truck with a critical exception sorts above one with a warning, and a
   * clean fleet falls back to shipment id so the list does not reshuffle itself between refreshes
   * for no reason. A list that reorders while somebody is reading it is a small thing that makes a
   * dashboard feel untrustworthy.
   */
  public List<ShipmentSummary> fleet() {
    List<Views.PositionView> positions = reader.positions();
    List<String> ids = positions.stream().map(Views.PositionView::shipmentId).toList();

    Map<String, Itinerary> plans = reader.itineraries(ids);
    Map<String, List<Views.GeofenceView>> states = reader.geofenceStates(ids);
    Map<String, Views.EtaView> estimates = reader.estimates(ids);
    Map<String, List<Views.IncidentView>> incidents = reader.incidents(ids, true);

    return positions.stream()
        .map(
            position ->
                assembler.summarize(
                    position,
                    plans.get(position.shipmentId()),
                    states.getOrDefault(position.shipmentId(), List.of()),
                    estimates.get(position.shipmentId()),
                    incidents.getOrDefault(position.shipmentId(), List.of())))
        .sorted(
            Comparator.comparingInt((ShipmentSummary s) -> -FleetAssembler.rank(s.worstSeverity()))
                .thenComparing(ShipmentSummary::shipmentId))
        .toList();
  }

  /**
   * One shipment, in full.
   *
   * <p>Empty when the platform has never had a position for it. Deliberately not "empty when the
   * shipment does not exist" — this service has no idea what shipments exist, only what it has
   * heard about, and a load whose truck has not switched its unit on yet is indistinguishable from
   * one nobody ever created. Saying "not found" is the honest answer to both.
   */
  public Optional<ShipmentDetail> detail(String shipmentId) {
    Views.PositionView position = reader.position(shipmentId);
    if (position == null) {
      return Optional.empty();
    }

    List<String> id = List.of(shipmentId);
    Itinerary plan = reader.itineraries(id).get(shipmentId);
    List<Views.GeofenceView> states = reader.geofenceStates(id).getOrDefault(shipmentId, List.of());
    Views.EtaView estimate = reader.estimates(id).get(shipmentId);
    List<Views.IncidentView> incidents = reader.incidents(id, false).getOrDefault(shipmentId, List.of());

    ShipmentSummary summary =
        assembler.summarize(
            position,
            plan,
            states,
            estimate,
            incidents.stream().filter(Views.IncidentView::isOpen).toList());

    return Optional.of(
        assembler.detail(
            summary,
            plan,
            states,
            reader.manifest(shipmentId),
            incidents,
            reader.track(shipmentId, trackLimit)));
  }

  /** Incidents across the whole fleet, for the exceptions panel. */
  public List<ShipmentSummary.IncidentSummary> exceptions(boolean openOnly, int limit) {
    return reader.allIncidents(openOnly, limit).stream()
        .map(FleetAssembler::incidentSummary)
        .toList();
  }

  /**
   * What this service can currently see, for an operator and for the integration test.
   *
   * <p>The same instinct as the heartbeat logs in the other three consumers: a count of zero here is
   * the difference between "nothing is happening" and "this is pointed at the wrong database", and
   * that distinction has cost this project a session before.
   */
  public Map<String, Object> meta() {
    return Map.of(
        "trackedShipments", reader.trackedCount(),
        "openExceptions", reader.openIncidentCount());
  }
}
