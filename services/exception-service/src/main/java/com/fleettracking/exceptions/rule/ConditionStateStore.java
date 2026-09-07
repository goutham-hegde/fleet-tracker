package com.fleettracking.exceptions.rule;

import com.fleettracking.events.ExceptionType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoOperations;

/**
 * Holds each rule's working state: in memory while a condition is developing, in MongoDB whenever it
 * changes shape.
 *
 * <h2>Why a cache is allowed here when three other stores refuse one</h2>
 *
 * <p>The identity resolver, the itinerary store and the schema store all deliberately query every
 * time. This one caches, and the distinction is not about speed — it is about who owns the data.
 * Those three read <b>reference data somebody else maintains</b>, where a cache buys one indexed
 * lookup and pays with a window in which the service knowingly works from a plan that has changed.
 * This is the consumer's <b>own working state</b>, written by the single process that holds the
 * shipment's partition and read by nothing else. There is no one to be stale with respect to.
 *
 * <p>It is the same argument the ETA state store makes, and the two are the only caches in the
 * platform for the same reason.
 *
 * <h2>Written on transition, not per event</h2>
 *
 * <p>A rule looks at every event and almost always concludes nothing. Persisting after each one
 * would add a MongoDB write per rule per position event to protect a value that changes only when a
 * condition starts or stops. So the caller persists when the shape changed — the condition began, it
 * ended, or it has now been confirmed into an incident — and otherwise only refreshes the cache.
 *
 * <p>What that costs is precise and small: a restart loses how far a still-developing condition had
 * got, so its clock starts again and confirmation takes up to one further tolerance period. What it
 * saves is the busiest write path in the platform. The onset of a condition <em>is</em> durable,
 * because that is the transition that gets written.
 *
 * <h2>Bounded, and eviction is harmless</h2>
 *
 * <p>Least-recently-used, so a process that has seen a hundred thousand loads holds a working set
 * rather than all of them. An evicted state is in MongoDB and is read back on that shipment's next
 * event — the eviction costs a query, never a fact.
 */
public class ConditionStateStore {

  private final MongoOperations mongo;
  private final Map<String, ConditionState> cache;

  public ConditionStateStore(MongoOperations mongo, int cacheSize) {
    this.mongo = mongo;
    this.cache =
        Collections.synchronizedMap(
            new LinkedHashMap<>(Math.max(16, cacheSize / 4), 0.75f, true) {
              @Override
              protected boolean removeEldestEntry(Map.Entry<String, ConditionState> eldest) {
                return size() > cacheSize;
              }
            });
  }

  /**
   * The state for one shipment under one rule, from memory, from MongoDB, or fresh.
   *
   * <p>A shipment nothing has ever been concluded about gets an initial state rather than an empty
   * optional, so a rule never has to describe the difference between "no condition" and "no record
   * of a condition". They are the same thing.
   */
  public ConditionState get(String shipmentId, ExceptionType type) {
    String id = ConditionState.idFor(shipmentId, type);
    ConditionState cached = cache.get(id);
    if (cached != null) {
      return cached;
    }
    ConditionState stored = mongo.findById(id, ConditionState.class);
    ConditionState state = stored == null ? ConditionState.initial(shipmentId, type) : stored;
    cache.put(id, state);
    return state;
  }

  /** Updates memory only. For an event that advanced a rule's clock and nothing else. */
  public void remember(ConditionState state) {
    cache.put(state.id(), state);
  }

  /** Updates memory and MongoDB. For a condition that has just begun, ended, or been confirmed. */
  public void persist(ConditionState state) {
    cache.put(state.id(), state);
    mongo.save(state);
  }

  /** How many states are held in memory. Reported by the heartbeat. */
  public int cached() {
    return cache.size();
  }
}
