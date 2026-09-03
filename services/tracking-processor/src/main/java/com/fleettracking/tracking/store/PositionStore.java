package com.fleettracking.tracking.store;

import com.fleettracking.events.PositionEvent;
import java.time.Clock;
import java.util.Optional;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * The two writes this service exists to perform: append to a shipment's history, and move its
 * current position forward.
 *
 * <p>Both happen for every position event, in that order, and the order matters. If the process
 * dies between them, the measurement is durable and the current position is one event behind, which
 * the next event repairs. The other order would leave a current position pointing at a measurement
 * that is not in the history — a shipment whose "now" cannot be found in its own past, which is a
 * far more confusing thing to debug than being briefly one fix stale.
 */
public class PositionStore {

  private static final Logger log = LoggerFactory.getLogger(PositionStore.class);

  /**
   * Criteria are written against {@code _id} literally rather than against the mapped property
   * name. Spring Data would translate {@code shipmentId} to {@code _id} for
   * {@link CurrentPosition}, because that property carries {@code @Id} — but if that translation
   * ever failed to happen, the conditional upsert below would stop matching the existing document
   * and would insert a <em>new</em> one with a generated id on every event. That failure produces
   * no error and no log line; it produces a collection that quietly grows one document per position
   * instead of one per shipment. Naming the physical field removes the possibility.
   */
  private static final String ID = "_id";

  private final MongoOperations mongo;
  private final Clock clock;

  public PositionStore(MongoOperations mongo, Clock clock) {
    this.mongo = mongo;
    this.clock = clock;
  }

  /**
   * Creates the history collection with its time-series options if it does not exist yet.
   *
   * <p>This is not a convenience. An insert into a missing collection creates an ordinary
   * collection, silently: no buckets, no compression, no automatic {@code {shipmentId: 1, ts: 1}}
   * index, and no error to say so. The only visible symptom would arrive months later as a
   * collection that is far larger and far slower than it should be. Creating it explicitly, before
   * the first insert can happen, is what makes the time-series decision real rather than
   * documented.
   *
   * <p>Idempotent, so it is safe on every start. It does not attempt to convert an existing
   * ordinary collection — a collection cannot be changed into a time-series one in place, and
   * silently continuing against the wrong kind is exactly the failure this method exists to
   * prevent, so it says so loudly instead.
   */
  public void ensureCollections() {
    if (mongo.collectionExists(PositionPoint.COLLECTION)) {
      boolean timeSeries =
          mongo.execute(
              db -> {
                Document info =
                    db.listCollections()
                        .filter(new Document("name", PositionPoint.COLLECTION))
                        .first();
                return info != null && "timeseries".equals(info.getString("type"));
              });
      if (!timeSeries) {
        throw new IllegalStateException(
            "collection '"
                + PositionPoint.COLLECTION
                + "' exists but is not a time-series collection. It was almost certainly created"
                + " by an insert rather than explicitly. Drop it and restart; a collection cannot"
                + " be converted in place.");
      }
      log.info("position history: existing time-series collection '{}'", PositionPoint.COLLECTION);
      return;
    }
    mongo.createCollection(PositionPoint.class);
    log.info("position history: created time-series collection '{}'", PositionPoint.COLLECTION);
  }

  /**
   * Records one position event.
   *
   * @param event a position event that has already been validated by the gateway
   * @param verifyNotAlreadyPresent when true, check the history for this event before appending to
   *     it. Kafka redelivers whatever was not committed when a consumer stopped, so the first
   *     records after a restart or a rebalance may be ones this service already wrote. The check
   *     costs an indexed lookup and the caller turns it off as soon as it is no longer possible for
   *     a record to be a redelivery — see {@code PartitionGuard}. It is not on permanently because
   *     it would double the read load on the busiest path in the platform to defend against
   *     something that can only happen in the first moments after an assignment
   * @return true if a new measurement was appended, false if this event was already in the history
   */
  public boolean record(PositionEvent event, boolean verifyNotAlreadyPresent) {
    boolean fresh = true;
    if (verifyNotAlreadyPresent && containsPoint(event)) {
      log.debug(
          "position {} for shipment {} is already in history; skipping the append",
          event.eventId(),
          event.shipmentId());
      fresh = false;
    } else {
      mongo.insert(PositionPoint.from(event));
    }

    // Run for a duplicate too. The update is conditional on being strictly newer, so re-applying
    // the same event is a no-op, while a current position that was never written because the
    // process died between the two writes is repaired here rather than left one fix behind.
    advanceCurrentPosition(event);
    return fresh;
  }

