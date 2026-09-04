package com.fleettracking.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fleettracking.events.EventJson;
import com.fleettracking.events.GeoPoint;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.RawPayload;
import com.fleettracking.events.ShipmentArrived;
import com.fleettracking.events.ShipmentDeparted;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.events.Topics;
import com.fleettracking.tracking.consume.TrackingTopics;
import com.fleettracking.tracking.geofence.GeofenceState;
import com.fleettracking.tracking.itinerary.Itinerary;
import com.fleettracking.tracking.itinerary.ScheduledStop;
import com.fleettracking.tracking.store.CurrentPosition;
import com.fleettracking.tracking.store.PositionPoint;
import com.fleettracking.tracking.store.PositionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Geofencing end to end: positions in on one topic, arrivals and departures out on another.
 *
 * <p>The unit tests prove the state machine reaches the right conclusions. What they cannot prove
 * is that the conclusions are published to the topic this service thinks they are, in a shape
 * another service can read back, from reference data that was actually loaded — or that the
 * "exactly one arrival" guarantee survives the position being delivered twice, which is the form
 * the restart problem actually takes.
 *
 * <p>The truck here drives a scripted approach: far away, then inside a 400 m yard, dwelling for
 * fifty minutes, then away again. That is M3's second exit criterion in miniature.
 */
@SpringBootTest
@Testcontainers
class GeofenceIT {

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

  private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

  /**
   * A distinct shipment per test.
   *
   * <p>The collections are cleared between tests but the topics are not — a Kafka topic is a log
   * and there is no "delete what I just published". Sharing one shipment id would therefore let one
   * test's arrivals be drained by the next one and counted as its own. Giving each test its own
   * load is both simpler and closer to the truth: these are independent shipments.
   */
  private static final java.util.concurrent.atomic.AtomicInteger NEXT_SHIPMENT =
      new java.util.concurrent.atomic.AtomicInteger();

  private String shipment;

  /** The Okhla DC in Delhi, exactly as the committed lane catalogue states it. */
  private static final double STOP_LAT = 28.5355;

  private static final double STOP_LON = 77.2730;

  /** Roughly nine kilometres due north of the yard — outside any fence in this itinerary. */
  private static final double AWAY_LAT = 28.6155;

  @Autowired private PositionStore store;
  @Autowired private MongoOperations mongo;

  private static Producer<String, String> producer;

  @DynamicPropertySource
  static void containerAddresses(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.mongodb.uri", MONGO::getConnectionString);
    registry.add("spring.mongodb.database", () -> "fleet");
    registry.add("fleet.tracking.heartbeat-interval", () -> "1h");
  }

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
                    new NewTopic(Topics.DERIVED, 6, (short) 1),
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

  /**
   * Reference data is seeded here rather than by the shell script, because a test that depended on
   * a script having been run against a different database would not be testing this service.
   */
  @BeforeEach
  void seedItineraryAndClear() {
    mongo.remove(new Query(), PositionPoint.COLLECTION);
    mongo.remove(new Query(), CurrentPosition.COLLECTION);
    mongo.remove(new Query(), GeofenceState.COLLECTION);

    shipment = "SHP-DEL-%04d".formatted(NEXT_SHIPMENT.incrementAndGet());
    mongo.save(
        new Itinerary(
            shipment,
            "del-bom-nh48",
            List.of(
                new ScheduledStop(
                    "del-okhla", 0, "Okhla DC", "Delhi", "DL", STOP_LAT, STOP_LON, 400, "PICKUP"),
                // A second stop the truck never goes near, so the test also shows that evaluating
                // every stop on every fix does not invent arrivals at the ones it skipped.
                new ScheduledStop(
                    "jai-vki", 1, "Jaipur VKI depot", "Jaipur", "RJ", 26.9124, 75.7873, 400,
                    "DELIVERY"))));
  }

  /**
   * The scripted crossing: exactly one arrival and exactly one departure.
   *
   * <p>This is M3's second exit criterion. The fixes are deliberately dense while the truck is
   * parked — twenty of them across the dwell — because the naive implementation announces one
   * arrival per fix and this is what would catch it.
   */
  @Test
  void aScriptedCrossingProducesExactlyOneArrivalAndOneDeparture() {
    driveTheScriptedRoute();

    awaitStopComplete();
    List<ConsumerRecord<String, String>> derived = drainForThisShipment(Topics.DERIVED);

    List<ShipmentArrived> arrivals = only(derived, ShipmentArrived.class);
    List<ShipmentDeparted> departures = only(derived, ShipmentDeparted.class);

    assertThat(arrivals).hasSize(1);
    assertThat(departures).hasSize(1);
    assertThat(arrivals.getFirst().stopId()).isEqualTo("del-okhla");
    assertThat(departures.getFirst().stopId()).isEqualTo("del-okhla");
  }

