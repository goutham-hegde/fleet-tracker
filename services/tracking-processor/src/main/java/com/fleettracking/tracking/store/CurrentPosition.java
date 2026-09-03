package com.fleettracking.tracking.store;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Where a shipment is now: exactly one document per shipment, overwritten in place.
 *
 * <h2>Why this exists next to the history</h2>
 *
 * <p>"Where is this shipment?" is the question the map dashboard asks about every shipment on
 * screen, several times a minute. Answering it from the history means, for each shipment, finding
 * the newest measurement in a collection that grows for ever — cheap per query against the
 * automatic index, but paid again on every refresh, for every shipment, and it grows more expensive
 * as the history does. One small document per shipment answers it with a single lookup by primary
 * key and never gets slower.
 *
 * <p>This is denormalisation, and it is the kind that is worth it: the derived value is written by
 * exactly one service, from exactly one stream, and can always be rebuilt by replaying the topic.
 * The failure mode of denormalisation — two writers disagreeing — is structurally impossible here,
 * because only this consumer group writes it.
 *
 * <h2>Forward-only in event time</h2>
 *
 * <p>The update that maintains this document is conditional on the incoming event being
 * <em>strictly newer than what is already stored</em>, in event time rather than arrival time. That
 * condition is not defensive programming, it is the mobile feed's normal behaviour: a driver's phone
 * goes quiet through a canyon and then dumps twenty buffered fixes at once, out of order. Without
 * the condition, the last message of that burst wins, and the map would show a truck jumping
 * backwards to where it was twenty minutes ago. The history keeps every one of those fixes; only
 * this document insists on moving forwards.
 *
 * @param shipmentId the primary key — stored as {@code _id}, which is what makes the conditional
 *     upsert safe: a losing update cannot create a second document for the same shipment, it
 *     collides with this key instead
 * @param eventId the event this position came from
 * @param vehicleId the tractor as of that event
 * @param deviceId the reporting hardware, when the feed named one
 * @param occurredAt when the truck was here. The field the forward-only condition compares
 * @param receivedAt when the gateway heard it
 * @param updatedAt when this document was last written. Wall-clock, and deliberately distinct from
 *     the two above: a stale {@code updatedAt} means this service has stopped, while a stale
 *     {@code occurredAt} with a fresh {@code updatedAt} means the truck has stopped reporting.
 *     Collapsing them into one field would make those two very different incidents look identical
 * @param location GeoJSON {@code [longitude, latitude]}
 * @param speedKph ground speed at that moment
 * @param headingDegrees clockwise from true north
 * @param odometerKm lifetime vehicle distance
 * @param accuracyMeters reported horizontal accuracy of the winning fix
 * @param source which feed the winning fix came from
 */
@Document(collection = CurrentPosition.COLLECTION)
public record CurrentPosition(
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

  public static final String COLLECTION = "shipment.position";
}
