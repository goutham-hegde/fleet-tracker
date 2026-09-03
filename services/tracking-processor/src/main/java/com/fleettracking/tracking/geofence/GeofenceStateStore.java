package com.fleettracking.tracking.geofence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Reads and writes what the platform believes about a shipment's stops.
 *
 * <p>One query per position event fetches every stop's state for that shipment at once, rather than
 * one lookup per stop. A five-stop itinerary would otherwise cost five round trips per fix on the
 * busiest path in the platform, which is the sort of thing that is invisible in a test and
 * ruinous under load.
 *
 * <p>Writes happen only when something changed. A truck driving between stops changes nothing here,
 * so the steady state is a read and no write at all; the writes cluster around the few minutes when
 * a vehicle is actually crossing a boundary.
 */
public class GeofenceStateStore {

  private static final Logger log = LoggerFactory.getLogger(GeofenceStateStore.class);

  private final MongoOperations mongo;

  public GeofenceStateStore(MongoOperations mongo) {
    this.mongo = mongo;
  }

  /**
   * Creates the index the per-shipment query needs.
   *
   * <p>Explicitly, at startup, for the same reason the Kafka topics are created by a Job with
   * stated partition counts rather than by auto-creation: a collection that acquires its indexes
   * from whichever query happened to run first is a collection whose performance is an accident.
   */
  public void ensureIndexes() {
    mongo.indexOps(GeofenceState.class)
        .createIndex(new Index().on("shipmentId", Sort.Direction.ASC));
    log.info("geofence state: index on shipmentId present in '{}'", GeofenceState.COLLECTION);
  }

  /**
   * Every stop state stored for one shipment, keyed by stop id.
   *
   * <p>Stops with no stored state are simply absent; the caller starts them from
   * {@link GeofenceState#initial}. Writing an initial state for every stop the first time a
   * shipment is seen would fill the collection with rows that say nothing.
   */
  public Map<String, GeofenceState> forShipment(String shipmentId) {
    List<GeofenceState> states =
        mongo.find(Query.query(Criteria.where("shipmentId").is(shipmentId)), GeofenceState.class);

    Map<String, GeofenceState> byStop = new HashMap<>();
    for (GeofenceState state : states) {
      byStop.put(state.stopId(), state);
    }
    return byStop;
  }

  /**
   * Writes one stop's state.
   *
   * <p>A plain save keyed on the pair, not a conditional update. The forward-only rule that guards
   * the current position is enforced inside {@link Geofencer} instead — it refuses a fix that is
   * not newer than the last one it applied — because here the ordering that matters is over fixes
   * rather than over writes, and only one consumer thread ever holds a given shipment's partition.
   */
  public void save(GeofenceState state) {
    mongo.save(state);
  }

  /** How many stop states are stored. For the heartbeat and for tests. */
  public long count() {
    return mongo.count(new Query(), GeofenceState.class);
  }
}
