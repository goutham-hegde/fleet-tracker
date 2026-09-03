package com.fleettracking.tracking.consume;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.common.TopicPartition;

/**
 * Remembers the event ids seen recently on each partition, so a message the source sent twice is
 * stored once.
 *
 * <h2>The duplicate this exists for</h2>
 *
 * <p>The mobile app is the only feed that repeats itself, and it does so as a matter of course
 * rather than as a fault. A driver's phone loses signal, buffers its fixes, and on reconnect sends
 * the whole backlog at once — including whatever was in flight when the link died and whose
 * acknowledgement never came back. That message is genuinely sent twice, seconds apart.
 *
 * <p>Nothing upstream removes the second copy, and that is deliberate. The gateway derives an event
 * id from the feed, the device and the instant the source stated, which makes the two copies
 * <em>recognisable</em> as one event; it does not hold a dedup table, because a gateway that
 * remembered every message it had ever seen would be a database with an HTTP interface. Recognising
 * the repeat is the gateway's job. Acting on it is this service's.
 *
 * <h2>Why a bounded set and not a database lookup</h2>
 *
 * <p>Asking MongoDB "have I stored this event id already?" on every message would catch every
 * duplicate however far apart the copies arrived. It would also add an indexed read to every
 * position event on the highest-volume path in the platform, permanently, to defend against
 * something that happens to roughly one message in a thousand. That is the same trade
 * {@link PartitionGuard} declines, for the same reason.
 *
 * <p>So this holds a fixed number of recent ids per partition and forgets the oldest. The duplicate
 * it is built for arrives within one backlog burst — seconds, not hours — so a few thousand ids is
 * far more history than the case needs, and the cost is one hash lookup per event and no I/O at
 * all. A duplicate whose copies straddle the whole window is not caught, and would be stored twice.
 * That is a real limit rather than an oversight, and it is the right shape of limit: the failure is
 * an extra measurement in a history that is append-only anyway, not a wrong one.
 *
 * <h2>Per partition, and emptied when a partition leaves</h2>
 *
 * <p>Keyed by partition because that is the unit Kafka hands to exactly one consumer thread at a
 * time, so each inner map is touched by one thread and needs no locking of its own. The outer map
 * does, because partitions are added and removed by the consumer thread while others are running.
 *
 * <p>A partition that is revoked takes its ids with it — see {@link #forget(TopicPartition)}. The
 * instance that receives that partition next cannot use them anyway, and after a rebalance the
 * relevant defence is {@link PartitionGuard}'s, which checks what is actually in the database
 * rather than what this process happens to remember.
 */
public class RecentEventIds {

  /** Ids kept per partition, oldest evicted first. */
  private final int capacity;

  private final Map<TopicPartition, Map<String, Boolean>> byPartition = new ConcurrentHashMap<>();

  public RecentEventIds(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1, was " + capacity);
    }
    this.capacity = capacity;
  }

  /**
   * Records an event id against a partition and says whether it had not been seen before.
   *
   * @return true if this is the first sighting of the id on this partition, false if the id is
   *     already remembered — in which case the caller should not store the event again
   */
  public boolean isFirstSighting(TopicPartition partition, String eventId) {
    Map<String, Boolean> seen = byPartition.computeIfAbsent(partition, key -> newRing());
    return seen.put(eventId, Boolean.TRUE) == null;
  }

  /** Drops everything remembered for a partition this instance no longer holds. */
  public void forget(TopicPartition partition) {
    byPartition.remove(partition);
  }

  /** How many ids are currently remembered for a partition. For tests and for logging. */
  public int rememberedFor(TopicPartition partition) {
    Map<String, Boolean> seen = byPartition.get(partition);
    return seen == null ? 0 : seen.size();
  }

  /**
   * An access-ordered map that evicts its oldest entry once it is full — the smallest correct
   * spelling of a bounded LRU on this platform, and one that allocates nothing per event once it
   * has reached its size.
   */
  private Map<String, Boolean> newRing() {
    return new LinkedHashMap<>(16, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
        return size() > capacity;
      }
    };
  }
}
