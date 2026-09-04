package com.fleettracking.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fleettracking.events.EtaUpdated;
import com.fleettracking.events.EventJson;
import com.fleettracking.events.GeoPoint;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.RawPayload;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.events.Topics;
import com.fleettracking.tracking.consume.TrackingTopics;
import com.fleettracking.tracking.eta.EtaState;
import com.fleettracking.tracking.geofence.GeofenceState;
import com.fleettracking.tracking.itinerary.Itinerary;
import com.fleettracking.tracking.itinerary.ScheduledStop;
import com.fleettracking.tracking.store.CurrentPosition;
import com.fleettracking.tracking.store.PositionPoint;
import com.fleettracking.tracking.store.PositionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
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
 * The estimate end to end: positions in on one topic, revised arrival times out on another.
 *
 * <p>{@code EtaCalculatorTest} proves the behaviour — that the estimate converges, that noise does
 * not move it, that a halt slips it rather than inflating it. None of that needs a broker. What
 * this proves is the things only a running system can be wrong about: that the events reach the
 * topic the service believes they do, in a shape another service can read back; that the estimate
 * is durable, so a restart does not begin again from nothing; that a replay produces no second
 * copy of anything; and that the suppression while a truck sits at a stop actually depends on
 * geofencing having run first, which in a unit test is simply asserted.
 *
 * <p>The truck drives a scripted approach with a slow patch in the middle, so that the estimate has
 * to be revised and then recover. Its arithmetic is arranged to arrive at a known instant: the
 * straight-line steps are what a truck covers when its odometer turns at the reported speed and the
 * road is thirty per cent longer than the line. The reported speed is <em>derived</em> from the step
 * and the circuity rather than written beside it, so that changing the platform's road assumption
 * cannot leave this script quietly describing a truck that is not the one it publishes.
 */
@SpringBootTest
@Testcontainers
class EtaIT {

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

  private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

  /** The Okhla DC in Delhi, exactly as the committed lane catalogue states it. */
  private static final double STOP_LAT = 28.5355;

  private static final double STOP_LON = 77.2730;

  /** One degree of latitude, in kilometres. */
  private static final double KM_PER_DEGREE = 111.19;

  /**
   * How much longer the road is than the line. Must match {@code fleet.tracking.eta.road-circuity}
   * and {@code Route.ROAD_CIRCUITY}: one assumption stated in three places, which is exactly why it
   * is named here rather than multiplied in by hand.
   */
  private static final double ROAD_CIRCUITY = 1.30;

  /** Cruise: 4 km of straight line every five minutes. */
  private static final double CRUISE_STEP_KM = 4.0;

  /** The slow patch: half that. */
  private static final double CRAWL_STEP_KM = CRUISE_STEP_KM / 2;

  /** What the odometer turns at to cover that step: 48 km/h along the line, 62.4 along the road. */
  private static final double CRUISE_KPH = CRUISE_STEP_KM * 12 * ROAD_CIRCUITY;

  private static final double CRAWL_KPH = CRUISE_KPH / 2;

  /**
   * A distinct shipment per test, for the reason {@code GeofenceIT} gives: the collections are
   * cleared between tests, but a Kafka topic is a log and there is no unpublishing.
   */
  private static final AtomicInteger NEXT_SHIPMENT = new AtomicInteger();

  private String shipment;

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

  @BeforeEach
  void seedItineraryAndClear() {
    mongo.remove(new Query(), PositionPoint.COLLECTION);
    mongo.remove(new Query(), CurrentPosition.COLLECTION);
    mongo.remove(new Query(), GeofenceState.COLLECTION);
    mongo.remove(new Query(), EtaState.COLLECTION);

    shipment = "SHP-ETA-%04d".formatted(NEXT_SHIPMENT.incrementAndGet());
    mongo.save(
        new Itinerary(
            shipment,
            "del-bom-nh48",
            List.of(
                new ScheduledStop(
                    "del-okhla", 0, "Okhla DC", "Delhi", "DL", STOP_LAT, STOP_LON, 400, "PICKUP"),
                new ScheduledStop(
                    "jai-vki", 1, "Jaipur VKI depot", "Jaipur", "RJ", 26.9124, 75.7873, 400,
                    "DELIVERY"))));
  }

  // --- the estimate reaches the topic -----------------------------------------------------------

  @Test
  void publishesRevisedEstimatesOntoTheDerivedTopic() {
    driveTheScriptedApproach();
    awaitPositionsStored(TOTAL_FIXES);

    List<EtaUpdated> etas = etasForThisShipment();

    assertThat(etas).isNotEmpty();
    assertThat(etas).allSatisfy(eta -> {
      assertThat(eta.shipmentId()).isEqualTo(shipment);
      assertThat(eta.stopId()).isEqualTo("del-okhla");
      assertThat(eta.remainingKm()).isPositive();
      assertThat(eta.confidence()).isBetween(0.0, 1.0);
    });
  }