  /** The arrival is stamped with the crossing, and the departure states the detention time. */
  @Test
  void theArrivalAndDepartureCarryTheRightInstants() {
    driveTheScriptedRoute();
    awaitStopComplete();
    List<ConsumerRecord<String, String>> derived = drainForThisShipment(Topics.DERIVED);

    ShipmentArrived arrival = only(derived, ShipmentArrived.class).getFirst();
    ShipmentDeparted departure = only(derived, ShipmentDeparted.class).getFirst();

    // Entered at minute 10 and crossed back out at minute 60, so the dwell is fifty minutes -- and
    // the arrival is stamped with the crossing rather than with the fix that confirmed it.
    assertThat(arrival.occurredAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
    assertThat(departure.occurredAt()).isEqualTo(T0.plus(Duration.ofMinutes(60)));
    assertThat(departure.dwell()).isEqualTo(Duration.ofMinutes(50));
  }

  /** Everything on the derived topic is keyed by shipment, which is what preserves their order. */
  @Test
  void derivedEventsAreKeyedByShipment() {
    driveTheScriptedRoute();
    awaitStopComplete();

    assertThat(drainForThisShipment(Topics.DERIVED)).allSatisfy(r -> assertThat(r.key()).isEqualTo(shipment));
  }

  /**
   * The restart problem, in the form it actually takes.
   *
   * <p>A processor that stops without committing has its positions handed back to it, so the whole
   * approach is replayed through a geofencer whose state is already past it. Two things must hold:
   * the persisted state must refuse to announce a second arrival at all, and even if it did, the
   * derived event id must be identical so that no consumer could count two.
   *
   * <p>Replaying the positions is a faithful stand-in for the restart, and a stricter one: a real
   * restart replays only what was uncommitted, whereas this replays everything.
   */
  @Test
  void replayingEveryPositionDoesNotAnnounceASecondArrival() {
    driveTheScriptedRoute();
    awaitStopComplete();
    List<String> firstIds = idsOf(conclusions(drainForThisShipment(Topics.DERIVED)));

    driveTheScriptedRoute();

    // Nothing more should appear. The only honest way to assert that a write does not happen is to
    // let the records be consumed and then look.
    settle();

    List<ConsumerRecord<String, String>> derived = conclusions(drainForThisShipment(Topics.DERIVED));
    assertThat(derived).hasSize(2);
    assertThat(idsOf(derived)).isEqualTo(firstIds);
  }

  /**
   * The state that makes the guarantee is in the database, so it can be inspected — and its being
   * inspectable is the point, because it is what a restart reads back.
   */
  @Test
  void thePersistedStateRecordsTheStopAsComplete() {
    driveTheScriptedRoute();
    awaitStopComplete();

    GeofenceState state =
        mongo.findById(GeofenceState.idFor(shipment, "del-okhla"), GeofenceState.class);

    assertThat(state).isNotNull();
    assertThat(state.arrivalAnnounced()).isTrue();
    assertThat(state.departureAnnounced()).isTrue();
    assertThat(state.isComplete()).isTrue();

    // The stop the truck never went near has no state at all, rather than an empty row.
    assertThat(mongo.findById(GeofenceState.idFor(shipment, "jai-vki"), GeofenceState.class))
        .isNull();
  }

  /**
   * A load with no itinerary is not an error. Its positions are stored exactly as any other, and
   * nothing is announced, because there is nothing to announce against.
   */
  @Test
  void aShipmentWithNoItineraryIsStoredButNotGeofenced() {
    String unplanned = shipment + "-UNPLANNED";

    // Parked squarely inside the Okhla yard for an hour. The only thing separating this from the
    // scripted route above is that nothing planned it, so nothing may be announced for it.
    for (int minute = 0; minute <= 60; minute += 10) {
      publish(at(unplanned, minute, STOP_LAT, STOP_LON));
    }

    await().atMost(Duration.ofSeconds(30)).until(() -> store.historyCount() == 7);
    assertThat(store.currentPosition(unplanned)).isPresent();

    settle();
    assertThat(drainKeyedBy(Topics.DERIVED, unplanned)).isEmpty();
    assertThat(mongo.count(new Query(), GeofenceState.class)).isZero();
  }

  // --- the scripted route ----------------------------------------------------------------------

  /**
   * Approach, park for fifty minutes, leave.
   *
   * <p>Minutes 0-5 are eight kilometres away; the truck is inside the yard from minute 10 to minute
   * 55, reporting every three minutes as telematics does; and from minute 60 it is away again.
   */
  private void driveTheScriptedRoute() {
    publish(at(shipment, 0, AWAY_LAT, STOP_LON));
    publish(at(shipment, 5, AWAY_LAT, STOP_LON));

    for (int minute = 10; minute <= 55; minute += 3) {
      publish(at(shipment, minute, STOP_LAT, STOP_LON));
    }

    publish(at(shipment, 60, AWAY_LAT, STOP_LON));
    publish(at(shipment, 65, AWAY_LAT, STOP_LON));
    publish(at(shipment, 70, AWAY_LAT, STOP_LON));
  }

  private static PositionEvent at(String shipmentId, int minute, double lat, double lon) {
    Instant occurredAt = T0.plus(Duration.ofMinutes(minute));
    return new PositionEvent(
        // Derived from the shipment and the instant, exactly as the gateway would, so that a
        // replayed fix is recognised as the same event rather than a new one.
        "evt-" + shipmentId + "-" + occurredAt.toEpochMilli(),
        shipmentId,
        "VEH-0001",
        "TLM-0001",
        occurredAt,
        occurredAt.plusSeconds(2),
        new GeoPoint(lat, lon),
        0.0,
        180.0,
        123456.0,
        6.0,
        RawPayload.of(SourceSystem.TELEMATICS, "{\"probe\":\"" + shipmentId + "\"}"));
  }

  // --- helpers ---------------------------------------------------------------------------------

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

  /**
   * Waits until the stop is recorded as arrived at and departed from.
   *
   * <p>Waiting on the persisted state rather than on the topic, for two reasons. It is the
   * condition the assertions are actually about — the lesson S9's flaky test taught — and polling
   * the topic would mean building a consumer per poll, which is slow enough to change the timing of
   * what is being measured.
   */
  private void awaitStopComplete() {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () -> {
              GeofenceState state =
                  mongo.findById(GeofenceState.idFor(shipment, "del-okhla"), GeofenceState.class);
              return state != null && state.isComplete();
            });
  }

