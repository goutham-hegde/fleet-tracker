package com.fleettracking.dashboard.read;

import com.fleettracking.dashboard.read.ShipmentSummary.AtStop;
import com.fleettracking.dashboard.read.ShipmentSummary.IncidentSummary;
import com.fleettracking.dashboard.read.ShipmentSummary.Movement;
import com.fleettracking.dashboard.read.ShipmentSummary.NextStop;
import com.fleettracking.events.GeoPoint;
import com.fleettracking.events.Severity;
import com.fleettracking.reference.Itinerary;
import com.fleettracking.reference.ScheduledStop;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Folds four services' documents into the one shape a map draws.
 *
 * <h2>A pure function, deliberately</h2>
 *
 * <p>Nothing here touches MongoDB, Kafka or the clock except through the {@link Clock} it is handed.
 * Reading is {@link FleetReader}'s job and this is the judgement, which is the same split the
 * exception service makes between its rules and {@code RuleService}: the reference data is fetched
 * in one place and every opinion about what it means is formed in another. It is what lets the
 * awkward cases here — a truck at a stop, a finished shipment, an estimate that names a stop already
 * cleared — be tested by calling a method, with no broker and no database.
 *
 * <h2>The three reconciliations this performs</h2>
 *
 * <p>The documents disagree in small ways, because they are written by different services at
 * different moments, and every one of those disagreements has to be resolved here rather than shown
 * to a viewer.
 *
 * <ul>
 *   <li><b>Which stop is next</b> is taken by plan order, not by proximity, and is the first stop
 *       with no announced arrival. This is copied deliberately from {@code ShipmentProgress} in the
 *       tracking processor: a lane through Jaipur passes within a few kilometres of places it left
 *       hours ago, so "nearest" is the wrong question and two components answering it differently
 *       would put a truck's marker and its ETA on different stops.
 *   <li><b>Whether the estimate is usable.</b> The ETA document is written on publish, and no
 *       estimate is published while a truck sits at a stop — so at every dock the stored estimate
 *       names the stop the truck is standing in, while the next stop has moved on. Showing it would
 *       put yesterday's answer against tomorrow's question. When the two disagree the estimate is
 *       withheld and the reason is stated.
 *   <li><b>How far there is left to go</b> is measured now rather than read, because the stored
 *       distance is only refreshed when an estimate moves. See {@link RemainingDistance}.
 * </ul>
 */
public class FleetAssembler {

  /**
   * Above this, a truck is moving.
   *
   * <p>The same 5 km/h the ETA calculator uses to decide whether a fix is worth learning a speed
   * from, and the same value for the same reason: below it, a reading is noise around a stationary
   * vehicle rather than a measurement of travel. Stated here rather than imported, because the
   * tracking processor's copy is a threshold for learning and this one is a label on a screen — two
   * uses that happen to agree today and are not obliged to for ever.
   */
  public static final double MOVING_KPH = 5.0;

  /** No estimate has been formed for this shipment yet. */
  public static final String PENDING_NO_ESTIMATE = "NO_ESTIMATE_YET";

  /** Suppressed by design: nothing knows how long a load takes to work. */
  public static final String PENDING_AT_A_STOP = "AT_A_STOP";

  /** The stored estimate names a stop the truck has already cleared. */
  public static final String PENDING_SUPERSEDED = "SUPERSEDED";

  private final RemainingDistance distances;
  private final Clock clock;

  public FleetAssembler(RemainingDistance distances, Clock clock) {
    this.distances = distances;
    this.clock = clock;
  }

