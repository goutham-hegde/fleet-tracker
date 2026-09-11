package com.fleettracking.publicview.lookup;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * What the public page is sent: the dashboard API's wire format, minus what the archive cannot
 * honestly supply.
 *
 * <p>These mirror {@code dashboard/src/api/types.ts} field for field, so the dashboard that draws the
 * live fleet draws this one with no second set of components. What is missing is missing
 * deliberately, and the page copes because the live API omits nulls too:
 *
 * <ul>
 *   <li><b>{@code nextStop}</b>, and with it every estimate. An arrival time forecast during a run
 *       that may have finished days ago is not information.
 *   <li><b>{@code fractionComplete} and {@code remainingRouteKm}</b>, which the live API measures
 *       from the road-distance model in {@code libs/reference}. That module is built on Spring Data
 *       MongoDB, which this function neither has nor needs.
 *   <li><b>{@code manifest} and {@code track}.</b> Manifests are not in the archive, and a trail
 *       would mean keeping a history per shipment that the table is designed not to hold.
 * </ul>
 */
public final class Wire {

  private Wire() {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record AtStop(String stopId, String name, String city, int seq, Instant since) {}

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

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ShipmentSummary(
      String shipmentId,
      String vehicleId,
      String deviceId,
      String source,
      Instant occurredAt,
      Instant receivedAt,
      Long staleSeconds,
      double latitude,
      double longitude,
      Double speedKph,
      Double headingDegrees,
      Double accuracyMeters,
      String movement,
      AtStop atStop,
      int stopsCompleted,
      int stopsTotal,
      List<IncidentSummary> openExceptions,
      String worstSeverity) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PlannedStop(
      String stopId,
      int seq,
      String name,
      String city,
      String state,
      double latitude,
      double longitude,
      double radiusMeters,
      String kind,
      String status,
      Instant arrivedAt,
      Instant departedAt,
      Long dwellSeconds) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ShipmentDetail(
      ShipmentSummary summary, List<PlannedStop> stops, List<IncidentSummary> exceptions) {}

  /**
   * What the public view can see.
   *
   * @param source always {@code archive}: the page says so rather than letting a snapshot pass for
   *     a live feed
   * @param archivedThrough the newest Kafka timestamp indexed, across topics. When the platform last
   *     received something that has reached this table, in wall-clock time
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Meta(
      int trackedShipments, int openExceptions, String source, Instant archivedThrough) {}

  /** The body of every error response. */
  public record Problem(int status, String error) {}
}
