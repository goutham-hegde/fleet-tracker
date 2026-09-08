package com.fleettracking.exceptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fleettracking.events.EtaUpdated;
import com.fleettracking.events.EventJson;
import com.fleettracking.events.ExceptionCleared;
import com.fleettracking.events.ExceptionRaised;
import com.fleettracking.events.ExceptionType;
import com.fleettracking.events.GeoPoint;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.RawPayload;
import com.fleettracking.events.Severity;
import com.fleettracking.events.ShipmentArrived;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.events.StatusCode;
import com.fleettracking.events.StatusEvent;
import com.fleettracking.events.TemperatureReading;
import com.fleettracking.events.Topics;
import com.fleettracking.exceptions.consume.ExceptionDeadLetters;
import com.fleettracking.exceptions.incident.Incident;
import com.fleettracking.exceptions.rule.ConditionState;
import com.fleettracking.reference.Itinerary;
import com.fleettracking.reference.ScheduledStop;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * The whole service: three topics in, raise-and-clear pairs out.
 *
 * <p>The rule tests prove each rule reaches the right conclusion. What they cannot prove is that a
 * conclusion becomes a pair of events on the topic this service thinks it publishes to, in a shape
 * another service can read back, joined by an id that actually matches — or that one condition
 * lasting an hour produces one incident rather than one per reading, which is the property the
 * whole design turns on and the one that cannot be checked without a real broker in the way.
 *
 * <p>Two of M4's exit criteria live here: each fault raises exactly the expected exception, and
 * exceptions clear when the condition resolves.
 */
@SpringBootTest
@Testcontainers
class ExceptionServiceIT {

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

  private static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

  /** Genome Valley, exactly as the committed lane catalogue states it. */
  private static final double PICKUP_LAT = 17.61;

  private static final double PICKUP_LON = 78.58;

  /** Well down the highway toward Kurnool, and nowhere near any scheduled stop. */
  private static final double ROADSIDE_LAT = 16.70;

  private static final double ROADSIDE_LON = 78.30;

  /** The delivery, which is where a booked window would apply. */
  private static final double DELIVERY_LAT = 12.9716;

  private static final double DELIVERY_LON = 77.5946;

  /**
   * A distinct shipment per test.
   *
   * <p>The collections are cleared between tests but the topics are not — a Kafka topic is a log,
   * and there is no "delete what I just published". Sharing one shipment id would let one test's
   * exceptions be drained by the next and counted as its own.
   */
  private static final AtomicInteger NEXT_SHIPMENT = new AtomicInteger();

  private String shipment;

  @Autowired private MongoOperations mongo;

  /** Read to tell "the service has caught up and concluded nothing" from "it has not read yet". */
  @Autowired private com.fleettracking.exceptions.consume.ExceptionConsumers consumers;

  /** How many records this test has published, per topic, so the wait has something real to wait on. */
  private int publishedPositions;
  private int publishedStatuses;
  private int publishedDerived;
  private long positionsBefore;
  private long statusesBefore;
  private long derivedBefore;

  private static Producer<String, String> producer;

