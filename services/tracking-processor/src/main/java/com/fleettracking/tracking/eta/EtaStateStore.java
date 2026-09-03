package com.fleettracking.tracking.eta;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoOperations;

/**
 * Holds each shipment's estimate and the model behind it — in memory while it is being worked on,
 * in MongoDB once it is worth keeping.
 *
 * <h2>Why there is a cache here when nothing else in this service has one</h2>
 *
 * <p>The itinerary store and the identity resolver both looked at caching and rejected it, and the
 * reasoning was the same both times: the thing being read is <em>reference data maintained by
 * somebody else</em>, so a cache buys speed by knowingly working from a plan that may have changed.
 * Nothing about that applies here. This is not reference data; it is this consumer's own working
 * state, written by exactly one process — the one holding the shipment's partition — and read by
 * nobody else. There is no version of it that can go stale behind our back.
 *
 * <p>What the cache buys is the opposite of staleness. The speed model is advanced by every single
 * fix, and persisting it every time would add a third write to the busiest path in the platform,
 * where there are currently two. Keeping it in memory and writing only when an estimate is
 * published costs a few writes per leg instead of thousands, and follows the rule the geofence
 * state already set: pay for changes, not for arithmetic.
 *
 * <h2>What a restart or a rebalance costs</h2>
 *
 * <p>A miss reads from MongoDB, so a process that has just started — or one that has just been
 * handed a partition another instance was holding — picks up the model as it stood at the last
 * publish rather than at the last fix. That is a few minutes of staleness in a quantity that is
 * deliberately smoothed over minutes, and the confidence on the next event reflects it. The
 * alternative, a per-fix write, would be paying continuously to protect a number that reconstructs
 * itself in a quarter of an hour of driving.
 *
 * <p>The map is bounded and evicts the least recently used shipment, so a long-running process that
 * has seen a hundred thousand loads holds a working set rather than all of them. An eviction is not
 * a loss: the state is in MongoDB, and the next fix for that shipment reads it back.
 */
public class EtaStateStore {

  private final MongoOperations mongo;
  private final Clock clock;
  private final Map<String, EtaState> cache;

  public EtaStateStore(MongoOperations mongo, Clock clock, int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be positive: " + capacity);
    }
    this.mongo = mongo;
    this.clock = clock;
    // Access-ordered, so the eviction is by least recently used rather than by insertion order:
    // a shipment reporting every ten seconds must not be evicted because it was first seen a long
    // time ago. Not synchronized, because a partition is held by exactly one consumer thread; if
    // listener concurrency is raised this needs the same treatment RecentEventIds gives it.
    this.cache =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<String, EtaState> eldest) {
            return size() > capacity;
          }
        };
  }

  /**
   * The state for a shipment: from memory, else from MongoDB, else fresh.
   *
   * <p>A shipment with no stored state is not an error — it is one whose first position has just
   * arrived. {@link EtaState#initial} says explicitly that nothing has been learned, which is what
   * lets the first estimate report a low confidence instead of presenting the nominal speed as if
   * it had been measured.
   */
  public EtaState forShipment(String shipmentId) {
    EtaState cached = cache.get(shipmentId);
    if (cached != null) {
      return cached;
    }
    EtaState stored = mongo.findById(shipmentId, EtaState.class);
    EtaState state = stored != null ? stored : EtaState.initial(shipmentId);
    cache.put(shipmentId, state);
    return state;
  }

  /** Keeps the advanced model to hand without writing it. Called for every position event. */
  public void remember(EtaState state) {
    cache.put(state.shipmentId(), state);
  }

  /**
   * Writes the state, stamping the wall-clock time of the write.
   *
   * <p>Called only when an estimate is published. {@code updatedAt} is stamped here rather than in
   * the calculator so that the calculator stays free of the clock — every other instant it handles
   * is event time, and one wall-clock reading in the middle of that would be the thing that makes
   * a replay stop reproducing its original result.
   */
  public void save(EtaState state) {
    EtaState written =
        new EtaState(
            state.shipmentId(),
            state.stopId(),
            state.estimatedArrival(),
            state.remainingKm(),
            state.confidence(),
            state.expectedSpeedKph(),
            state.movingSeconds(),
            state.lastMovingAt(),
            state.lastFixAt(),
            clock.instant());
    mongo.save(written);
    cache.put(written.shipmentId(), written);
  }

  /** How many shipments have an estimate stored. For the heartbeat and for tests. */
  public long count() {
    return mongo.getCollection(EtaState.COLLECTION).countDocuments();
  }
}