  /**
   * Whether this exact event is already in the history.
   *
   * <p>Filtered by shipment first so the query is answered from the automatic meta index rather
   * than by unpacking every bucket in the collection. The event id is the discriminator, and it is
   * safe to lean on because the gateway derives it from the feed, the device and the instant the
   * source stated — a message resent by a carrier produces the same id, so "already seen" is a
   * property of the event rather than of the delivery.
   */
  public boolean containsPoint(PositionEvent event) {
    Query query =
        Query.query(
            Criteria.where("shipmentId")
                .is(event.shipmentId())
                .and(ID)
                .is(event.eventId()));
    return mongo.exists(query, PositionPoint.class);
  }

  /**
   * Moves a shipment's current position forward, if this event is newer than what is stored.
   *
   * <h2>The idiom, and why it needs a catch block</h2>
   *
   * <p>The filter matches "the document for this shipment, whose stored {@code occurredAt} is older
   * than this event's". Combined with {@code upsert}, MongoDB does one of three things:
   *
   * <ul>
   *   <li>the document exists and is older — it is updated. The normal case;
   *   <li>no document exists — the filter matches nothing, so the upsert inserts, taking
   *       {@code _id} from the equality half of the filter. The first event for a shipment;
   *   <li>the document exists and is <em>newer or equal</em> — the filter still matches nothing, so
   *       the upsert tries to insert, and collides with the existing {@code _id}.
   * </ul>
   *
   * <p>That third case is a stale or duplicate event arriving, which is routine rather than
   * exceptional, and it surfaces as a duplicate-key error. Catching it is the standard way to spell
   * "update only if newer" in a database with no compare-and-set operator, and it is safe precisely
   * because the shipment id is the primary key: the losing write cannot create a second document
   * for the same shipment, it can only fail.
   */
  private void advanceCurrentPosition(PositionEvent event) {
    Query newerThanStored =
        Query.query(
            Criteria.where(ID)
                .is(event.shipmentId())
                .and("occurredAt")
                .lt(event.occurredAt()));

    Update update =
        new Update()
            .set("eventId", event.eventId())
            .set("vehicleId", event.vehicleId())
            .set("deviceId", event.deviceId())
            .set("occurredAt", event.occurredAt())
            .set("receivedAt", event.receivedAt())
            .set("updatedAt", clock.instant())
            // longitude first: GeoJSON, not the order people say it in.
            .set(
                "location",
                new GeoJsonPoint(event.position().longitude(), event.position().latitude()))
            .set("speedKph", event.speedKph())
            .set("headingDegrees", event.headingDegrees())
            .set("odometerKm", event.odometerKm())
            .set("accuracyMeters", event.accuracyMeters())
            .set("source", event.raw().source().name());

    try {
      mongo.upsert(newerThanStored, update, CurrentPosition.class);
    } catch (DuplicateKeyException stale) {
      log.debug(
          "position {} for shipment {} is not newer than the stored current position; ignored",
          event.eventId(),
          event.shipmentId());
    }
  }

  /** The current position of one shipment, if this service has seen any position for it. */
  public Optional<CurrentPosition> currentPosition(String shipmentId) {
    return Optional.ofNullable(mongo.findById(shipmentId, CurrentPosition.class));
  }

  /** How many measurements are in the history. Used by operational tooling and by tests. */
  public long historyCount() {
    return mongo.count(new Query(), PositionPoint.class);
  }

  /** How many shipments have a current position. */
  public long trackedShipments() {
    return mongo.count(new Query(), CurrentPosition.class);
  }
}
