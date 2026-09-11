package com.fleettracking.publicview.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything the public table knows about one shipment: what the lookup function reads back.
 *
 * <p>These are facts the platform published, stored without judgement. Which stop a truck is at,
 * whether it is delivered, what counts as open: all of that is decided at read time by
 * {@code PublicAssembler}, with the plan in hand, so a rule can change without re-indexing a month
 * of archive.
 *
 * @param position the newest fix, or null if the archive has only ever shown this load's stops
 * @param stops by stop id, what was announced at each
 * @param incidents every incident the archive has shown for this load, open and cleared
 */
public record ShipmentFacts(
    String shipmentId, Position position, Map<String, StopFacts> stops, List<IncidentFacts> incidents) {

  /** The newest fix. {@code receivedAt} is wall-clock; everything else here is event time. */
  public record Position(
      String eventId,
      String vehicleId,
      String deviceId,
      String source,
      Instant occurredAt,
      Instant receivedAt,
      double latitude,
      double longitude,
      Double speedKph,
      Double headingDegrees,
      Double accuracyMeters) {}

  /**
   * What the platform announced at one stop. Either instant may be absent, and a departure may be
   * older than the arrival: that is a departure from an earlier run over the same shipment id, and
   * the assembler knows not to count it.
   */
  public record StopFacts(String stopId, Instant arrivedAt, Instant departedAt, Long dwellSeconds) {}

  /**
   * One incident, assembled from its raise and its clear, in whichever order they were indexed.
   *
   * @param onsetAt when the condition began. Both events carry it: the raise as its own
   *     {@code occurredAt}, the clear as {@code raisedAt}. That is what lets a clear indexed before
   *     its raise still produce a complete incident
   * @param type null only if nothing but a clear has been seen and it predates the field, which
   *     cannot happen on this platform; kept nullable rather than asserted
   * @param severity null when only the clear has been seen, since a clear does not repeat it
   */
  public record IncidentFacts(
      String exceptionId,
      String shipmentId,
      String type,
      String severity,
      String detail,
      String stopId,
      Instant onsetAt,
      Instant clearedAt,
      Double observedValue,
      Double thresholdValue,
      String resolution) {

    public boolean isOpen() {
      return clearedAt == null;
    }
  }

  /** How far the archive has been indexed, per topic. */
  public record Watermark(String topic, Instant newestReceived, String lastKey, Instant indexedAt) {}
}
