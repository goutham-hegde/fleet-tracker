package com.fleettracking.dashboard.read;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * One shipment, as a map draws it: a marker, a label, and a reason to click.
 *
 * <h2>Why this is assembled here rather than in the browser</h2>
 *
 * <p>Everything on this record comes from a different collection, written by a different service:
 * the position from the tracking processor's current-position document, which stop the truck has
 * cleared from its geofence state, the estimate from its ETA state, the incidents from the exception
 * service. A dashboard that fetched four endpoints and joined them would be doing this join in
 * JavaScript, once per truck, over a network — and would have to be rewritten every time the
 * platform grew a fifth thing worth knowing.
 *
 * <p>The join is also where the platform's own conclusions get reconciled, and that is not a job for
 * a browser. Whether a truck counts as "at a stop" is the geofencer's announced arrival rather than
 * raw proximity; whether an estimate is usable depends on it naming the stop the truck is actually
 * heading for. Both are decisions, and both are made once, here.
 *
 * <h2>Nulls are omitted from the JSON</h2>
 *
 * <p>Following the platform's shared mapper. A shipment with nothing wrong sends no exception list
 * key at all rather than an empty one, and a shipment with no plan sends no next stop — the absence
 * is the message, and a browser distinguishing "absent" from "null" is doing avoidable work.
 *
 * @param movement what the truck is doing, as one word a map legend can colour by
 * @param atStop the stop it is sitting at, or absent while it is on the road
 * @param nextStop where it is going and when it is expected, or absent for a finished or unplanned
 *     shipment
 * @param remainingRouteKm road kilometres left through every remaining stop, measured now — see
 *     {@link RemainingDistance}
 * @param openExceptions the SLA breaches currently open against this load, worst first
 * @param staleSeconds how long ago the truck was where this says it is, in event time. The field a
 *     dashboard greys a marker out on: a position is not wrong because it is old, it is just old,
 *     and saying so is more useful than hiding it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShipmentSummary(
    String shipmentId,
    String vehicleId,
    String deviceId,
    String source,
    Instant occurredAt,
    Instant receivedAt,
    Instant updatedAt,
    Long staleSeconds,
    double latitude,
    double longitude,
    Double speedKph,
    Double headingDegrees,
    Double accuracyMeters,
    Movement movement,
    AtStop atStop,
    NextStop nextStop,
    int stopsCompleted,
    int stopsTotal,
    Double fractionComplete,
    Double remainingRouteKm,
    List<IncidentSummary> openExceptions,
    String worstSeverity) {

  /**
   * What a truck is doing, in the one word a map legend can colour by.
   *
   * <p>Deliberately not derived from speed alone. A truck reporting zero on a motorway hard shoulder
   * and a truck reporting zero inside a delivery yard are the same number and completely different
   * situations — the first is what the unplanned-stop rule exists to catch, and the second is a
   * driver doing their job. The distinction comes from the geofence state, which is the platform's
   * conclusion rather than a threshold applied twice.
   */
  public enum Movement {
    /** Moving, by the same 5 km/h the ETA calculator uses to decide a fix is worth learning from. */
    MOVING,

    /** Stationary, and not at any stop in its plan. */
    STOPPED,

    /** Inside a stop's geofence, arrival announced and departure not. */
    AT_STOP,

    /** Every stop in the plan has been arrived at. Nothing left to estimate. */
    DELIVERED
  }

  /** The stop a truck is currently sitting at. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AtStop(String stopId, String name, String city, int seq, Instant since) {}

  /**
   * Where a truck is going, and when the platform thinks it gets there.
   *
   * @param remainingKm road kilometres to this stop, measured on this request from the live position
   * @param estimatedArrival the tracking processor's estimate, or absent when there is not yet a
   *     usable one
   * @param estimatePending why {@code estimatedArrival} is absent, when it is. An estimate is
   *     suppressed by design while a truck is at a stop, and has not been formed at all until the
   *     first fix of a leg. Saying which is the difference between a dashboard that looks broken and
   *     one that explains itself
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record NextStop(
      String stopId,
      String name,
      String city,
      int seq,
      double latitude,
      double longitude,
      double radiusMeters,
      Double remainingKm,
      Instant estimatedArrival,
      Double confidence,
      String estimatePending) {}

  /**
   * An SLA breach, as much of one as a marker needs. The full incident is on the detail.
   *
   * <p>{@code shipmentId} is redundant here — this record is nested inside the summary of the very
   * shipment it belongs to — and it is carried anyway, because the same record is returned flat by
   * {@code /api/exceptions}. Without it that endpoint answers "a truck is 15 km off route" without
   * saying which truck, which makes an exceptions panel unable to link a row to a marker. Found by
   * querying the running service rather than by a test: the integration test asserted how many
   * incidents came back and never looked at what was in them.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record IncidentSummary(
      String exceptionId,
      String shipmentId,
      String type,
      String severity,
      String state,
      Instant onsetAt,
      Instant raisedAt,
      Instant clearedAt,
      String stopId,
      String detail,
      Double observedValue,
      Double thresholdValue,
      String resolution) {}
}
