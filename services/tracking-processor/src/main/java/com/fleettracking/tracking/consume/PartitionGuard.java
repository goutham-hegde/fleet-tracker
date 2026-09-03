package com.fleettracking.tracking.consume;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;

/**
 * Makes redelivery harmless, without paying for it on every message.
 *
 * <h2>The problem</h2>
 *
 * <p>Kafka does not remember which messages a consumer has handled. It remembers one number per
 * partition — the <em>committed offset</em>, meaning "everything before here is done" — and that
 * number is written back to the broker periodically, after a batch of records has been processed.
 * So when a consumer stops without warning, the records it had processed since its last commit are
 * still, as far as the broker knows, outstanding. Whoever picks that partition up next is handed
 * them again. This is not a defect; it is the deliberate choice of <em>at-least-once</em> delivery,
 * and the alternative — committing before doing the work — trades duplicates for lost data, which
 * for a position history is a much worse trade.
 *
 * <p>Ordinarily a database absorbs this: write with a unique key, and the second attempt is
 * rejected. MongoDB will not do that here, because unique indexes are not supported on time-series
 * collections. So the same fix, redelivered, would simply appear in the history twice.
 *
 * <h2>The observation this exploits</h2>
 *
 * <p>Redelivery is not scattered through the stream — it is always a <em>contiguous run at the very
 * start</em> of what a consumer is handed after it is given a partition. The records between the
 * last commit and the moment the previous consumer stopped were processed; everything after that
 * point was not, and never will have been by anyone. So within one partition the sequence is
 * always: some number of already-stored records, then nothing but new ones.
 *
 * <p>That makes the guard cheap. When a partition is assigned, mark it as needing verification.
 * Check the history before storing each of its records. The first record that turns out to be
 * genuinely new proves the redelivered run has ended, so verification is switched off for that
 * partition and never runs again until the next assignment. The steady-state cost is nothing at
 * all; the cost after a restart is one extra indexed lookup per partition per redelivered record,
 * which is a handful.
 *
 * <p>Checking every record for ever would also work, and would double the read load on the busiest
 * path in the platform in order to defend against something that can only happen in the first
 * moments after an assignment.
 *
 * <h2>Why it hangs off the rebalance callback</h2>
 *
 * <p>A <em>rebalance</em> is Kafka redistributing a topic's partitions among the members of a
 * consumer group — it happens when this service starts, when a second instance joins, when one
 * dies, and when a pod is rescheduled. It is the exact moment at which a partition might arrive
 * carrying work someone else had half-finished, so it is the exact moment to re-arm the check.
 * Tying it to process startup instead would miss the case that matters most in a cluster: a
 * partition moving between two instances that are both already running.
 */
public class PartitionGuard implements ConsumerAwareRebalanceListener {

  private static final Logger log = LoggerFactory.getLogger(PartitionGuard.class);

  /**
   * Told when a partition leaves, so it can drop what it remembers about it. This class is the only
   * one in the service that hears about partitions coming and going, and both defences against
   * seeing an event twice are keyed on that lifecycle — so it does the telling rather than
   * registering a second rebalance listener the container would have to be taught about.
   */
  private final RecentEventIds recentEventIds;

  /**
   * Partitions whose next records might already be in the database.
   *
   * <p>Concurrent because a listener container with a concurrency above one runs several consumer
   * threads, each with its own partitions but sharing this object. Entries are only ever keyed by
   * partition, so the threads never contend over the same one.
   */
  private final Set<TopicPartition> unverified = ConcurrentHashMap.newKeySet();

  public PartitionGuard(RecentEventIds recentEventIds) {
    this.recentEventIds = recentEventIds;
  }

  @Override
  public void onPartitionsAssigned(
      Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
    unverified.addAll(partitions);
    log.info(
        "assigned {} partition(s): {}. Verifying against stored history until the first new event"
            + " in each",
        partitions.size(),
        partitions);
  }

  @Override
  public void onPartitionsRevokedAfterCommit(
      Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
    // Offsets have just been committed for these, so nothing is outstanding. Dropping them keeps
    // the set the size of what this instance actually holds rather than everything it ever held.
    partitions.forEach(unverified::remove);
    partitions.forEach(recentEventIds::forget);
  }

  @Override
  public void onPartitionsLost(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
    // Lost, not revoked: the group moved on without us and these offsets were not committed.
    // Whoever is given them will re-arm its own guard on assignment, which is what covers this.
    partitions.forEach(unverified::remove);
    partitions.forEach(recentEventIds::forget);
  }

  /** Whether records from this partition still need checking against stored history. */
  public boolean verifying(TopicPartition partition) {
    return unverified.contains(partition);
  }

  /**
   * Called with the first record of a partition that proved to be genuinely new. Everything after
   * it in that partition is new too, so the checking stops here.
   */
  public void firstNewRecordSeen(TopicPartition partition) {
    if (unverified.remove(partition)) {
      log.info("{} has caught up to new events; duplicate checking off for this assignment", partition);
    }
  }

  /** How many partitions are still being verified. For tests and for logging. */
  public int unverifiedCount() {
    return unverified.size();
  }
}
