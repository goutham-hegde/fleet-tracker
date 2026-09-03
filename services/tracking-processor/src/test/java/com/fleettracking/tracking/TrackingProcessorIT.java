package com.fleettracking.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fleettracking.events.EventJson;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.Topics;
import com.fleettracking.tracking.consume.TrackingTopics;
import com.fleettracking.tracking.store.CurrentPosition;
import com.fleettracking.tracking.store.PositionPoint;
import com.fleettracking.tracking.store.PositionStore;
import com.mongodb.client.MongoClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The whole processor: a real broker in, a real database out.
 *
 * <p>The unit tests prove which branch the consumer takes and the store tests prove what MongoDB
 * does with a write. Neither can prove that this service subscribes to the topic it thinks it does,
 * that its group actually receives an assignment, that a record published by something else
 * deserializes into the envelope this code expects, or that the offsets it commits mean what the
 * restart behaviour assumes. Every one of those is a wiring question, and wiring questions are only
 * answered by wiring.
 *
 * <p>The two containers are pinned to the versions the cluster runs. A test against a different
 * broker or a different server is a test of a different system.
 */
@SpringBootTest
@Testcontainers
class TrackingProcessorIT {

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

  @Autowired private PositionStore store;
  @Autowired private MongoOperations mongo;
  @Autowired private MongoClient mongoClient;

  private static Producer<String, String> producer;

  @DynamicPropertySource
  static void containerAddresses(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    // spring.mongodb, not spring.data.mongodb: Boot 4 deprecated the Spring Data namespace for
    // connection settings at level "error", so the old names bind to nothing at all and the
    // default mongodb://localhost/test would stay in place -- against this machine's unrelated,
    // real MongoDB, which would answer every query and let this test pass while proving nothing.
    registry.add("spring.mongodb.uri", MONGO::getConnectionString);
    registry.add("spring.mongodb.database", () -> "fleet");
    // The heartbeat is operational noise in a test, and its store queries would race the
    // assertions for the connection pool.
    registry.add("fleet.tracking.heartbeat-interval", () -> "1h");
  }

  /**
   * Topics are created before the context starts, with the partition counts the cluster uses.
   *
   * <p>Not left to auto-creation, which is off on the cluster anyway: it would produce a
   * single-partition topic, and a test that only ever exercises one partition cannot notice a guard
   * that tracks partitions incorrectly. This runs in a static initializer rather than in
   * {@code @BeforeAll} because the Spring context is built first, and the service is configured to
   * fail fast when a topic it subscribes to does not exist.
   */
  @BeforeAll
  static void createTopicsAndProducer() throws Exception {
    try (Admin admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      try {
        admin
            .createTopics(
                List.of(
                    new NewTopic(Topics.POSITION, 12, (short) 1),
                    new NewTopic(TrackingTopics.DEAD_LETTER, 3, (short) 1)))
            .all()
            .get();
      } catch (ExecutionException e) {
        if (!(e.getCause() instanceof TopicExistsException)) {
          throw e;
        }
      }
    }

    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    producer = new KafkaProducer<>(props);
  }

  @BeforeEach
  void clearCollections() {
    mongo.remove(new Query(), PositionPoint.COLLECTION);
    mongo.remove(new Query(), CurrentPosition.COLLECTION);
  }

  /**
   * The destination is asserted, not assumed — see the note in {@code PositionStoreIT}. In S8 a
   * service silently connected to this machine's unrelated MongoDB and its tests passed.
   */
  @Test
  void talksToTheContainersAndNotToAnythingLocal() {
    int port =
        mongoClient.getClusterDescription().getServerDescriptions().getFirst().getAddress().getPort();
    assertThat(port).isEqualTo(MONGO.getFirstMappedPort()).isNotEqualTo(27017);
    assertThat(KAFKA.getBootstrapServers()).doesNotContain(":9092");
  }