  /** Keyed by shipment, like everything else on this topic. That is what preserves their order. */
  @Test
  void estimatesAreKeyedByShipment() {
    driveTheScriptedApproach();
    awaitPositionsStored(TOTAL_FIXES);

    assertThat(drainKeyedBy(Topics.DERIVED, shipment))
        .isNotEmpty()
        .allSatisfy(record -> assertThat(record.key()).isEqualTo(shipment));
  }

  /**
   * The estimate converges on the arrival that actually happens.
   *
   * <p>The scripted truck reaches the stop at a known instant, so this can be graded against the
   * truth rather than against itself. The slow patch in the middle is what makes the test worth
   * running: without it the estimate is constant and converging is trivial.
   */
  @Test
  void theEstimateConvergesOnTheRealArrival() {
    driveTheScriptedApproach();
    awaitPositionsStored(TOTAL_FIXES);

    List<EtaUpdated> etas = etasForThisShipment();
    assertThat(etas).hasSizeGreaterThan(1);

    Duration firstError = errorAgainst(etas.getFirst());
    Duration lastError = errorAgainst(etas.getLast());

    assertThat(lastError).isLessThan(firstError);
    assertThat(lastError).isLessThan(Duration.ofMinutes(5));
  }

  // --- durability ------------------------------------------------------------------------------

  /**
   * The estimate is in the database, and it agrees with the last one published.
   *
   * <p>This is what a restart reads back, and what a dashboard will query rather than replaying a
   * topic. If the two disagreed, the platform would be telling a consumer one thing and itself
   * another.
   */
  @Test
  void theCurrentEstimateIsStored() {
    driveTheScriptedApproach();
    awaitPositionsStored(TOTAL_FIXES);
    List<EtaUpdated> etas = etasForThisShipment();

    EtaState stored = mongo.findById(shipment, EtaState.class);

    assertThat(stored).isNotNull();
    assertThat(stored.stopId()).isEqualTo("del-okhla");
    assertThat(stored.estimatedArrival()).isEqualTo(etas.getLast().estimatedArrival());
    assertThat(stored.remainingKm()).isEqualTo(etas.getLast().remainingKm());
    // The model is kept too, so a restart resumes knowing roughly how fast this truck travels
    // rather than falling back to the nominal speed and publishing a burst of corrections.
    assertThat(stored.expectedSpeedKph()).isBetween(CRAWL_KPH, CRUISE_KPH * 1.05);
    assertThat(stored.updatedAt()).isNotNull();
  }

  /**
   * Replaying every position produces no new events at all.
   *
   * <p>The same test S10 wrote for arrivals, and it matters more here, because there is one estimate
   * per few fixes rather than one per stop. The ids are derived from the position that caused them,
   * so a redelivered fix regenerates the id it had the first time — which is what makes it safe to
   * publish before recording.
   */
  @Test
  void replayingEveryPositionProducesNoNewEstimateIds() {
    driveTheScriptedApproach();
    awaitPositionsStored(TOTAL_FIXES);
    List<String> firstIds = etasForThisShipment().stream().map(EtaUpdated::eventId).sorted().toList();

    driveTheScriptedApproach();
    settle();

    List<String> afterReplay =
        etasForThisShipment().stream().map(EtaUpdated::eventId).distinct().sorted().toList();

    assertThat(afterReplay).isEqualTo(firstIds);
  }

  // --- what must not produce an estimate ---------------------------------------------------------

  /**
   * Nothing is said while the truck is at a stop.
   *
   * <p>The suppression depends on geofencing having concluded that this fix is inside a fence, so
   * unlike the unit test's version of it, this is the whole path: a position is stored, evaluated
   * against the plan, found to be inside the yard, and the estimate declines to speak. Twenty fixes
   * across an hour in the yard, and not one of them produces an event.
   */
  @Test
  void publishesNothingWhileTheTruckIsAtAStop() {
    // Eight kilometres out and moving, twice.
    publish(at(0, kmSouth(8), CRUISE_KPH));
    publish(at(5, kmSouth(8), CRUISE_KPH));

    // Then parked squarely in the yard for an hour, reporting every three minutes as telematics
    // does. Stationary, so the speed model learns nothing, and inside a fence, so nothing is said.
    for (int minute = 10; minute <= 70; minute += 3) {
      publish(at(minute, new GeoPoint(STOP_LAT, STOP_LON), 0.0));
    }

    awaitArrivalAnnounced();
    settle();

    List<EtaUpdated> etas = etasForThisShipment();

    assertThat(etas).isNotEmpty();
    assertThat(etas)
        .allSatisfy(eta -> assertThat(eta.occurredAt()).isBeforeOrEqualTo(T0.plus(Duration.ofMinutes(5))));
  }

