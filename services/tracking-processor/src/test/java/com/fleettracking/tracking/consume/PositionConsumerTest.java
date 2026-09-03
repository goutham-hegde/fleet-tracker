package com.fleettracking.tracking.consume;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.EventJson;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.Topics;
import com.fleettracking.tracking.Positions;
import com.fleettracking.tracking.geofence.GeofenceService;
import com.fleettracking.tracking.store.PositionStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;
import tools.jackson.databind.node.ObjectNode;

/**
 * The consumer's decisions, with no broker and no database.
 *
 * <p>What is being tested here is a set of choices — parse or set aside, store or recognise as a
 * duplicate, keep verifying or stop — and every one of them is decided before anything is written.
 * A test that needed two containers to check which branch was taken would be slower and would prove
 * less, because a failure could then be in the container.
 */
class PositionConsumerTest {

  private RecordingStore store;
  private RecordingDeadLetters deadLetters;
  private PartitionGuard guard;
  private RecentEventIds recentEventIds;
  private RecordingGeofence geofence;
  private PositionConsumer consumer;

  private static final TopicPartition PARTITION = new TopicPartition(Topics.POSITION, 4);

  @BeforeEach
  void setUp() {
    store = new RecordingStore();
    deadLetters = new RecordingDeadLetters();
    recentEventIds = new RecentEventIds(64);
    guard = new PartitionGuard(recentEventIds);
    geofence = new RecordingGeofence();
    consumer = new PositionConsumer(store, guard, recentEventIds, geofence, deadLetters);
  }

  @Test
  void storesAValidPositionEvent() {
    PositionEvent event = Positions.at("SHP-1", Duration.ZERO);

    consumer.onPositionEvent(recordFor(event, 0));

    assertThat(store.recorded).hasSize(1);
    assertThat(store.recorded.getFirst().eventId()).isEqualTo(event.eventId());
    assertThat(consumer.storedCount()).isEqualTo(1);
    assertThat(deadLetters.sent).isEmpty();
  }

  @Test
  void setsAsideARecordThatIsNotJson() {
    consumer.onPositionEvent(
        new ConsumerRecord<>(Topics.POSITION, 4, 0L, "SHP-1", "this is not json at all"));

    assertThat(store.recorded).isEmpty();
    assertThat(deadLetters.sent).hasSize(1);
    assertThat(deadLetters.sent.getFirst().reason()).isEqualTo("UNPARSEABLE");
    assertThat(consumer.deadLetteredCount()).isEqualTo(1);
  }

  /**
   * Valid JSON that is not a usable position event. This is the case a JSON deserializer configured
   * on the container would have swallowed before the listener ever ran.
   *
   * <p>The three inputs below are built by taking a real event and deleting one field, rather than
   * by writing the JSON out by hand. The hand-written version was written first and every one of
   * them failed, because a canonical event carries a {@code "type"} discriminator that nobody
   * remembers when typing an example — so the tests were exercising the unparseable path while
   * claiming to exercise the incomplete one. Deriving the input from the real serializer means a
   * change to the envelope cannot leave these quietly testing something else.
   */
  @Test
  void setsAsideAnEventWithNoShipmentId() {
    consumer.onPositionEvent(recordWithout("shipmentId"));

    assertThat(store.recorded).isEmpty();
    assertThat(deadLetters.sent.getFirst().reason()).isEqualTo("INCOMPLETE");
    assertThat(deadLetters.sent.getFirst().detail()).contains("shipmentId");
  }

  @Test
  void setsAsideAnEventWithNoPosition() {
    consumer.onPositionEvent(recordWithout("position"));

    assertThat(store.recorded).isEmpty();
    assertThat(deadLetters.sent.getFirst().detail()).contains("position");
  }

  /** A null raw payload would otherwise throw deep in the store and be retried for ever. */
  @Test
  void setsAsideAnEventWithNoRawPayload() {
    consumer.onPositionEvent(recordWithout("raw"));

    assertThat(store.recorded).isEmpty();
    assertThat(deadLetters.sent.getFirst().detail()).contains("raw");
  }