  /**
   * The application is still running.
   *
   * <p>A Spring Boot application with no web server exits the moment {@code main} returns unless
   * something holds a non-daemon thread — and it does so with a clean startup log, a clean shutdown
   * log and no error anywhere. The claim that the Kafka listener container supplies that thread is
   * a claim about a library, so it is checked rather than believed: if it were false, this context
   * would have stopped before the assertion ran.
   */
  @Test
  void staysAliveWithNoWebServer() throws Exception {
    publish(Positions.at("SHP-ALIVE", Duration.ZERO));
    awaitHistoryCount(1);

    Thread.sleep(1_000);

    publish(Positions.at("SHP-ALIVE", Duration.ofMinutes(1)));
    awaitHistoryCount(2);
  }

  @Test
  void storesAPublishedPositionAsATimeSeriesMeasurement() {
    PositionEvent event = Positions.at("SHP-A", Duration.ZERO, 41.8781, -87.6298);

    publish(event);

    awaitStored(1, "SHP-A", event.occurredAt());
    CurrentPosition current = store.currentPosition("SHP-A").orElseThrow();
    assertThat(current.eventId()).isEqualTo(event.eventId());
    assertThat(current.vehicleId()).isEqualTo(event.vehicleId());
    assertThat(current.location().getX()).isEqualTo(-87.6298);
    assertThat(current.location().getY()).isEqualTo(41.8781);

    assertThat(collectionType(PositionPoint.COLLECTION)).isEqualTo("timeseries");
  }

  /**
   * A shipment's history grows while its current position tracks the newest fix. This is M3's first
   * exit criterion, in miniature and without the simulator.
   */
  @Test
  void historyGrowsAndCurrentPositionFollows() {
    for (int minute = 0; minute < 10; minute++) {
      publish(Positions.at("SHP-B", Duration.ofMinutes(minute), 40.0 + minute * 0.01, -88.0));
    }

    awaitStored(10, "SHP-B", Positions.T0.plus(Duration.ofMinutes(9)));
    CurrentPosition current = store.currentPosition("SHP-B").orElseThrow();
    assertThat(current.occurredAt()).isEqualTo(Positions.T0.plus(Duration.ofMinutes(9)));
    assertThat(current.location().getY()).isEqualTo(40.09);
    assertThat(store.trackedShipments()).isEqualTo(1);
  }

  /**
   * The mobile feed's backlog, end to end: the burst arrives out of order, every fix is kept, and
   * the map does not jump backwards.
   */
  @Test
  void anOutOfOrderBurstIsStoredWithoutMovingTheCurrentPositionBackwards() {
    publish(Positions.at("SHP-C", Duration.ofMinutes(20), 45.0, -90.0));
    awaitStored(1, "SHP-C", Positions.T0.plus(Duration.ofMinutes(20)));

    publish(Positions.at("SHP-C", Duration.ofMinutes(5), 44.0, -90.0));
    publish(Positions.at("SHP-C", Duration.ofMinutes(12), 44.5, -90.0));
    publish(Positions.at("SHP-C", Duration.ofMinutes(2), 43.5, -90.0));

    awaitStored(4, "SHP-C", Positions.T0.plus(Duration.ofMinutes(20)));
    CurrentPosition current = store.currentPosition("SHP-C").orElseThrow();
    assertThat(current.occurredAt()).isEqualTo(Positions.T0.plus(Duration.ofMinutes(20)));
    assertThat(current.location().getY()).isEqualTo(45.0);
  }

  /**
   * The same event published twice — the mobile app resending a backlogged message, a producer
   * retry, or a replay of the topic.
   *
   * <p>Both copies are delivered and only one measurement is stored. The rebalance guard is not
   * what suppresses the second, because by then the partition has long since caught up; what
   * suppresses it is the bounded set of recently-seen ids, and what makes that possible is that the
   * gateway <em>derives</em> an event id rather than generating one, so a resent message is
   * recognisably the same event rather than a new one that happens to look similar.
   *
   * <p>The current position is checked as well as the count, because the two are defended
   * differently: the history is protected by not writing the duplicate at all, while the current
   * position would survive it anyway — its update is conditional on being strictly newer, which is
   * why re-applying an old event cannot drag the map backwards.
   */
  @Test
  void aRepublishedEventIsStoredOnceAndMovesNothingBackwards() throws Exception {
    PositionEvent first = Positions.at("SHP-D", Duration.ZERO, 41.0, -87.0);
    PositionEvent second = Positions.at("SHP-D", Duration.ofMinutes(5), 42.0, -87.0);

    publish(first);
    publish(second);
    awaitStored(2, "SHP-D", second.occurredAt());

    publish(first);

    // Nothing to wait for: the assertion is that a write does *not* happen, so the only honest
    // synchronisation is to let the record be consumed and then check the count is unchanged. The
    // third publish is acknowledged by the broker before this returns, and the consumer is reading
    // the same partition it has just been reading, so a settle is enough.
    Thread.sleep(2_000);

    assertThat(store.historyCount()).isEqualTo(2);
    CurrentPosition current = store.currentPosition("SHP-D").orElseThrow();
    assertThat(current.occurredAt()).isEqualTo(Positions.T0.plus(Duration.ofMinutes(5)));
    assertThat(current.location().getY()).isEqualTo(42.0);
  }

