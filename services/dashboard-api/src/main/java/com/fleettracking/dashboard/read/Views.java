package com.fleettracking.dashboard.read;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Every document this service reads from another service's collection, and nothing else.
 *
 * <h2>Projections, not the other services' models</h2>
 *
 * <p>The shortcut would be to depend on the tracking processor, the exception service and the
 * shipment service, and reuse the records they write. That would make the dashboard compile against
 * three services' write models at once, so a field promoted, renamed or tightened anywhere in the
 * platform would rebuild and redeploy the one component that only ever reads. S13 already made this
 * call in the other direction — {@code MongoManifestLookup} declares its own small view of the
 * manifest collection — and this is the same decision at five times the scale.
 *
 * <p>So what is declared here is the smallest read model that answers the dashboard's questions.
 * Gathering all five in one file is deliberate: this is the complete list of the platform's
 * internals the dashboard is coupled to, and it should be short enough to read in one screen. If it
 * stops being short, the dashboard has started depending on how other services work rather than on
 * what they concluded.
 *
 * <h2>Collection names are written out, not imported</h2>
 *
 * <p>A shared constant would say two components agree on a contract. They do not: this is one
 * service reading another's store, and spelling the name out is the honest form of that. It is also
 * what a search for the collection name finds.
 *
 * <h2>Enums are read as strings</h2>
 *
 * <p>{@code type}, {@code severity}, {@code source} and {@code mode} are all enums where they are
 * written. They are strings here, because a sixth exception type or a fifth freight mode must reach
 * a screen as an unfamiliar word rather than fail deserialization and take the whole fleet view down
 * with it. Nothing in this service branches on any of these values; they are passed through to the
 * browser, which renders what it is given.
 */
public final class Views {

  private Views() {}

  /**
   * Where a shipment is now — one document per shipment, from {@code shipment.position}.
   *
   * <p>The tracking processor maintains this with an upsert conditional on the event being strictly
   * newer in event time, which is why the dashboard can read it directly and never see a truck jump
   * backwards when the mobile app dumps a backlog.
   *
   * @param occurredAt when the truck was here
   * @param updatedAt when this document was last written. Deliberately distinct from the above: a
   *     stale {@code updatedAt} means the tracking processor stopped, while a stale
   *     {@code occurredAt} under a fresh {@code updatedAt} means the truck stopped reporting. The
   *     dashboard shows those as different things, which is the whole reason both fields exist
   * @param location GeoJSON, so {@code getX()} is longitude and {@code getY()} latitude — the
   *     opposite order to how everything human-facing in this project states a coordinate
   */
  @Document(collection = "shipment.position")
  public record PositionView(
      @Id String shipmentId,
      String eventId,
      String vehicleId,
      String deviceId,
      Instant occurredAt,
      Instant receivedAt,
      Instant updatedAt,
      GeoJsonPoint location,
      Double speedKph,
      Double headingDegrees,
      Double odometerKm,
      Double accuracyMeters,
      String source) {

    /** Latitude, dug back out of the GeoJSON ordering. */
    public double latitude() {
      return location == null ? 0 : location.getY();
    }

    /** Longitude. */
    public double longitude() {
      return location == null ? 0 : location.getX();
    }
  }

  /**
   * What the platform currently estimates, from {@code shipment.eta}.
   *
   * <p>Four of that document's nine fields are missing here, and their absence is the point. The
   * learned speed, how much moving time it was built from and when the vehicle last moved are the
   * ETA model's own working memory: they exist so a restart does not throw away what it knew about
   * a truck's pace, and they are no business of a screen.
   *
   * <p>{@code remainingKm} is left out for a stronger reason than tidiness — see
   * {@link RemainingDistance}. It is written only when an estimate is published, so a shipment five
   * kilometres from its stop can hold a document saying a hundred. The dashboard measures that
   * distance itself, on every request.
   */
  @Document(collection = "shipment.eta")
  public record EtaView(
      @Id String shipmentId,
      String stopId,
      Instant estimatedArrival,
      Double confidence,
      Instant lastFixAt,
      Instant updatedAt) {}

  /**
   * What the platform believes about one shipment at one stop, from {@code geofence.state}.
   *
   * <p>One document per shipment-and-stop, so a shipment with six stops has up to six of these. The
   * two announcement flags rather than the raw {@code inside} are what the dashboard renders: a
   * truck is "at" a stop when the platform said it arrived and has not said it left, which is the
   * conclusion, not the raw geometry.
   */
  @Document(collection = "geofence.state")
  public record GeofenceView(
      @Id String id,
      String shipmentId,
      String stopId,
      boolean inside,
      Instant enteredAt,
      Instant leftAt,
      boolean arrivalAnnounced,
      Instant arrivalOccurredAt,
      boolean departureAnnounced,
      Instant lastFixAt) {}

  /**
   * One SLA breach, from {@code exceptions} — the collection S13 filled and nothing has read until
   * now.
   *
   * <p>Both open and cleared incidents are read. A cleared one is not noise: "this lane broke its
   * cold chain twice today and recovered" is the sentence that makes an exception system worth
   * looking at, and a dashboard that showed only what is currently wrong could not say it.
   */
  @Document(collection = "exceptions")
  public record IncidentView(
      @Id String exceptionId,
      String shipmentId,
      String type,
      String stopId,
      String severity,
      String state,
      Instant onsetAt,
      Instant raisedAt,
      Instant clearedAt,
      Instant lastSeenAt,
      String detail,
      Double observedValue,
      Double thresholdValue,
      String resolution) {

    /** Whether this incident is still open. The string the exception service writes. */
    public boolean isOpen() {
      return "OPEN".equals(state);
    }
  }

  /**
   * What is in the truck, from {@code manifests}.
   *
   * <p>The body is handed to the browser exactly as the customer sent it. That is the one place in
   * this service where a document's shape is genuinely unknown at compile time, and it is the point
   * of M4: four customers whose manifests share no fields all render through the same endpoint,
   * because this service never looks inside.
   */
  @Document(collection = "manifests")
  public record ManifestView(
      @Id String shipmentId,
      String customerId,
      String mode,
      String schemaVersion,
      Instant createdAt,
      Map<String, Object> body) {}

  /**
   * One stored measurement, from the {@code position.history} time-series collection.
   *
   * <p>Read only for a single shipment's recent trail, never across the fleet. The series is keyed
   * by shipment ({@code metaField}) and ordered by its own instant ({@code timeField}), which is
   * exactly the shape of that query — asking for the last few hundred points of one shipment is
   * what a time-series collection is built to answer cheaply.
   *
   * <p>Declared with a plain {@code @Document} rather than {@code @TimeSeries} on purpose: the
   * annotation's extra job is <em>creating</em> the collection with the right options, and creating
   * it is the tracking processor's responsibility. A reader that could create it would be able to
   * create the wrong kind — an ordinary collection, silently, with no buckets and no compression,
   * which is the exact failure the processor refuses to start on.
   */
  @Document(collection = "position.history")
  public record TrackPointView(
      @Id String eventId,
      Instant ts,
      String shipmentId,
      GeoJsonPoint location,
      Double speedKph,
      Double headingDegrees,
      String source) {

    /** Latitude. */
    public double latitude() {
      return location == null ? 0 : location.getY();
    }

    /** Longitude. */
    public double longitude() {
      return location == null ? 0 : location.getX();
    }
  }
}
