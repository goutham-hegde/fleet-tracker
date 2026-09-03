package com.fleettracking.tracking.geofence;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * What the platform currently believes about one shipment at one stop.
 *
 * <h2>Why this is in the database and not in memory</h2>
 *
 * <p>M3 requires exactly one arrival per stop, <em>including across a restart</em>. Held in memory,
 * a restart forgets that an arrival was ever announced — so a truck still parked in a yard would be
 * announced as arriving a second time the moment the next fix came in. That is not a rare edge: a
 * truck spends the better part of an hour inside a geofence at every stop, and a deployment lands
 * whenever it lands.
 *
 * <p>The write cost is small because it is paid on <em>change</em>, not per position. A truck
 * driving between stops changes nothing here; it produces a read and no write at all.
 *
 * <h2>One document per shipment-and-stop</h2>
 *
 * <p>Rather than one document per shipment holding a map of stops. Two reasons. A per-shipment
 * document would be read, modified and written as a whole, so two stops changing state in the same
 * batch would race; and its size would grow with the length of the itinerary, which is exactly the
 * shape of document that eventually meets the size ceiling. All of one shipment's states are still
 * fetched in a single query — see {@code GeofenceStateStore} — so this costs no extra round trips.
 *
 * @param id {@code shipmentId|stopId}, so the pair is the primary key and cannot be duplicated
 * @param shipmentId carried as its own field, indexed, because the query is always "every stop for
 *     this shipment" rather than one stop at a time
 * @param inside whether the last trusted fix put the vehicle within the fence. Note the hysteresis
 *     in {@link Geofencer}: leaving requires going further out than entering required coming in, so
 *     this is not simply {@code distance < radius}
 * @param enteredAt when the vehicle crossed in — the instant the arrival is eventually stamped
 *     with. Deliberately not the instant the dwell threshold expired: those differ by the threshold,
 *     and using the later one would make every arrival look minutes late against its schedule
 * @param leftAt when the vehicle crossed back out, or null while it is inside
 * @param arrivalAnnounced whether {@code ShipmentArrived} has been published. The flag that makes
 *     "exactly one" true across a restart
 * @param arrivalOccurredAt the instant carried on that arrival, kept so the departure can state the
 *     dwell without reading the topic back
 * @param departureAnnounced whether {@code ShipmentDeparted} has been published. Terminal: a stop
 *     that has been arrived at and departed from is finished, and a later re-entry does not reopen
 *     it. A truck that comes back to a yard it has already served is a second visit that the plan
 *     does not describe, and inventing a second arrival for it would break the exit criterion for
 *     the sake of a case the itinerary cannot express
 * @param lastFixAt the newest fix instant already applied here. Fixes older than this are ignored,
 *     because the mobile app dumps buffered backlogs out of order and replaying an old fix through
 *     a state machine would walk it backwards
 */
@Document(collection = GeofenceState.COLLECTION)
public record GeofenceState(
    @Id String id,
    String shipmentId,
    String stopId,
    boolean inside,
    Instant enteredAt,
    Instant leftAt,
    boolean arrivalAnnounced,
    Instant arrivalOccurredAt,
    boolean departureAnnounced,
    Instant lastFixAt) {

  public static final String COLLECTION = "geofence.state";

  /** The primary key for a shipment at a stop. */
  public static String idFor(String shipmentId, String stopId) {
    return shipmentId + "|" + stopId;
  }

  /** The state of a shipment at a stop it has never been seen near. */
  public static GeofenceState initial(String shipmentId, String stopId) {
    return new GeofenceState(
        idFor(shipmentId, stopId), shipmentId, stopId, false, null, null, false, null, false, null);
  }

  /** Whether this stop is finished: arrived at, and departed from. */
  public boolean isComplete() {
    return arrivalAnnounced && departureAnnounced;
  }
}