  /** A record that is not JSON is set aside, and the partition keeps moving. */
  @Test
  void anUnparseableRecordGoesToTheDeadLetterTopicAndDoesNotStallTheStream() {
    producer.send(new ProducerRecord<>(Topics.POSITION, "SHP-E", "{ this is not a position event"));

    PositionEvent afterwards = Positions.at("SHP-E", Duration.ofMinutes(1));
    publish(afterwards);

    // The event published after the bad record still lands, which is what "did not stall" means.
    awaitStored(1, "SHP-E", afterwards.occurredAt());
    assertThat(store.currentPosition("SHP-E")).isPresent();

    List<ConsumerRecord<String, String>> dead = drain(TrackingTopics.DEAD_LETTER);
    assertThat(dead).hasSize(1);
    assertThat(header(dead.getFirst(), "fleet.dlq.reason")).isEqualTo("UNPARSEABLE");
    // The key survives: unlike a gateway rejection, the shipment id is a separate field from the
    // value that failed to parse, so it is free provenance for whoever investigates.
    assertThat(dead.getFirst().key()).isEqualTo("SHP-E");
    assertThat(header(dead.getFirst(), "fleet.dlq.origin")).startsWith(Topics.POSITION + "-");
  }

  private void publish(PositionEvent event) {
    try {
      producer
          .send(
              new ProducerRecord<>(
                  Topics.POSITION, event.shipmentId(), EventJson.mapper().writeValueAsString(event)))
          .get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  private void awaitHistoryCount(long expected) {
    await().atMost(Duration.ofSeconds(30)).until(() -> store.historyCount() == expected);
  }

  /**
   * Waits until the history has reached a size <em>and</em> the shipment's current position has
   * caught up to a given instant.
   *
   * <p>Waiting on the history count alone is not enough, and the reason is a property of the store
   * rather than an accident of timing. It performs two writes per event, in a deliberate order:
   * append the measurement, then move the current position. So there is a window — tens of
   * milliseconds against a containerised database — in which the count has already reached its
   * final value and the current position has not yet been written. A poll loop lands in that window
   * often enough to fail, which is exactly what happened the first time this test ran.
   *
   * <p>The general rule it illustrates: wait for the condition being asserted, not for a proxy that
   * usually arrives at the same time.
   */
  private void awaitStored(long historyCount, String shipmentId, Instant currentAt) {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () ->
                store.historyCount() == historyCount
                    && store
                        .currentPosition(shipmentId)
                        .map(current -> currentAt.equals(current.occurredAt()))
                        .orElse(false));
  }

  private String collectionType(String name) {
    return mongo.execute(
        db -> {
          Document info = db.listCollections().filter(new Document("name", name)).first();
          return info == null ? null : info.getString("type");
        });
  }

  private static String header(ConsumerRecord<String, String> record, String key) {
    var header = record.headers().lastHeader(key);
    return header == null ? null : new String(header.value());
  }

  private static List<ConsumerRecord<String, String>> drain(String topic) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-drain-" + System.nanoTime());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topic));
      List<ConsumerRecord<String, String>> all = new java.util.ArrayList<>();
      for (int attempt = 0; attempt < 10 && all.isEmpty(); attempt++) {
        ConsumerRecords<String, String> polled = consumer.poll(Duration.ofSeconds(2));
        polled.records(topic).forEach(all::add);
      }
      return all;
    }
  }
}