  /**
   * One shipment's marker.
   *
   * @param position where it is. The only argument that cannot be null — a shipment the platform has
   *     never had a position for has nothing to draw
   * @param itinerary its plan, or null for a load nobody planned. Not an error: the tracking
   *     processor stores those positions and announces nothing, and the map shows the truck with no
   *     route
   * @param states what is believed about each of its stops. Absent stops have simply never been near
   * @param eta the last published estimate, or null
   * @param incidents every incident against this load, open and cleared
   */
  public ShipmentSummary summarize(
      Views.PositionView position,
      Itinerary itinerary,
      List<Views.GeofenceView> states,
      Views.EtaView eta,
      List<Views.IncidentView> incidents) {

    Map<String, Views.GeofenceView> byStop =
        states.stream()
            .collect(Collectors.toMap(Views.GeofenceView::stopId, Function.identity(), (a, b) -> a));

    List<ScheduledStop> plan = orderedStops(itinerary);
    List<ScheduledStop> remaining =
        plan.stream().filter(stop -> !arrived(byStop, stop.stopId())).toList();

    ScheduledStop currentStop = atStop(plan, byStop);
    Movement movement = movement(position, plan, remaining, currentStop);
    GeoPoint here = new GeoPoint(position.latitude(), position.longitude());

    List<IncidentSummary> open =
        incidents.stream()
            .filter(Views.IncidentView::isOpen)
            .map(FleetAssembler::incidentSummary)
            .sorted(
                Comparator.comparingInt((IncidentSummary i) -> -rank(i.severity()))
                    .thenComparing(IncidentSummary::onsetAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

    return new ShipmentSummary(
        position.shipmentId(),
        position.vehicleId(),
        position.deviceId(),
        position.source(),
        position.occurredAt(),
        position.receivedAt(),
        position.updatedAt(),
        staleSeconds(position.receivedAt()),
        position.latitude(),
        position.longitude(),
        position.speedKph(),
        position.headingDegrees(),
        position.accuracyMeters(),
        movement,
        currentStop == null
            ? null
            : new AtStop(
                currentStop.stopId(),
                currentStop.name(),
                currentStop.city(),
                currentStop.seq(),
                byStop.get(currentStop.stopId()).arrivalOccurredAt()),
        nextStop(here, remaining, eta, movement),
        plan.size() - remaining.size(),
        plan.size(),
        plan.isEmpty() ? null : distances.fractionComplete(here, plan, remaining),
        plan.isEmpty() ? null : distances.throughRemainingKm(here, remaining),
        open.isEmpty() ? null : open,
        open.isEmpty() ? null : open.get(0).severity());
  }

  /** The detail panel: the marker, plus the plan, the paperwork, the history and every incident. */
  public ShipmentDetail detail(
      ShipmentSummary summary,
      Itinerary itinerary,
      List<Views.GeofenceView> states,
      Views.ManifestView manifest,
      List<Views.IncidentView> incidents,
      List<Views.TrackPointView> track) {

    Map<String, Views.GeofenceView> byStop =
        states.stream()
            .collect(Collectors.toMap(Views.GeofenceView::stopId, Function.identity(), (a, b) -> a));

    List<ShipmentDetail.PlannedStop> stops = new ArrayList<>();
    for (ScheduledStop stop : orderedStops(itinerary)) {
      Views.GeofenceView state = byStop.get(stop.stopId());
      String status =
          state == null || !state.arrivalAnnounced()
              ? ShipmentDetail.PlannedStop.PENDING
              : state.departureAnnounced()
                  ? ShipmentDetail.PlannedStop.DEPARTED
                  : ShipmentDetail.PlannedStop.AT;

      Instant arrived = state == null ? null : state.arrivalOccurredAt();
      Instant departed =
          state == null || !state.departureAnnounced() ? null : state.leftAt();

      stops.add(
          new ShipmentDetail.PlannedStop(
              stop.stopId(),
              stop.seq(),
              stop.name(),
              stop.city(),
              stop.state(),
              stop.latitude(),
              stop.longitude(),
              stop.radiusMeters(),
              stop.kind(),
              status,
              arrived,
              departed,
              arrived != null && departed != null
                  ? Duration.between(arrived, departed).toSeconds()
                  : null));
    }

    List<IncidentSummary> all =
        incidents.stream()
            .map(FleetAssembler::incidentSummary)
            .sorted(
                Comparator.comparing(
                        IncidentSummary::onsetAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .reversed())
            .toList();

    List<ShipmentDetail.TrackPoint> trail =
        track.stream()
            .map(
                point ->
                    new ShipmentDetail.TrackPoint(
                        point.ts(),
                        point.latitude(),
                        point.longitude(),
                        point.speedKph(),
                        point.source()))
            .toList();

    return new ShipmentDetail(
        summary,
        manifest == null
            ? null
            : new ShipmentDetail.ManifestDetail(
                manifest.customerId(),
                manifest.mode(),
                manifest.schemaVersion(),
                manifest.createdAt(),
                manifest.body()),
        stops,
        all.isEmpty() ? null : all,
        trail.isEmpty() ? null : trail);
  }

  /** An incident, in the shape both the marker and the panel use. */
  public static IncidentSummary incidentSummary(Views.IncidentView incident) {
    return new IncidentSummary(
        incident.exceptionId(),
        incident.shipmentId(),
        incident.type(),
        incident.severity(),
        incident.state(),
        incident.onsetAt(),
        incident.raisedAt(),
        incident.clearedAt(),
        incident.stopId(),
        incident.detail(),
        incident.observedValue(),
        incident.thresholdValue(),
        incident.resolution());
  }

  /**
   * How severe, as a number, for sorting.
   *
   * <p>An unrecognised severity ranks lowest rather than throwing. The views read these as strings
   * precisely so a severity added later reaches a screen as an unfamiliar word instead of taking the
   * whole fleet view down, and a comparator that then refused to sort it would undo that.
   */
  static int rank(String severity) {
    if (severity == null) {
      return -1;
    }
    try {
      return Severity.valueOf(severity).ordinal();
    } catch (IllegalArgumentException unknown) {
      return -1;
    }
  }

  private NextStop nextStop(
      GeoPoint here, List<ScheduledStop> remaining, Views.EtaView eta, Movement movement) {

    if (remaining.isEmpty()) {
      // Delivered, or never planned. Either way there is nothing ahead to describe.
      return null;
    }
    ScheduledStop next = remaining.get(0);

    // The estimate is only shown when it is about the stop the truck is actually heading for.
    String pending = null;
    if (movement == Movement.AT_STOP) {
      pending = PENDING_AT_A_STOP;
    } else if (eta == null || eta.estimatedArrival() == null || eta.stopId() == null) {
      pending = PENDING_NO_ESTIMATE;
    } else if (!eta.stopId().equals(next.stopId())) {
      pending = PENDING_SUPERSEDED;
    }

    return new NextStop(
        next.stopId(),
        next.name(),
        next.city(),
        next.seq(),
        next.latitude(),
        next.longitude(),
        next.radiusMeters(),
        distances.toStopKm(here, next),
        pending == null ? eta.estimatedArrival() : null,
        pending == null ? eta.confidence() : null,
        pending);
  }

  private Movement movement(
      Views.PositionView position,
      List<ScheduledStop> plan,
      List<ScheduledStop> remaining,
      ScheduledStop currentStop) {

    if (!plan.isEmpty() && remaining.isEmpty()) {
      return Movement.DELIVERED;
    }
    if (currentStop != null) {
      return Movement.AT_STOP;
    }
    return position.speedKph() != null && position.speedKph() >= MOVING_KPH
        ? Movement.MOVING
        : Movement.STOPPED;
  }

  /**
   * The stop a truck is currently at: arrival announced, departure not.
   *
   * <p>Not {@code inside}, which is the raw geometry. A truck that has just crossed a fence is
   * inside it for three minutes before the platform is willing to say it arrived, and a dashboard
   * that jumped ahead of that would contradict the arrival event the same platform publishes.
   */
  private ScheduledStop atStop(List<ScheduledStop> plan, Map<String, Views.GeofenceView> byStop) {
    for (ScheduledStop stop : plan) {
      Views.GeofenceView state = byStop.get(stop.stopId());
      if (state != null && state.arrivalAnnounced() && !state.departureAnnounced()) {
        return stop;
      }
    }
    return null;
  }

  private boolean arrived(Map<String, Views.GeofenceView> byStop, String stopId) {
    Views.GeofenceView state = byStop.get(stopId);
    return state != null && state.arrivalAnnounced();
  }

  private static List<ScheduledStop> orderedStops(Itinerary itinerary) {
    if (itinerary == null || itinerary.stops() == null) {
      return List.of();
    }
    return itinerary.stops().stream()
        .sorted(Comparator.comparingInt(ScheduledStop::seq))
        .toList();
  }

  /**
   * How long ago the platform heard from this truck, in wall-clock seconds.
   *
   * <p>Measured against {@code receivedAt} rather than {@code occurredAt}, and that is not the
   * obvious choice. Under a time-scaled simulator run, simulated time outruns the wall clock and
   * {@code occurredAt} runs <em>ahead</em> of the moment the message was received — so a staleness
   * computed from event time is negative for every truck on the road, which reads as a broken clock.
   * What a viewer wants from this field is "should I trust this dot", and that is a question about
   * how long ago the news arrived.
   *
   * <p>Clamped at zero, because a demonstration run and a real deployment should differ in what the
   * number means, not in whether it is negative.
   */
  private Long staleSeconds(Instant receivedAt) {
    if (receivedAt == null) {
      return null;
    }
    return Math.max(0, Duration.between(receivedAt, clock.instant()).toSeconds());
  }
}