  /** A load nobody planned has nowhere to be, so there is nothing to estimate. */
  @Test
  void aShipmentWithNoItineraryGetsNoEstimate() {
    String unplanned = shipment + "-UNPLANNED";

    for (int minute = 0; minute <= 40; minute += 5) {
      publishFor(unplanned, at(unplanned, minute, kmSouth(40 - minute * 0.8), CRUISE_KPH));
    }

    await().atMost(Duration.ofSeconds(30)).until(() -> store.currentPosition(unplanned).isPresent());
    settle();

    assertThat(drainKeyedBy(Topics.DERIVED, unplanned)).isEmpty();
    assertThat(mongo.findById(unplanned, EtaState.class)).isNull();
  }

  // --- the scripted approach ---------------------------------------------------------------------

  /** Fixes in the approach: minute 0 to minute 110, every five minutes. */
  private static final int TOTAL_FIXES = 23;

  /** When the scripted truck actually reaches the stop. */
  private static final Instant TRUE_ARRIVAL = T0.plus(Duration.ofMinutes(110));

  /**
   * 80 km out, driving in, with twenty minutes of crawling in the middle.
   *
   * <p>Cruise covers 4 km of straight line every five minutes, which is 48 km/h along the line and
   * the 62.4 km/h the truck reports along the road. The crawl covers 2 km and reports half the
   * speed. Adding it up: forty kilometres in the first fifty minutes, eight in the next twenty, and
   * the last thirty-two in the forty minutes after that — 110 minutes in all.
   */
  private void driveTheScriptedApproach() {
    double remainingKm = 80;

    for (int minute = 0; minute <= 110; minute += 5) {
      boolean crawling = minute >= 50 && minute < 70;
      publish(at(minute, kmSouth(remainingKm), crawling ? CRAWL_KPH : CRUISE_KPH));
      remainingKm -= crawling ? CRAWL_STEP_KM : CRUISE_STEP_KM;
    }
  }

  private static GeoPoint kmSouth(double km) {
    return new GeoPoint(STOP_LAT - km / KM_PER_DEGREE, STOP_LON);
  }

  private PositionEvent at(int minute, GeoPoint where, double speedKph) {
    return at(shipment, minute, where, speedKph);
  }

  private static PositionEvent at(
      String shipmentId, int minute, GeoPoint where, double speedKph) {
    Instant occurredAt = T0.plus(Duration.ofMinutes(minute));
    return new PositionEvent(
        // Derived from the shipment and the instant, exactly as the gateway would, so a replayed
        // fix is recognised as the same event rather than as a new one.
        "evt-" + shipmentId + "-" + occurredAt.toEpochMilli(),
        shipmentId,
        "VEH-0001",
        "TLM-0001",
        occurredAt,
        occurredAt.plusSeconds(2),
        where,
        speedKph,
        0.0,
        123456.0,
        6.0,
        RawPayload.of(SourceSystem.TELEMATICS, "{\"probe\":\"" + shipmentId + "\"}"));
  }

  // --- helpers -----------------------------------------------------------------------------------

  private Duration errorAgainst(EtaUpdated eta) {
    return Duration.between(TRUE_ARRIVAL, eta.estimatedArrival()).abs();
  }

  private void publish(PositionEvent event) {
    publishFor(event.shipmentId(), event);
  }

  private void publishFor(String key, PositionEvent event) {
    try {
      producer
          .send(
              new ProducerRecord<>(Topics.POSITION, key, EventJson.mapper().writeValueAsString(event)))
          .get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Waits on the stored measurements rather than on the topic.
   *
   * <p>The lesson from S9's flaky test: wait for the condition the assertions are about. Polling the
   * derived topic would mean building a consumer per poll, which is slow enough to change the timing
   * of the thing being measured.
   */
  private void awaitPositionsStored(int expected) {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> mongo.count(new Query(), PositionPoint.COLLECTION) >= expected);
    settle();
  }

  private void awaitArrivalAnnounced() {
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () -> {
              GeofenceState state =
                  mongo.findById(GeofenceState.idFor(shipment, "del-okhla"), GeofenceState.class);
              return state != null && state.arrivalAnnounced();
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

  /**
   * Every estimate on the derived topic for this test's shipment, in the order it was published.
   *
   * <p>Told apart from arrivals and departures by shape, which is what any consumer of a shared
   * topic has to do: only an estimate carries an estimated arrival.
   */
  private List<EtaUpdated> etasForThisShipment() {
    return drainKeyedBy(Topics.DERIVED, shipment).stream()
        .filter(r -> EventJson.mapper().readTree(r.value()).has("estimatedArrival"))
        .map(r -> EventJson.mapper().readValue(r.value(), EtaUpdated.class))
        .toList();
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

    List<ConsumerRecord<String, String>> all = new java.util.ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topic));
      for (int attempt = 0; attempt < 5; attempt++) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
        records.forEach(all::add);
      }
    }
    return all;
  }
}