  /** Nothing has been assigned, so nothing is being verified and no lookups are wasted. */
  @Test
  void doesNotVerifyWhenNoPartitionHasBeenAssigned() {
    consumer.onPositionEvent(recordFor(Positions.at("SHP-1", Duration.ZERO), 0));

    assertThat(store.verifyFlags).containsExactly(false);
  }

  /**
   * The guard's whole behaviour, in the order it actually happens: a partition is assigned, the
   * first records are ones already stored, and the first genuinely new record turns the checking
   * off for good.
   */
  @Test
  void verifiesAfterAnAssignmentAndStopsAtTheFirstNewRecord() {
    PositionEvent alreadyStored = Positions.at("SHP-1", Duration.ZERO);
    PositionEvent alsoStored = Positions.at("SHP-1", Duration.ofMinutes(1));
    store.pretendAlreadyStored(alreadyStored, alsoStored);

    guard.onPartitionsAssigned(null, List.of(PARTITION));
    assertThat(guard.verifying(PARTITION)).isTrue();

    consumer.onPositionEvent(recordFor(alreadyStored, 10));
    consumer.onPositionEvent(recordFor(alsoStored, 11));
    consumer.onPositionEvent(recordFor(Positions.at("SHP-1", Duration.ofMinutes(2)), 12));
    consumer.onPositionEvent(recordFor(Positions.at("SHP-1", Duration.ofMinutes(3)), 13));

    // Only the first three were checked; once one proved new, checking stopped.
    assertThat(store.verifyFlags).containsExactly(true, true, true, false);
    assertThat(guard.verifying(PARTITION)).isFalse();

    assertThat(consumer.duplicateCount()).isEqualTo(2);
    assertThat(consumer.storedCount()).isEqualTo(2);
  }

  /** A partition given up and handed back is verified again — the records may not be ours. */
  @Test
  void reArmsWhenAPartitionIsAssignedAgain() {
    guard.onPartitionsAssigned(null, List.of(PARTITION));
    consumer.onPositionEvent(recordFor(Positions.at("SHP-1", Duration.ZERO), 0));
    assertThat(guard.verifying(PARTITION)).isFalse();

    guard.onPartitionsRevokedAfterCommit(null, List.of(PARTITION));
    guard.onPartitionsAssigned(null, List.of(PARTITION));

    assertThat(guard.verifying(PARTITION)).isTrue();
  }

  /** Partitions are tracked independently; one catching up says nothing about the others. */
  @Test
  void tracksPartitionsSeparately() {
    TopicPartition other = new TopicPartition(Topics.POSITION, 7);
    guard.onPartitionsAssigned(null, List.of(PARTITION, other));

    consumer.onPositionEvent(recordFor(Positions.at("SHP-1", Duration.ZERO), 0));

    assertThat(guard.verifying(PARTITION)).isFalse();
    assertThat(guard.verifying(other)).isTrue();
    assertThat(guard.unverifiedCount()).isEqualTo(1);
  }

  /** A real serialized position event with one field deleted. */
  /**
   * The same event delivered twice on a healthy partition — the mobile app resending a backlogged
   * message whose acknowledgement it never saw.
   *
   * <p>The partition is not being verified here, and that is the point: this is not redelivery
   * after a rebalance, it is a duplicate at the source arriving in the middle of an ordinary run,
   * and the database is never asked about it.
   */
  @Test
  void storesASourceDuplicateOnlyOnce() {
    PositionEvent event = Positions.at("SHP-DUP", Duration.ZERO);

    consumer.onPositionEvent(recordFor(event, 0));
    consumer.onPositionEvent(recordFor(event, 1));

    assertThat(store.recorded).hasSize(1);
    assertThat(consumer.storedCount()).isEqualTo(1);
    assertThat(consumer.duplicateCount()).isEqualTo(1);
    assertThat(deadLetters.sent).isEmpty();
  }

  /** Two different events are not mistaken for one another just because they share a partition. */
  @Test
  void distinctEventsOnOnePartitionAreAllStored() {
    consumer.onPositionEvent(recordFor(Positions.at("SHP-DUP", Duration.ZERO), 0));
    consumer.onPositionEvent(recordFor(Positions.at("SHP-DUP", Duration.ofMinutes(1)), 1));

    assertThat(store.recorded).hasSize(2);
    assertThat(consumer.duplicateCount()).isZero();
  }

