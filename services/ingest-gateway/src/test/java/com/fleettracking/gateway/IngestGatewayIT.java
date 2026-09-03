package com.fleettracking.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.Event;
import com.fleettracking.events.EventJson;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.SourceEvent;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.events.StatusEvent;
import com.fleettracking.events.Topics;
import com.fleettracking.gateway.web.IngestResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.mongodb.client.MongoClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The whole gateway against a real broker: HTTP in, Kafka out.
 *
 * <p>The unit tests prove the normalizer converts correctly. They cannot prove that the event
 * survives serialization, that the partition key is what the platform's ordering guarantee assumes,
 * that the producer configuration lets a message through at all, or that a rejected message really
 * does end up on the dead-letter topic and really does not end up on the canonical one. Every one
 * of those is a wiring question, and wiring questions are only answered by wiring.
 *
 * <p>Testcontainers starts an actual Kafka broker in Docker for the duration of the class. The
 * image is pinned to the same version the cluster runs, because a test against a different broker
 * than production is a test of a different system.
 *
 * <p>The topics are created explicitly rather than left to the broker's auto-creation, matching what
 * the {@code kafka-topics} Job does on the cluster. Auto-creation would silently produce a
 * single-partition topic, and a test that only ever exercises one partition cannot notice a
 * producer that forgot to set a key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class IngestGatewayIT {

  @Container
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  /**
   * Identity resolution reads dispatch reference data from MongoDB as of S8, so proving that a
   * probe's device id reaches Kafka as a shipment id now requires a database as well as a broker.
   * That is the honest shape of the test: the claim being made is about the deployed system, and
   * in the deployed system that lookup is a query.
   */
  @Container
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

  @Autowired private MongoOperations mongo;
  @Autowired private MongoClient mongoClient;

  @LocalServerPort private int port;

  /**
   * A plain JDK client rather than a Spring test client, for the same reason the simulator uses
   * one: what is being tested is an endpoint a telematics vendor will call with no Spring on their
   * side at all. Anything that binds request and response through the framework's own conversion
   * would be testing that conversion as much as the gateway.
   */
  private final HttpClient client = HttpClient.newHttpClient();

  @DynamicPropertySource
  static void containerAddresses(DynamicPropertyRegistry registry) {
    // Each container's port is assigned when it starts, so neither can be written in
    // application.yaml.
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    // spring.mongodb, not spring.data.mongodb: Boot 4 deprecated the Spring Data namespace for
    // connection settings at level "error", so the old names bind to nothing at all. Registering
    // them here would leave the default mongodb://localhost/test in place, and this test would
    // quietly read and write the developer's own local MongoDB instead of its container.
    registry.add("spring.mongodb.uri", MONGO::getConnectionString);
    registry.add("spring.mongodb.database", () -> "fleet");
  }

  /**
   * Reference data, seeded before every test.
   *
   * <p>Seeded here rather than assumed, because an empty collection would make every assertion in
   * this class fail as an unresolved identity -- which reads like four broken normalizers rather
   * than like a database nobody filled in. The assignments are open-ended and backdated well before
   * the committed fixtures were captured; see {@link Fixtures#FLEET_EPOCH}.
   */
  @BeforeEach
  void seedReferenceData() {
    mongo.remove(new Query(), com.fleettracking.gateway.identity.Assignment.class);
    mongo.insertAll(Fixtures.defaultFleetAssignments());
  }

  @BeforeAll
  static void createTopics() throws Exception {
    try (Admin admin =
        Admin.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      try {
        admin
            .createTopics(
                List.of(
                    new NewTopic(Topics.POSITION, 12, (short) 1),
                    new NewTopic(Topics.STATUS, 3, (short) 1),
                    new NewTopic(Topics.DEAD_LETTER, 3, (short) 1)))
            .all()
            .get();
      } catch (ExecutionException e) {
        if (!(e.getCause() instanceof TopicExistsException)) {
          throw e;
        }
      }
    }
  }

  // -------------------------------------------------------------------------------------------

  /**
   * That this test is talking to its own database and not to the developer's.
   *
   * <p>Written after it was not. Spring Boot 4 renamed the MongoDB connection properties, and the
   * old names bind to nothing rather than failing, so the application fell back to the default
   * {@code mongodb://localhost/test}. This machine has an unrelated MongoDB on that port, which
   * accepted the connection and answered every query -- so the suite passed while seeding reference
   * data into a database belonging to something else entirely.
   *
   * <p>Nothing else here can catch that, because every other assertion is satisfied by any database
   * that stores what it is given. This one asserts the address.
   */
  @Test
  void connectsToItsOwnContainerAndNotToWhateverIsOnTheDefaultPort() {
    int connectedPort =
        mongoClient.getClusterDescription().getServerDescriptions().getFirst().getAddress().getPort();

    assertThat(connectedPort).isEqualTo(MONGO.getFirstMappedPort());
    assertThat(connectedPort).isNotEqualTo(27017);
  }

  @Test
  void aSimulatedTruckPositionReachesTheCanonicalTopicKeyedByShipment() {
    String captured = Fixtures.lines("telematics.jsonl").getFirst();

    Posted response = post("/ingest/telematics", "application/json", captured);

    assertThat(response.status()).isEqualTo(202);
    assertThat(response.body()).isNotNull();
    assertThat(response.body().outcome()).isEqualTo(IngestResponse.Outcome.ACCEPTED);
    assertThat(response.body().published()).isEqualTo(1);

    List<ConsumerRecord<String, String>> positions =
        drain(Topics.POSITION).stream().filter(r -> captured.equals(rawBodyOf(r))).toList();
    assertThat(positions).hasSize(1);
    ConsumerRecord<String, String> record = positions.getFirst();

    // The key is the whole ordering guarantee. Without it the record goes to a partition chosen at
    // random and one shipment's history is spread across twelve of them in no particular order.
    assertThat(record.key()).isEqualTo("SHP-LAX-0002");

    // Read as Event, not as PositionEvent: that is what a consumer subscribed to the topic does,
    // and it exercises the "type" discriminator the sealed hierarchy writes.
    PositionEvent event = (PositionEvent) EventJson.mapper().readValue(record.value(), Event.class);
    assertThat(event.shipmentId()).isEqualTo("SHP-LAX-0002");
    assertThat(event.vehicleId()).isEqualTo("VEH-0002");
    assertThat(event.deviceId()).isEqualTo("TLM-0002");
    assertThat(event.position().latitude()).isCloseTo(34.052209, org.assertj.core.data.Offset.offset(1e-6));
    // Imperial in, metric out: the captured odometer reads 51665.8 miles.
    assertThat(event.odometerKm()).isCloseTo(83148.05, org.assertj.core.data.Offset.offset(0.05));
    // HDOP 1.09 became a radius in metres rather than being copied across.
    assertThat(event.accuracyMeters()).isCloseTo(5.45, org.assertj.core.data.Offset.offset(0.001));
    assertThat(event.raw().source()).isEqualTo(SourceSystem.TELEMATICS);
    assertThat(event.raw().body()).isEqualTo(captured);
  }

  @Test
  void aCorruptPayloadReachesTheDeadLetterTopicAndNowhereElse() {
    // Genuinely broken bytes, marked so this test can find its own message among the others.
    String corrupt = "{\"marker\":\"dlq-probe\",\"deviceId\":\"TLM-0003\",\"gps\":{\"lat\":34.0";

    Posted response = post("/ingest/telematics", "application/json", corrupt);

    // 202, not 400: the message is durably stored and resending the same bytes cannot help.
    assertThat(response.status()).isEqualTo(202);
    assertThat(response.body()).isNotNull();
    assertThat(response.body().outcome()).isEqualTo(IngestResponse.Outcome.DEAD_LETTERED);
    assertThat(response.body().reason()).isEqualTo("MALFORMED_PAYLOAD");

    List<ConsumerRecord<String, String>> dead =
        drain(Topics.DEAD_LETTER).stream().filter(r -> r.value().contains("dlq-probe")).toList();
    assertThat(dead).hasSize(1);
    assertThat(dead.getFirst().value()).contains("MALFORMED_PAYLOAD").contains("TELEMATICS");
    assertThat(headerOf(dead.getFirst(), "fleet.rejection-reason")).isEqualTo("MALFORMED_PAYLOAD");

    // "And nowhere else" is half the requirement. A gateway that dead-letters a message and also
    // publishes a half-built version of it is worse than one that drops it.
    assertThat(drain(Topics.POSITION))
        .as("a rejected message must not appear on the canonical topic")
        .noneSatisfy(r -> assertThat(r.value()).contains("dlq-probe"));
  }

  @Test
  void aTruckWithNoAssignedLoadIsDeadLetteredRatherThanGuessedAt() {
    String unknown =
        """
        {"marker":"identity-probe","deviceId":"TLM-4242","vehicle":{"id":"VEH-4242"},
         "gps":{"lat":35.1495,"lon":-90.049,"speedMph":55.0,"headingDeg":47.4,"hdop":1.0,
                "fixTime":"2026-08-31T14:05:00Z"},
         "odometer":{"value":100.0,"unit":"mi"},"sentAt":"2026-08-31T14:05:02Z"}
        """;

    Posted response = post("/ingest/telematics", "application/json", unknown);

    assertThat(response.body()).isNotNull();
    assertThat(response.body().reason()).isEqualTo("UNRESOLVED_IDENTITY");

    List<ConsumerRecord<String, String>> dead =
        drain(Topics.DEAD_LETTER).stream().filter(r -> r.value().contains("identity-probe")).toList();
    assertThat(dead).hasSize(1);
    // Unkeyed on purpose: there is no shipment to key it by, which is the reason it was rejected.
    assertThat(dead.getFirst().key()).isNull();
  }

  @Test
  void allFourFeedsLandOnTheRightTopicKeyedByTheSameShipment() {
    // The point of the milestone, in one test. Four systems that share no format, no units, no time
    // representation and no idea of each other's identifiers describe the same shipment, and what
    // comes out the far side is one stream of canonical events under one key.
    //
    // The four captured payloads deliberately concern SHP-LAX-0002, which the fleet knows as
    // VEH-0002 with a telematics unit TLM-0002 and a reefer probe DEV-0002. Only the phone names
    // the shipment; telematics names the truck, the probe names itself, and the carrier's
    // interchange names several shipments at once.
    // Deliberately not the same captured messages the single-feed tests in this class post. Every
    // test here drains the topics from the beginning, and a resent payload produces a byte-identical
    // event by design, so two tests posting one payload would each see the other's record.
    String telematics = lastFixtureLineFor("telematics.jsonl", "TLM-0002");
    String mobile = firstFixtureLineFor("mobile-app.jsonl", "SHP-LAX-0002");
    String reefer = firstFixtureLineFor("reefer-sensor.jsonl", "DEV-0002");
    String edi = interchangeFor("SHP-LAX-0002");

    assertThat(post("/ingest/telematics", "application/json", telematics).status()).isEqualTo(202);
    assertThat(post("/ingest/mobile", "application/json", mobile).status()).isEqualTo(202);
    assertThat(post("/ingest/reefer", "application/json", reefer).status()).isEqualTo(202);
    assertThat(post("/ingest/edi214", "application/edi-x12", edi).status()).isEqualTo(202);

    List<ConsumerRecord<String, String>> positions = drain(Topics.POSITION);
    List<ConsumerRecord<String, String>> statuses = drain(Topics.STATUS);

    // Positions from the two feeds that carry coordinates.
    assertThat(sourcesOf(positions, "SHP-LAX-0002"))
        .contains(SourceSystem.TELEMATICS, SourceSystem.MOBILE_APP);
    // Statuses from the two that do not: a temperature with no position, and a carrier's status
    // with a place name and no position.
    assertThat(sourcesOf(statuses, "SHP-LAX-0002"))
        .contains(SourceSystem.REEFER_SENSOR, SourceSystem.EDI_214);

    // Every one of them keyed by the shipment, which is what puts four dissimilar sources onto one
    // partition and makes their relative order mean something.
    assertThat(positions).filteredOn(r -> "SHP-LAX-0002".equals(r.key())).isNotEmpty();
    assertThat(statuses).filteredOn(r -> "SHP-LAX-0002".equals(r.key())).isNotEmpty();
  }

  @Test
  void oneCarrierInterchangeBecomesOneEventPerShipmentInIt() {
    // An interchange has no shipment id of its own and therefore arrives with no Kafka key. It is
    // only after the gateway splits it that anything can be keyed at all.
    String batched = interchangeWithMostShipments();

    Posted response = post("/ingest/edi214", "application/edi-x12", batched);

    assertThat(response.status()).isEqualTo(202);
    assertThat(response.body()).isNotNull();
    assertThat(response.body().outcome()).isEqualTo(IngestResponse.Outcome.ACCEPTED);
    assertThat(response.body().published()).isGreaterThan(1);

    List<ConsumerRecord<String, String>> statuses =
        drain(Topics.STATUS).stream().filter(r -> batched.equals(rawBodyOf(r))).toList();
    assertThat(statuses).hasSize(response.body().published());
    // One message in, several keys out, and no record left unkeyed.
    assertThat(statuses)
        .extracting(ConsumerRecord::key)
        .doesNotContainNull()
        .doesNotHaveDuplicates();
  }

  @Test
  void aPartlyDamagedBatchPublishesWhatSurvivedAndDeadLettersTheOriginal() {
    // Truncated in the middle of the third transaction set, leaving the first two intact. The
    // carrier has already sent this batch and will not send it again.
    String batched = interchangeWithMostShipments();
    int thirdSet = batched.indexOf("ST*214*0003");
    assertThat(thirdSet).as("fixture should carry at least three transaction sets").isPositive();
    String truncated = batched.substring(0, thirdSet + 40);

    Posted response = post("/ingest/edi214", "application/edi-x12", truncated);

    assertThat(response.status()).isEqualTo(202);
    assertThat(response.body()).isNotNull();
    assertThat(response.body().outcome()).isEqualTo(IngestResponse.Outcome.PARTIAL);
    assertThat(response.body().published()).isEqualTo(2);
    assertThat(response.body().deadLettered()).isEqualTo(1);

    // The two readable statuses reached the canonical topic.
    assertThat(drain(Topics.STATUS).stream().filter(r -> truncated.equals(rawBodyOf(r))).toList())
        .hasSize(2);
    // And the original interchange is on the dead-letter topic whole, so nothing is lost in either
    // direction. Replaying it later regenerates identical event ids for the two already published,
    // which is the only reason publishing and dead-lettering one message is safe.
    List<ConsumerRecord<String, String>> dead =
        drain(Topics.DEAD_LETTER).stream()
            .filter(r -> r.value().contains("transaction set 3"))
            .toList();
    assertThat(dead).hasSize(1);
    assertThat(headerOf(dead.getFirst(), "fleet.source")).isEqualTo("EDI_214");
  }

  @Test
  void aReeferReadingIsAttributedFromNothingButItsDeviceId() {
    // Again a different captured reading from the four-feed test's, so neither sees the other's.
    String reading = lastFixtureLineFor("reefer-sensor.jsonl", "DEV-0002");

    assertThat(post("/ingest/reefer", "application/json", reading).status()).isEqualTo(202);

    List<ConsumerRecord<String, String>> statuses =
        drain(Topics.STATUS).stream().filter(r -> reading.equals(rawBodyOf(r))).toList();
    assertThat(statuses).hasSize(1);

    // The probe named a device and nothing else. The key it ended up under came entirely from
    // reference data, which is what S8 replaces with a real lookup.
    assertThat(statuses.getFirst().key()).isEqualTo("SHP-LAX-0002");
    StatusEvent event =
        (StatusEvent) EventJson.mapper().readValue(statuses.getFirst().value(), Event.class);
    assertThat(event.deviceId()).isEqualTo("DEV-0002");
    assertThat(event.vehicleId()).isEqualTo("VEH-0002");
    assertThat(event.temperature().celsius()).isNotNull();
    assertThat(event.position()).as("a probe reports no position").isNull();
  }

  // -------------------------------------------------------------------------------------------

  /** The first captured payload of a feed mentioning a given identifier. */
  private static String firstFixtureLineFor(String fixture, String identifier) {
    return Fixtures.lines(fixture).stream()
        .filter(line -> line.contains(identifier))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(identifier + " absent from " + fixture));
  }

  /** The last captured payload of a feed mentioning a given identifier. */
  private static String lastFixtureLineFor(String fixture, String identifier) {
    return Fixtures.lines(fixture).stream()
        .filter(line -> line.contains(identifier))
        .reduce((first, second) -> second)
        .orElseThrow(() -> new IllegalStateException(identifier + " absent from " + fixture));
  }

  /**
   * An interchange mentioning a shipment, and never the one the batch tests post — for the same
   * reason those tests pick different captured lines: they all read the topics from the start.
   */
  private static String interchangeFor(String shipmentId) {
    String usedByTheBatchTests = interchangeWithMostShipments();
    return interchanges().stream()
        .filter(body -> body.contains(shipmentId) && !body.equals(usedByTheBatchTests))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(shipmentId + " absent from the interchanges"));
  }

  /** The committed interchange batching the most shipments, chosen by content rather than by name. */
  private static String interchangeWithMostShipments() {
    return interchanges().stream()
        .max(Comparator.comparingInt(body -> body.split("ST\\*214", -1).length))
        .orElseThrow();
  }

  private static List<String> interchanges() {
    try (Stream<Path> files = Files.list(Fixtures.samples().resolve("edi-214"))) {
      List<String> bodies = new ArrayList<>();
      for (Path file : files.sorted().toList()) {
        bodies.add(Files.readString(file, StandardCharsets.UTF_8));
      }
      return bodies;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Which feeds produced the records on a topic for one shipment. */
  private static List<SourceSystem> sourcesOf(
      List<ConsumerRecord<String, String>> records, String shipmentId) {
    return records.stream()
        .map(r -> EventJson.mapper().readValue(r.value(), Event.class))
        .filter(SourceEvent.class::isInstance)
        .map(SourceEvent.class::cast)
        .filter(e -> shipmentId.equals(e.shipmentId()))
        .map(e -> e.raw().source())
        .distinct()
        .toList();
  }

  // -------------------------------------------------------------------------------------------

  /** One POST to the running gateway, with the response body parsed as the gateway's own type. */
  private Posted post(String path, String contentType, String body) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
              .timeout(Duration.ofSeconds(20))
              .header("Content-Type", contentType)
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      IngestResponse parsed =
          response.body() == null || response.body().isBlank()
              ? null
              : EventJson.mapper().readValue(response.body(), IngestResponse.class);
      return new Posted(response.statusCode(), parsed);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** An HTTP status and the gateway's parsed answer. */
  record Posted(int status, IngestResponse body) {}

  /**
   * Reads a topic from the beginning with a throwaway consumer group.
   *
   * <p>A new group each time means every call sees the whole topic rather than only what has
   * arrived since the last one, so tests do not depend on the order they run in. Polling stops
   * after two consecutive empty polls: the first poll of a fresh subscription usually returns
   * nothing while the group is being assigned its partitions, so a single empty result proves
   * nothing.
   */
  private static List<ConsumerRecord<String, String>> drain(String topic) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

    List<ConsumerRecord<String, String>> collected = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(topic));
      int emptyPolls = 0;
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (emptyPolls < 2 && System.nanoTime() < deadline) {
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
        if (records.isEmpty()) {
          emptyPolls++;
        } else {
          emptyPolls = 0;
          records.forEach(collected::add);
        }
      }
    }
    return collected;
  }

  /**
   * The {@code raw.body} of a published event, used to find the record a given test produced.
   *
   * <p>Read as {@link SourceEvent} rather than as a position: since S7 the status topic carries
   * temperatures and carrier statuses too, and the {@code raw} field is common to both envelopes.
   */
  private static String rawBodyOf(ConsumerRecord<String, String> record) {
    return ((SourceEvent) EventJson.mapper().readValue(record.value(), Event.class)).raw().body();
  }

  private static String headerOf(ConsumerRecord<String, String> record, String key) {
    var header = record.headers().lastHeader(key);
    return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
  }
}