  @DynamicPropertySource
  static void containerAddresses(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.mongodb.uri", MONGO::getConnectionString);
    registry.add("spring.mongodb.database", () -> "fleet");
    registry.add("fleet.exceptions.heartbeat-interval", () -> "1h");
    // The sweep is left running: the signal-loss test needs it, and its own two-clock guard is
    // what stops it firing during the others.
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
                    new NewTopic(Topics.STATUS, 3, (short) 1),
                    new NewTopic(Topics.DERIVED, 6, (short) 1),
                    new NewTopic(Topics.EXCEPTIONS, 3, (short) 1),
                    new NewTopic(ExceptionDeadLetters.TOPIC, 3, (short) 1)))
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
   * Reference data is seeded here rather than by the shell scripts, for the reason S8 established:
   * a test that could pass against a different database is not testing this service. The manifests
   * are written in the shape {@code seed-manifests.sh} writes them, at the platform's reserved
   * paths.
   */
  @BeforeEach
  void seedAndClear() {
    mongo.remove(new Query(), Incident.COLLECTION);
    mongo.remove(new Query(), ConditionState.COLLECTION);
    mongo.remove(new Query(), "manifests");

    shipment = "SHP-HYD-%04d".formatted(NEXT_SHIPMENT.incrementAndGet());

    // The counters are cumulative across tests sharing one context, so each test waits on its
    // own delta rather than on an absolute total.
    publishedPositions = 0;
    publishedStatuses = 0;
    publishedDerived = 0;
    positionsBefore = consumers.positionCount();
    statusesBefore = consumers.statusCount();
    derivedBefore = consumers.derivedCount();

    mongo.save(
        new Itinerary(
            shipment,
            "hyd-blr-cold",
            List.of(
                new ScheduledStop(
                    "hyd-genome", 0, "Genome Valley", "Hyderabad", "TG", PICKUP_LAT, PICKUP_LON,
                    400, "PICKUP"),
                new ScheduledStop(
                    "blr-hosp", 1, "Bengaluru hospital", "Bengaluru", "KA", DELIVERY_LAT,
                    DELIVERY_LON, 120, "DELIVERY"))));
  }

  // ---------------------------------------------------------------- temperature

  @Test
  void aSustainedExcursionRaisesOnceAndClearsWhenTheLoadRecovers() {
    seedColdChainManifest();

    // Eight readings well above the 2-8C band, spread over an hour of event time. The tolerance
    // on the manifest is thirty minutes, so the first few must produce nothing at all.
    for (int minute = 0; minute <= 70; minute += 10) {
      publish(Topics.STATUS, reading(Duration.ofMinutes(minute), 11.4));
    }
    // Back inside the band.
    publish(Topics.STATUS, reading(Duration.ofMinutes(80), 4.6));

    awaitClosedIncident(ExceptionType.TEMPERATURE_EXCURSION);

    List<ExceptionRaised> raised = raisedFor(ExceptionType.TEMPERATURE_EXCURSION);
    List<ExceptionCleared> cleared = clearedFor(ExceptionType.TEMPERATURE_EXCURSION);

    // One incident, not one per reading. This is the property the whole design turns on, and it
    // cannot be checked without a real broker between the rule and the assertion.
    assertThat(raised).hasSize(1);
    assertThat(cleared).hasSize(1);

    ExceptionRaised open = raised.getFirst();
    // A band from a customer's manifest, so a breach is destroyed freight rather than a warning.
    assertThat(open.severity()).isEqualTo(Severity.CRITICAL);
    // Stamped when the temperature left the band, not when the tolerance expired.
    assertThat(open.occurredAt()).isEqualTo(T0);
    assertThat(open.thresholdValue()).isEqualTo(8.0);

    // The join. Both events name the same incident, which is the entire reason exceptions are two
    // events rather than a boolean.
    assertThat(cleared.getFirst().exceptionId()).isEqualTo(open.exceptionId());
    assertThat(cleared.getFirst().openFor()).isEqualTo(Duration.ofMinutes(80));
    assertThat(cleared.getFirst().raisedAt()).isEqualTo(T0);
  }

  @Test
  void aBriefWarmSpellDuringLoadingRaisesNothing() {
    seedColdChainManifest();

    // Doors open on a dock: the box warms for ten minutes and cools again. The simulator produces
    // exactly this at every delivery, and a rule that alerted on it would be muted within a day.
    publish(Topics.STATUS, reading(Duration.ZERO, 9.8));
    publish(Topics.STATUS, reading(Duration.ofMinutes(5), 10.2));
    publish(Topics.STATUS, reading(Duration.ofMinutes(10), 5.1));
    publish(Topics.STATUS, reading(Duration.ofMinutes(20), 4.3));

    awaitConsumed();
    assertThat(openIncidents()).isEmpty();
    assertThat(raisedFor(ExceptionType.TEMPERATURE_EXCURSION)).isEmpty();
  }

  // ---------------------------------------------------------------- unplanned stop

  @Test
  void aBreakdownRaisesAnUnplannedStopAndClearsWhenTheTruckMovesAgain() {
    publish(Topics.POSITION, position(Duration.ZERO, ROADSIDE_LAT, ROADSIDE_LON, 68.0));
    // Stopped dead where it stands, which is what a breakdown looks like on the wire.
    for (int minute = 5; minute <= 40; minute += 5) {
      publish(Topics.POSITION, position(Duration.ofMinutes(minute), ROADSIDE_LAT, ROADSIDE_LON, 0.3));
    }
    publish(Topics.POSITION, position(Duration.ofMinutes(50), ROADSIDE_LAT, ROADSIDE_LON, 55.0));

    awaitClosedIncident(ExceptionType.UNPLANNED_STOP);

    assertThat(raisedFor(ExceptionType.UNPLANNED_STOP)).hasSize(1);
    List<ExceptionCleared> cleared = clearedFor(ExceptionType.UNPLANNED_STOP);
    assertThat(cleared).hasSize(1);
    assertThat(cleared.getFirst().resolution()).isEqualTo("RESUMED");
  }

  @Test
  void anHourOnADockRaisesNothing() {
    // The most ordinary event in freight, and the reason this rule needs the itinerary.
    for (int minute = 0; minute <= 60; minute += 5) {
      publish(Topics.POSITION, position(Duration.ofMinutes(minute), PICKUP_LAT, PICKUP_LON, 0.0));
    }

    awaitConsumed();
    assertThat(openIncidents()).isEmpty();
  }

  // ---------------------------------------------------------------- route deviation

  @Test
  void aDetourRaisesARouteDeviationAndClearsOnRejoining() {
    publish(Topics.POSITION, position(Duration.ZERO, ROADSIDE_LAT, ROADSIDE_LON, 65.0));
    // Roughly ninety kilometres east of the planned line, held for half an hour.
    for (int minute = 5; minute <= 35; minute += 5) {
      publish(Topics.POSITION, position(Duration.ofMinutes(minute), ROADSIDE_LAT, 79.15, 65.0));
    }
    publish(Topics.POSITION, position(Duration.ofMinutes(45), ROADSIDE_LAT, ROADSIDE_LON, 65.0));

    awaitClosedIncident(ExceptionType.ROUTE_DEVIATION);

    assertThat(raisedFor(ExceptionType.ROUTE_DEVIATION)).hasSize(1);
    assertThat(clearedFor(ExceptionType.ROUTE_DEVIATION).getFirst().resolution())
        .isEqualTo("BACK_ON_ROUTE");
  }

  @Test
  void aSingleStrayFixRaisesNothing() {
    // A reflected signal, not a diversion. Duration is what tells them apart.
    publish(Topics.POSITION, position(Duration.ZERO, ROADSIDE_LAT, ROADSIDE_LON, 65.0));
    publish(Topics.POSITION, position(Duration.ofMinutes(1), ROADSIDE_LAT, 79.15, 65.0));
    publish(Topics.POSITION, position(Duration.ofMinutes(2), ROADSIDE_LAT, ROADSIDE_LON, 65.0));

    awaitConsumed();
    assertThat(openIncidents()).isEmpty();
  }

  // ---------------------------------------------------------------- late arrival

  @Test
  void aProjectionPastTheWindowRaisesAndAnEarlierOneClearsIt() {
    Instant closes = T0.plus(Duration.ofHours(6));
    seedRetailManifest(closes);

    // Two hours late, and then recovered.
    publish(Topics.DERIVED, estimate(Duration.ofHours(1), closes.plus(Duration.ofHours(2))));
    publish(Topics.DERIVED, estimate(Duration.ofHours(2), closes.minus(Duration.ofMinutes(30))));

    awaitClosedIncident(ExceptionType.LATE_ARRIVAL);

    List<ExceptionRaised> raised = raisedFor(ExceptionType.LATE_ARRIVAL);
    assertThat(raised).hasSize(1);
    // Still only a prediction when it was raised.
    assertThat(raised.getFirst().severity()).isEqualTo(Severity.WARNING);
    assertThat(raised.getFirst().stopId()).isEqualTo("blr-hosp");

    assertThat(clearedFor(ExceptionType.LATE_ARRIVAL).getFirst().resolution())
        .isEqualTo("BACK_WITHIN_WINDOW");
  }

  @Test
  void arrivingLateEscalatesTheIncidentAndThenClosesIt() {
    Instant closes = T0.plus(Duration.ofHours(6));
    seedRetailManifest(closes);

    publish(Topics.DERIVED, estimate(Duration.ofHours(1), closes.plus(Duration.ofHours(2))));
    publish(Topics.DERIVED, arrival(Duration.ofHours(7), "blr-hosp"));

    awaitClosedIncident(ExceptionType.LATE_ARRIVAL);

    // One incident throughout: the projection and the fact are the same breach, and reopening it
    // under a new id to record that it came true would break the pairing.
    List<ExceptionRaised> raised = raisedFor(ExceptionType.LATE_ARRIVAL);
    assertThat(raised).hasSize(1);

    Incident stored = onlyIncident(ExceptionType.LATE_ARRIVAL);
    // Severity goes up and never comes down.
    assertThat(stored.severity()).isEqualTo(Severity.CRITICAL);
    assertThat(stored.detail()).contains("Arrived 60 minutes after");
    assertThat(stored.resolution()).isEqualTo("ARRIVED_LATE");
  }

  @Test
  void aCustomerWhoBookedNoWindowIsNeverLate() {
    // No manifest at all. The rules that need no commitment carry on; this one goes quiet.
    Instant closes = T0.plus(Duration.ofHours(6));
    publish(Topics.DERIVED, estimate(Duration.ofHours(1), closes.plus(Duration.ofDays(2))));

    awaitConsumed();
    assertThat(openIncidents()).isEmpty();
  }

  // ---------------------------------------------------------------- signal loss

  @Test
  void aDeviceThatGoesQuietIsReportedAndClearsWhenItComesBack() {
    // This shipment stops reporting at T0.
    publish(Topics.POSITION, position(Duration.ZERO, ROADSIDE_LAT, ROADSIDE_LON, 62.0));

    // Another load carries the fleet's clock past the threshold. That is where the current event
    // time comes from: there is no event from the silent shipment to read one off.
    publish(Topics.POSITION, positionFor("SHP-DEL-9999", Duration.ofMinutes(45), 62.0));

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(
            () -> assertThat(openIncidents(ExceptionType.SIGNAL_LOSS)).hasSize(1));

    Incident open = onlyIncident(ExceptionType.SIGNAL_LOSS);
    // The evidence is that nothing was measured at all, so there is nothing to report as a value.
    assertThat(open.observedValue()).isNull();
    assertThat(open.thresholdValue()).isNull();
    // Stamped at the last thing heard from it.
    assertThat(open.onsetAt()).isEqualTo(T0);

    // Reporting again closes it immediately rather than at the next sweep.
    publish(Topics.POSITION, position(Duration.ofMinutes(50), ROADSIDE_LAT, ROADSIDE_LON, 60.0));

    awaitClosedIncident(ExceptionType.SIGNAL_LOSS);
    assertThat(clearedFor(ExceptionType.SIGNAL_LOSS).getFirst().resolution())
        .isEqualTo("REPORTING_AGAIN");
  }

  // ---------------------------------------------------------------- plumbing

  @Test
  void aMalformedRecordIsSetAsideRatherThanStallingThePartition() {
    producer.send(new ProducerRecord<>(Topics.POSITION, shipment, "{ this is not JSON"));
    // A good record behind it must still be processed, which is what "set aside" has to mean.
    publish(Topics.POSITION, position(Duration.ZERO, ROADSIDE_LAT, ROADSIDE_LON, 62.0));
    for (int minute = 5; minute <= 40; minute += 5) {
      publish(Topics.POSITION, position(Duration.ofMinutes(minute), ROADSIDE_LAT, ROADSIDE_LON, 0.2));
    }

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(() -> assertThat(openIncidents(ExceptionType.UNPLANNED_STOP)).hasSize(1));

    List<ConsumerRecord<String, String>> dead = deadLettersForThisShipment();
    assertThat(dead).isNotEmpty();
    assertThat(new String(dead.getFirst().headers().lastHeader("fleet.rejection-reason").value()))
        .isEqualTo("UNPARSEABLE");
    // Unlike the gateway's dead-letter topic, the key is kept: the record already had one.
    assertThat(dead.getFirst().key()).isNotNull();
  }

  @Test
  void anUnrecognisedDerivedShapeIsIgnoredRatherThanRejected() {
    // A fifth kind of derived event must be able to appear without this service rejecting the
    // platform's own traffic.
    producer.send(
        new ProducerRecord<>(
            Topics.DERIVED,
            shipment,
            "{\"eventId\":\"x\",\"shipmentId\":\"" + shipment + "\",\"somethingNew\":true}"));
    publish(Topics.DERIVED, arrival(Duration.ofHours(1), "hyd-genome"));

    awaitConsumed();
    assertThat(deadLettersForThisShipment()).isEmpty();
  }

  // ---------------------------------------------------------------- helpers

  private void seedColdChainManifest() {
    mongo
        .getCollection("manifests")
        .insertOne(
            new org.bson.Document()
                .append("_id", shipment)
                .append("shipmentId", shipment)
                .append("customerId", "MEDIVAULT")
                .append("mode", "PHARMA_COLD_CHAIN")
                .append("schemaVersion", "2026-09-04")
                .append(
                    "body",
                    new org.bson.Document()
                        .append("drugLicenceNo", "TG/28/2019")
                        .append(
                            "temperature",
                            new org.bson.Document()
                                .append("minC", 2)
                                .append("maxC", 8)
                                .append("excursionToleranceMinutes", 30))));
  }

  private void seedRetailManifest(Instant closesAt) {
    mongo
        .getCollection("manifests")
        .insertOne(
            new org.bson.Document()
                .append("_id", shipment)
                .append("shipmentId", shipment)
                .append("customerId", "VISTAMART")
                .append("mode", "RETAIL_REPLENISHMENT")
                .append("schemaVersion", "2026-09-04")
                .append(
                    "body",
                    new org.bson.Document()
                        .append("dcCode", "DC-BHW-01")
                        .append(
                            "deliveryWindow",
                            new org.bson.Document()
                                .append(
                                    "opensAt",
                                    java.util.Date.from(closesAt.minus(Duration.ofHours(4))))
                                .append("closesAt", java.util.Date.from(closesAt)))));
  }

  private PositionEvent position(Duration afterT0, double lat, double lon, double speedKph) {
    return positionAt(shipment, afterT0, lat, lon, speedKph);
  }

  private PositionEvent positionFor(String otherShipment, Duration afterT0, double speedKph) {
    return positionAt(otherShipment, afterT0, ROADSIDE_LAT, ROADSIDE_LON, speedKph);
  }

  private static PositionEvent positionAt(
      String shipmentId, Duration afterT0, double lat, double lon, double speedKph) {
    Instant occurredAt = T0.plus(afterT0);
    return new PositionEvent(
        "evt-pos-" + shipmentId + "-" + occurredAt.toEpochMilli(),
        shipmentId,
        "VEH-0001",
        "TLM-0001",
        occurredAt,
        occurredAt.plusSeconds(2),
        new GeoPoint(lat, lon),
        speedKph,
        180.0,
        123456.0,
        6.0,
        RawPayload.of(SourceSystem.TELEMATICS, "{}"));
  }

  private StatusEvent reading(Duration afterT0, double celsius) {
    Instant occurredAt = T0.plus(afterT0);
    return new StatusEvent(
        "evt-tmp-" + shipment + "-" + occurredAt.toEpochMilli(),
        shipment,
        "VEH-0001",
        "RFR-0001",
        occurredAt,
        occurredAt.plusSeconds(2),
        StatusCode.TEMPERATURE_READING,
        null,
        null,
        new TemperatureReading(celsius, 4.0),
        null,
        null,
        RawPayload.of(SourceSystem.REEFER_SENSOR, "{}"));
  }

  private EtaUpdated estimate(Duration afterT0, Instant estimatedArrival) {
    Instant occurredAt = T0.plus(afterT0);
    return new EtaUpdated(
        "evt-eta-" + shipment + "-" + occurredAt.toEpochMilli(),
        shipment,
        occurredAt,
        "evt-cause",
        "blr-hosp",
        estimatedArrival,
        null,
        420.0,
        0.78);
  }

  private ShipmentArrived arrival(Duration afterT0, String stopId) {
    Instant occurredAt = T0.plus(afterT0);
    return new ShipmentArrived(
        "evt-arr-" + shipment + "-" + occurredAt.toEpochMilli(),
        shipment,
        occurredAt,
        "evt-cause",
        stopId,
        new GeoPoint(DELIVERY_LAT, DELIVERY_LON),
        null);
  }

  private void publish(String topic, Object event) {
    String key =
        event instanceof PositionEvent p
            ? p.shipmentId()
            : event instanceof StatusEvent s ? s.shipmentId() : shipment;
    producer.send(
        new ProducerRecord<>(topic, key, EventJson.mapper().writeValueAsString(event)));
    producer.flush();

    if (Topics.POSITION.equals(topic)) {
      publishedPositions++;
    } else if (Topics.STATUS.equals(topic)) {
      publishedStatuses++;
    } else if (Topics.DERIVED.equals(topic)) {
      publishedDerived++;
    }
  }

  /**
   * Waits until the service has actually read everything this test published.
   *
   * <p>Needed by the tests that assert nothing happened, which are otherwise untestable: "no
   * exception was raised" and "the consumer has not got there yet" look identical from outside,
   * and a fixed sleep would turn a genuine regression into an intermittent pass. The consumers'
   * own counters are the honest signal -- they only advance once a record has been handled.
   */
  private void awaitConsumed() {
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(
            () -> {
              assertThat(consumers.positionCount() - positionsBefore)
                  .isGreaterThanOrEqualTo(publishedPositions);
              assertThat(consumers.statusCount() - statusesBefore)
                  .isGreaterThanOrEqualTo(publishedStatuses);
              assertThat(consumers.derivedCount() - derivedBefore)
                  .isGreaterThanOrEqualTo(publishedDerived);
            });
  }

  private void awaitClosedIncident(ExceptionType type) {
    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(
            () -> {
              Incident incident = onlyIncident(type);
              assertThat(incident).isNotNull();
              assertThat(incident.isOpen()).isFalse();
            });
  }

  private List<Incident> openIncidents() {
    return mongo.find(
        Query.query(
            org.springframework.data.mongodb.core.query.Criteria.where("shipmentId")
                .is(shipment)
                .and("state")
                .is(Incident.OPEN)),
        Incident.class);
  }

  private List<Incident> openIncidents(ExceptionType type) {
    return openIncidents().stream().filter(i -> i.type() == type).toList();
  }

  private Incident onlyIncident(ExceptionType type) {
    List<Incident> all =
        mongo.find(
            Query.query(
                org.springframework.data.mongodb.core.query.Criteria.where("shipmentId")
                    .is(shipment)
                    .and("type")
                    .is(type)),
            Incident.class);
    assertThat(all).hasSizeLessThanOrEqualTo(1);
    return all.isEmpty() ? null : all.getFirst();
  }

  private List<ExceptionRaised> raisedFor(ExceptionType type) {
    return exceptionsOf(ExceptionRaised.class).stream()
        .filter(e -> e.exceptionType() == type)
        .toList();
  }

  private List<ExceptionCleared> clearedFor(ExceptionType type) {
    return exceptionsOf(ExceptionCleared.class).stream()
        .filter(e -> e.exceptionType() == type)
        .toList();
  }

  /**
   * Reads the exception topic and keeps only this shipment's events of one shape.
   *
   * <p>A raise and a clear are told apart by which fields they carry — only a clear has
   * {@code openFor}. The same shape-sniffing the derived topic needs, and kept narrow for the same
   * reason: adding a third shape here must not silently reclassify the first two.
   */
  private <T> List<T> exceptionsOf(Class<T> shape) {
    List<T> found = new ArrayList<>();
    for (ConsumerRecord<String, String> record : drain(Topics.EXCEPTIONS)) {
      if (!shipment.equals(record.key())) {
        continue;
      }
      boolean isClear = record.value().contains("\"openFor\"");
      if ((shape == ExceptionCleared.class) == isClear) {
        found.add(EventJson.mapper().readValue(record.value(), shape));
      }
    }
    return found;
  }

  /**
   * This shipment's dead letters only.
   *
   * <p>A Kafka topic is a log and the tests share one, so a bare drain returns everything every
   * other test set aside as well. Filtering by key is the same defence the per-test shipment id
   * gives the exception topic -- and it is also what the dead-letter topic keeping its key is for.
   */
  private List<ConsumerRecord<String, String>> deadLettersForThisShipment() {
    return drain(ExceptionDeadLetters.TOPIC).stream()
        .filter(record -> shipment.equals(record.key()))
        .toList();
  }

  private static List<ConsumerRecord<String, String>> drain(String topic) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-drain-" + java.util.UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

    List<ConsumerRecord<String, String>> all = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topic));
      for (int attempt = 0; attempt < 5; attempt++) {
        ConsumerRecords<String, String> batch = consumer.poll(Duration.ofSeconds(2));
        batch.forEach(all::add);
      }
    }
    return all;
  }
}
