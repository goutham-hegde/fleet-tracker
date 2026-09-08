package com.fleettracking.dashboard.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * One thing that has just happened, on its way to a browser.
 *
 * <h2>Why the platform's own events are not sent through unchanged</h2>
 *
 * <p>They very nearly are, and the difference is deliberate. A canonical {@code PositionEvent}
 * carries a {@code raw} field holding the untouched source payload — the whole telematics JSON
 * document or EDI interchange the event was normalized from. That field exists for replay and
 * debugging and it is easily the largest part of the message; forwarding it would multiply the
 * bytes on every open connection by something like five, to send a browser a copy of a payload it
 * cannot use and must not depend on.
 *
 * <p>The second reason is a contract. The wire format between this service and the dashboard is not
 * the wire format between the platform's own services, and pretending otherwise would make every
 * browser a consumer of {@code position.events.v1} — so a v2 of that envelope would become a
 * front-end change. What goes down this stream is the smallest thing a map needs to move a marker.
 *
 * @param type what kind of thing this is, and the SSE event name a browser subscribes to. Values
 *     are lower-case and dotted, matching the topics they come from rather than Java's naming
 * @param shipmentId which load. Every update on this stream is about exactly one, which is what
 *     lets a browser file it against a marker without parsing the payload
 * @param at the event's own instant, in event time. Not when this service forwarded it: a viewer
 *     watching a time-scaled run needs the simulated clock, and a viewer watching a real fleet
 *     needs the truck's clock. Neither wants this process's
 * @param payload the update itself — one of the records in {@link LiveUpdate}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LiveUpdate(String type, String shipmentId, Instant at, Object payload) {

  /** A truck moved. By far the most common update, and the one that is thinned under load. */
  public static final String POSITION = "position";

  /** The platform concluded a truck reached a stop. */
  public static final String ARRIVED = "arrived";

  /** The platform concluded a truck left one. */
  public static final String DEPARTED = "departed";

  /** A revised arrival estimate. */
  public static final String ESTIMATE = "estimate";

  /** An SLA breach began. */
  public static final String EXCEPTION_RAISED = "exception.raised";

  /** An SLA breach ended. */
  public static final String EXCEPTION_CLEARED = "exception.cleared";

  /** A reefer reading, or any other status the platform recorded. */
  public static final String STATUS = "status";

  /** Where a truck is, thinned to what a marker needs. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Position(
      String vehicleId,
      double latitude,
      double longitude,
      Double speedKph,
      Double headingDegrees,
      Double accuracyMeters,
      String source) {}

  /** A stop reached or left. {@code dwellSeconds} is present only on a departure. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record StopEvent(
      String stopId, double latitude, double longitude, Long dwellSeconds, Instant scheduledArrival) {}

  /**
   * A revised estimate.
   *
   * <p>{@code remainingKm} is carried here and, unlike the stored field, is not stale: it is what
   * the tracking processor measured from the fix that caused this estimate, which is the same
   * instant this update describes. The staleness the dashboard has to work around belongs to the
   * <em>document</em>, which is only rewritten on publish — the event was always accurate about the
   * moment it was published.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Estimate(
      String stopId,
      Instant estimatedArrival,
      Instant previousEstimate,
      Double remainingKm,
      Double confidence) {}

  /** An SLA breach raised or cleared. Both carry the incident id, which is what pairs them. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ExceptionEvent(
      String exceptionId,
      String type,
      String severity,
      String detail,
      String stopId,
      Double observedValue,
      Double thresholdValue,
      Instant raisedAt,
      Long openForSeconds,
      String resolution) {}

  /** A status reading — temperature, most usefully. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Status(
      String vehicleId,
      String status,
      String reasonCode,
      Double temperatureCelsius,
      Double setpointCelsius) {}
}