  /** Lets the consumer work through what has been published, for assertions that nothing happens. */
  private void settle() {
    try {
      Thread.sleep(3_000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static List<String> idsOf(List<ConsumerRecord<String, String>> records) {
    return records.stream()
        .map(r -> EventJson.mapper().readTree(r.value()).get("eventId").asString())
        .sorted()
        .toList();
  }

  private static <T> List<T> only(List<ConsumerRecord<String, String>> records, Class<T> type) {
    return conclusions(records).stream()
        .filter(r -> isA(r, type))
        .map(r -> EventJson.mapper().readValue(r.value(), type))
        .toList();
  }

  /**
   * Everything on the derived topic that this test is about — arrivals and departures.
   *
   * <p>S11 put a third kind of event on the same topic. Estimates are published for the fixes on
   * the approach, so a test that counted everything keyed by this shipment would find them and
   * report several arrivals where there is one. Consumers of a shared topic have always had to tell
   * its events apart; this is that, in a test.
   */
  private static List<ConsumerRecord<String, String>> conclusions(
      List<ConsumerRecord<String, String>> records) {
    return records.stream().filter(r -> !has(r, "estimatedArrival")).toList();
  }

  /** Told apart by shape, exactly as a real consumer would have to: only a departure has a dwell. */
  private static boolean isA(ConsumerRecord<String, String> record, Class<?> type) {
    return has(record, "dwell") == (type == ShipmentDeparted.class);
  }

  private static boolean has(ConsumerRecord<String, String> record, String field) {
    return EventJson.mapper().readTree(record.value()).has(field);
  }

  /** Everything on a topic for this test's shipment. See the note on {@code NEXT_SHIPMENT}. */
  private List<ConsumerRecord<String, String>> drainForThisShipment(String topic) {
    return drainKeyedBy(topic, shipment);
  }

  private static List<ConsumerRecord<String, String>> drainKeyedBy(String topic, String key) {
    return drain(topic).stream().filter(r -> key.equals(r.key())).toList();
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
      List<ConsumerRecord<String, String>> all = new ArrayList<>();
      for (int attempt = 0; attempt < 5; attempt++) {
        ConsumerRecords<String, String> polled = consumer.poll(Duration.ofSeconds(1));
        polled.records(topic).forEach(all::add);
      }
      return all;
    }
  }
}
