package com.fleettracking.tracking.consume;

import com.fleettracking.events.EventJson;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.Topics;
import com.fleettracking.tracking.store.PositionStore;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.core.JacksonException;

/**
 * Reads position events and stores them.
 *
 * <h2>What a consumer group is, and why the group id matters more than the class</h2>
 *
 * <p>Every consumer belongs to a named group, and Kafka divides a topic's partitions among that
 * group's members so that each partition is read by exactly one member. Two instances of this
 * service sharing a group id therefore share the work — the twelve partitions of the position topic
 * are split between them — while a service with a <em>different</em> group id gets its own copy of
 * every message and its own position in the stream. That is the whole mechanism by which the
 * archiver and the exception service will later read the same events without any of the three
 * knowing the others exist, and by which this service can be scaled to twelve pods without
 * duplicating a single write.
 *
 * <p>It also sets a ceiling: a group can never have more usefully busy members than the topic has
 * partitions. The thirteenth pod would sit idle. That is why the partition count was chosen
 * deliberately when the topic was created rather than inherited from a default.
 *
 * <h2>Why the payload is a String</h2>
 *
 * <p>The container could be configured to deserialize JSON into a {@link PositionEvent} before this
 * method is called, and that would be a mistake for the same reason the gateway binds request
 * bodies as strings: a record that fails to deserialize would fail <em>inside the framework</em>,
 * before any code here runs, and the dead-letter path below — the one that exists precisely for
 * that record — would never see it. Parsing here also keeps every service on the single
 * {@code EventJson} mapper instead of on whatever a deserializer would construct for itself.
 *
 * <h2>The two ways the same event can arrive twice</h2>
 *
 * <p>They are different problems with different answers, which is why there are two collaborators
 * rather than one. {@link PartitionGuard} covers <em>redelivery</em>: Kafka handing back records
 * that were processed but whose offsets were never committed. That happens only in the moments
 * after a partition is assigned, and it is checked against what is actually in the database.
 * {@link RecentEventIds} covers a <em>duplicate at the source</em>: the mobile app resending a
 * message it never saw acknowledged. That can happen at any point in a perfectly healthy run, and
 * it is checked against a bounded set held in memory. Neither mechanism would catch the other's
 * case.
 */
public class PositionConsumer {

  private static final Logger log = LoggerFactory.getLogger(PositionConsumer.class);

  private final PositionStore store;
  private final PartitionGuard guard;
  private final RecentEventIds recentEventIds;
  private final TrackingDeadLetters deadLetters;

  private final AtomicLong stored = new AtomicLong();
  private final AtomicLong duplicates = new AtomicLong();
  private final AtomicLong deadLettered = new AtomicLong();

  public PositionConsumer(
      PositionStore store,
      PartitionGuard guard,
      RecentEventIds recentEventIds,
      TrackingDeadLetters deadLetters) {
    this.store = store;
    this.guard = guard;
    this.recentEventIds = recentEventIds;
    this.deadLetters = deadLetters;
  }

  /**
   * Handles one position event.
   *
   * <p>The offset for this record is committed by the container only after this method returns
   * normally. Everything that must survive a crash therefore has to be durable before it returns,
   * and anything it throws will be retried rather than skipped — see the error handler in
   * {@code TrackingConfig}.
   */
  @KafkaListener(topics = Topics.POSITION, id = "position-events")
  public void onPositionEvent(ConsumerRecord<String, String> record) {
    PositionEvent event;
    try {
      event = EventJson.mapper().readValue(record.value(), PositionEvent.class);
    } catch (JacksonException malformed) {
      // Identical bytes parse identically for ever, so this is not retryable. Same rule as the
      // gateway's: a message that cannot be understood is set aside, not looped on.
      deadLetters.setAside(record, "UNPARSEABLE", malformed.getOriginalMessage());
      deadLettered.incrementAndGet();
      return;
    }

    String missing = firstMissingField(event);
    if (missing != null) {
      // The gateway validates every envelope before publishing, so reaching this line means
      // something produced to the canonical topic without going through it. Worth setting aside
      // loudly rather than storing a half-event that breaks a map query three weeks from now.
      deadLetters.setAside(record, "INCOMPLETE", "missing " + missing);
      deadLettered.incrementAndGet();
      return;
    }

    TopicPartition partition = new TopicPartition(record.topic(), record.partition());

    if (!recentEventIds.isFirstSighting(partition, event.eventId())) {
      // The same event, sent twice by the source -- the mobile app's backlog resending a message
      // whose acknowledgement it never received. Both writes for it have already happened in this
      // process, so there is nothing to repair and nothing to store; see RecentEventIds.
      duplicates.incrementAndGet();
      return;
    }

    boolean verify = guard.verifying(partition);

    boolean fresh = store.record(event, verify);

    if (fresh) {
      stored.incrementAndGet();
      if (verify) {
        // The redelivered run has ended; everything after this in the partition is new.
        guard.firstNewRecordSeen(partition);
      }
    } else {
      duplicates.incrementAndGet();
    }
  }

  /**
   * The fields nothing downstream can work without.
   *
   * <p>Not a full re-validation. The envelope's constraints are declared once, in {@code
   * libs/events}, and enforced once, by the gateway, which is what makes "a message on this topic
   * has already been checked" an invariant rather than a hope. Repeating the whole check here would
   * be a second, drifting definition of valid. These are different: without them there is nothing to
   * key the write on, nothing to draw, and nothing to replay from, so a missing one is a structural
   * problem rather than a range problem.
   */
  private static String firstMissingField(PositionEvent event) {
    if (event.shipmentId() == null || event.shipmentId().isBlank()) {
      return "shipmentId";
    }
    if (event.occurredAt() == null) {
      return "occurredAt";
    }
    if (event.position() == null) {
      return "position";
    }
    if (event.raw() == null) {
      // Dereferenced when the measurement is built. Without this check a null here would throw
      // inside the store, be treated as retryable, and stall the partition for ever on a record
      // that could never succeed -- the poison pill, arriving through the one door left open.
      return "raw";
    }
    return null;
  }

  /** Measurements written. */
  public long storedCount() {
    return stored.get();
  }

  /** Redeliveries and source duplicates recognised, and not written again. */
  public long duplicateCount() {
    return duplicates.get();
  }

  /** Records set aside as unprocessable. */
  public long deadLetteredCount() {
    return deadLettered.get();
  }

  /** A one-line summary, logged periodically by {@code TrackingConfig}. */
  public String summary() {
    return "stored=" + stored.get() + " duplicates=" + duplicates.get() + " dlq=" + deadLettered.get();
  }
}
