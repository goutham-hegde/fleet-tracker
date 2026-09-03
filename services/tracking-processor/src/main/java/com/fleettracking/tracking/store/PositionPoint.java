package com.fleettracking.tracking.store;

import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.SourceSystem;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.mapping.TimeSeries;
import org.springframework.data.mongodb.core.timeseries.Granularity;

/**
 * One measurement in a shipment's position history.
 *
 * <h2>Why this is a time-series collection and not an ordinary one</h2>
 *
 * <p>Position history is the classic shape a time-series collection exists for: many small
 * documents that are written once and never updated, that always carry a timestamp, and that are
 * almost always read as "everything for one shipment between two instants". MongoDB stores such a
 * collection as compressed buckets — many measurements for the same {@code metaField} over a short
 * window packed into one physical document — which is both far smaller on disk and far cheaper to
 * scan than one document per ping. The alternative is to hand-roll the same idea, appending to an
 * array inside a per-shipment-per-hour document, and then to own for ever the bucket-splitting, the
 * document-size ceiling and the index maintenance that the server already does.
 *
 * <h2>What the two special fields mean</h2>
 *
 * <ul>
 *   <li><b>{@code timeField = "ts"}</b> — the measurement's own instant. This is
 *       {@code occurredAt}: when the truck was actually there, not when this platform heard about
 *       it. Using arrival time would put a mobile-app backlog dumped after a dead zone into the
 *       wrong hour of history, and the whole point of the collection is to answer "where was it
 *       at 14:05".
 *   <li><b>{@code metaField = "shipmentId"}</b> — the value that identifies the series. MongoDB
 *       buckets by it, so measurements for one shipment sit together and a per-shipment query
 *       touches almost nothing else. It also gets an automatic {@code {shipmentId: 1, ts: 1}}
 *       index at creation, which is the index every read here wants; no index is declared by hand.
 *   <li><b>{@code granularity = SECONDS}</b> — a hint about how far apart consecutive measurements
 *       for one series are, which is what sizes the buckets. Telematics reports every few seconds,
 *       so {@code SECONDS} is right; {@code HOURS} would tell the server to pack an hour of one
 *       shipment into a single bucket and make every short-window read drag the whole hour off
 *       disk.
 * </ul>
 *
 * <h2>Three limitations that shaped the rest of this service</h2>
 *
 * <ul>
 *   <li>A time-series collection is <b>not created by inserting into it</b>. An insert into a
 *       missing collection makes an ordinary one, with no error and no warning — the buckets, the
 *       compression and the automatic index are all silently absent. It must be created explicitly;
 *       {@link PositionStore#ensureCollections()} does that at startup.
 *   <li><b>Unique indexes are not supported</b> ({@code InvalidOptions} on the attempt). The
 *       {@code _id} below is the derived event id and is genuinely useful for tracing a
 *       measurement back to the message that produced it, but it enforces nothing: insert the same
 *       document twice and there will be two of them. Idempotence has to come from the consumer,
 *       which recognises a redelivered record against this collection and a source duplicate
 *       against a bounded in-memory set — see {@code PartitionGuard} and {@code RecentEventIds}.
 *       Neither is absolute, and the database cannot make them so: two copies of one event
 *       separated by more than the in-memory window would both be stored.
 *   <li>Measurements are append-only in this design. Nothing here is ever updated; a correction is
 *       a replay of the topic into a fresh collection.
 * </ul>
 *
 * @param eventId the derived event id, stored as {@code _id}. Traceability, not a constraint
 * @param ts when the truck was at this point
 * @param shipmentId the series this measurement belongs to
 * @param vehicleId the tractor, resolved by the gateway
 * @param deviceId the reporting hardware, when the feed named one
 * @param location GeoJSON, and therefore {@code [longitude, latitude]} — the opposite order from
 *     the {@code GeoPoint} it is built from. Doing the swap here, in one visible line, is why the
 *     canonical model keeps the two named rather than as a bare pair of doubles
 * @param speedKph ground speed, already converted by the gateway's normalizer
 * @param headingDegrees clockwise from true north
 * @param odometerKm lifetime vehicle distance
 * @param accuracyMeters reported horizontal accuracy; carried because geofencing in S10 must be
 *     allowed to distrust a fix before acting on it
 * @param source which feed this came in on. Kept per measurement rather than per shipment because
 *     one shipment's history genuinely mixes feeds, and a consumer weighing "where is it now"
 *     should be able to prefer a telematics fix over a two-hour-old app report
 * @param receivedAt when the gateway heard it. Together with {@code ts} this is feed lag, which is
 *     a real operational signal and cannot be recovered later if it is not stored now
 */
@TimeSeries(
    collection = PositionPoint.COLLECTION,
    timeField = "ts",
    metaField = "shipmentId",
    granularity = Granularity.SECONDS)
public record PositionPoint(
    @Id String eventId,
    Instant ts,
    String shipmentId,
    String vehicleId,
    String deviceId,
    GeoJsonPoint location,
    Double speedKph,
    Double headingDegrees,
    Double odometerKm,
    Double accuracyMeters,
    SourceSystem source,
    Instant receivedAt) {

  /** The physical collection name. */
  public static final String COLLECTION = "position.history";

  /** Builds a measurement from a canonical position event. */
  public static PositionPoint from(PositionEvent event) {
    return new PositionPoint(
        event.eventId(),
        event.occurredAt(),
        event.shipmentId(),
        event.vehicleId(),
        event.deviceId(),
        // longitude first. GeoJSON orders the pair the opposite way round from how people say it.
        new GeoJsonPoint(event.position().longitude(), event.position().latitude()),
        event.speedKph(),
        event.headingDegrees(),
        event.odometerKm(),
        event.accuracyMeters(),
        event.raw().source(),
        event.receivedAt());
  }
}