  /**
   * A partition taken away is forgotten.
   *
   * <p>What this process remembers about a partition it no longer holds says nothing about what was
   * committed, so it is dropped. If that partition comes back, the defence that matters is the
   * guard's, which asks the database rather than memory.
   */
  @Test
  void forgetsRememberedIdsWhenAPartitionIsRevoked() {
    PositionEvent event = Positions.at("SHP-DUP", Duration.ZERO);
    consumer.onPositionEvent(recordFor(event, 0));
    assertThat(recentEventIds.rememberedFor(PARTITION)).isEqualTo(1);

    guard.onPartitionsRevokedAfterCommit(null, List.of(PARTITION));

    assertThat(recentEventIds.rememberedFor(PARTITION)).isZero();
    consumer.onPositionEvent(recordFor(event, 1));
    assertThat(store.recorded).hasSize(2);
  }

  /**
   * Geofencing is offered only what was stored.
   *
   * <p>A record that was set aside, or one recognised as a duplicate, must not reach it: the first
   * is not a position at all and the second would be re-evaluated to reach an answer already
   * reached. This is the seam between the two halves of the service, so it is asserted rather than
   * assumed.
   */
  @Test
  void geofencesOnlyWhatItActuallyStored() {
    PositionEvent event = Positions.at("SHP-GEO", Duration.ZERO);

    consumer.onPositionEvent(recordFor(event, 0));
    consumer.onPositionEvent(recordFor(event, 1));
    consumer.onPositionEvent(
        new ConsumerRecord<>(Topics.POSITION, 4, 2L, "SHP-GEO", "not json"));

    assertThat(geofence.applied).hasSize(1);
    assertThat(geofence.applied.getFirst().eventId()).isEqualTo(event.eventId());
  }

  private static ConsumerRecord<String, String> recordWithout(String field) {
    ObjectNode node =
        (ObjectNode)
            EventJson.mapper()
                .readTree(EventJson.mapper().writeValueAsString(Positions.at("SHP-1", Duration.ZERO)));
    node.remove(field);
    return new ConsumerRecord<>(PARTITION.topic(), PARTITION.partition(), 0L, "SHP-1", node.toString());
  }

  private static ConsumerRecord<String, String> recordFor(PositionEvent event, long offset) {
    return new ConsumerRecord<>(
        PARTITION.topic(),
        PARTITION.partition(),
        offset,
        event.shipmentId(),
        EventJson.mapper().writeValueAsString(event));
  }

  /**
   * A store that records what it was asked to do. Extending the real class rather than an
   * interface: there is one implementation, and inventing an interface so that a test can have a
   * second one is how a codebase ends up with an interface per class.
   */
  private static final class RecordingStore extends PositionStore {

    private final List<PositionEvent> recorded = new ArrayList<>();
    private final List<Boolean> verifyFlags = new ArrayList<>();
    private final Set<String> existing = new LinkedHashSet<>();

    RecordingStore() {
      super((MongoOperations) null, null);
    }

    void pretendAlreadyStored(PositionEvent... events) {
      for (PositionEvent event : events) {
        existing.add(event.eventId());
      }
    }

    @Override
    public boolean record(PositionEvent event, boolean verifyNotAlreadyPresent) {
      verifyFlags.add(verifyNotAlreadyPresent);
      if (verifyNotAlreadyPresent && existing.contains(event.eventId())) {
        return false;
      }
      recorded.add(event);
      return true;
    }
  }

  /** Records what geofencing was asked to evaluate, and does nothing else. */
  private static final class RecordingGeofence extends GeofenceService {

    private final List<PositionEvent> applied = new ArrayList<>();

    private RecordingGeofence() {
      super(null, null, null, null);
    }

    @Override
    public void apply(PositionEvent event) {
      applied.add(event);
    }
  }

  private record DeadLetterCall(String reason, String detail) {}

  private static final class RecordingDeadLetters extends TrackingDeadLetters {

    private final List<DeadLetterCall> sent = new ArrayList<>();

    RecordingDeadLetters() {
      super(null, 0);
    }

    @Override
    public void setAside(ConsumerRecord<String, String> record, String reason, String detail) {
      sent.add(new DeadLetterCall(reason, detail));
    }
  }
}
