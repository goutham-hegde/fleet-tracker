package com.fleettracking.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fleettracking.events.EventJson;
import com.fleettracking.events.GeoPoint;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.RawPayload;
import com.fleettracking.events.Severity;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.events.Topics;
import com.fleettracking.reference.Itinerary;
import com.fleettracking.reference.ScheduledStop;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;

/**
 * The dashboard's two halves, end to end: the snapshot and the stream.
 *
 * <h2>Why the documents are seeded as raw BSON</h2>
 *
 * <p>The obvious thing would be to save the projection records this service reads. That would prove
 * nothing worth proving — a test that writes through the same record it reads back is satisfied by
 * any pair of matching field names, including a pair that has drifted away from what the platform
 * actually writes.
 *
 * <p>So the documents here are built field by field, with the names the tracking processor, the
 * exception service and the shipment service really use. It is a second statement of the contract in
 * {@code Views}, written from the other side, and it is the thing that fails if a projection is
 * renamed. What it cannot catch is the other direction — a writer renaming a field — which is
 * checked by running the real platform, and is noted as a limitation rather than pretended away.
 *
 * <h2>The stream test is M5's first exit criterion</h2>
 *
 * <p>"{@code curl} on the SSE endpoint streams live position and exception events" — with a JDK
 * HTTP client standing in for curl, because it can be asserted on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DashboardApiIT {

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

  private static final Instant T0 = Instant.parse("2026-09-08T08:00:00Z");
  private static final String SHIPMENT = "SHP-DEL-0001";

  /** The Okhla DC in Delhi and the Jaipur depot, as the committed lane catalogue states them. */
  private static final ScheduledStop OKHLA =
      new ScheduledStop("del-okhla", 0, "Okhla DC", "Delhi", "DL", 28.5355, 77.2730, 400, "PICKUP");

  private static final ScheduledStop JAIPUR =
      new ScheduledStop(
          "jai-vki", 1, "Jaipur VKI depot", "Jaipur", "RJ", 26.9124, 75.7873, 400, "DELIVERY");

  @LocalServerPort private int port;

  @Autowired private MongoOperations mongo;
  @Autowired private com.mongodb.client.MongoClient mongoClient;

  private static Producer<String, String> producer;
  private final HttpClient http = HttpClient.newHttpClient();

  /** Every open stream, so they can be closed: see {@link #closeTheStreams()}. */
  private static final List<CompletableFuture<?>> subscriptions = new CopyOnWriteArrayList<>();

  @DynamicPropertySource
  static void containerAddresses(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.mongodb.uri", MONGO::getConnectionString);
    registry.add("spring.mongodb.database", () -> "fleet");
    registry.add("fleet.dashboard.heartbeat-interval", () -> "1h");
    // Nothing is thinned here: a test asserting that an event reaches a viewer must not race the
    // gate that exists to stop a browser drowning.
    registry.add("fleet.dashboard.stream.sample-interval", () -> "0ms");
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
                    new NewTopic(Topics.EXCEPTIONS, 3, (short) 1)))
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
  void seedWhatTheOtherServicesWouldHaveWritten() {
    for (String collection :
        List.of(
            "shipment.position",
            "shipment.eta",
            "geofence.state",
            "exceptions",
            "manifests",
            "position.history")) {
      mongo.remove(new Query(), collection);
    }
    mongo.remove(new Query(), Itinerary.COLLECTION);

    // The plan, from the shared reference model both consumers already use.
    mongo.save(new Itinerary(SHIPMENT, "del-bom-nh48", List.of(OKHLA, JAIPUR)));

    // What the tracking processor writes: one current position per shipment. Field names are the
    // writer's, not this service's projection's.
    mongo.insert(
        new Document("_id", SHIPMENT)
            .append("eventId", "evt-current")
            .append("vehicleId", "VEH-0001")
            .append("deviceId", "DEV-0001")
            .append("occurredAt", T0.plusSeconds(3600))
            .append("receivedAt", T0.plusSeconds(3600))
            .append("updatedAt", T0.plusSeconds(3600))
            .append("location", geoJson(28.0000, 76.8000))
            .append("speedKph", 62.0)
            .append("headingDegrees", 250.0)
            .append("odometerKm", 184_000.0)
            .append("accuracyMeters", 6.0)
            .append("source", "TELEMATICS"),
        "shipment.position");

    // Its geofence state: the pickup has been arrived at and departed from.
    mongo.insert(
        new Document("_id", SHIPMENT + "|del-okhla")
            .append("shipmentId", SHIPMENT)
            .append("stopId", "del-okhla")
            .append("inside", false)
            .append("enteredAt", T0)
            .append("leftAt", T0.plusSeconds(2400))
            .append("arrivalAnnounced", true)
            .append("arrivalOccurredAt", T0)
            .append("departureAnnounced", true)
            .append("lastFixAt", T0.plusSeconds(3600)),
        "geofence.state");

    // Its ETA state, naming the stop it is actually driving to.
    mongo.insert(
        new Document("_id", SHIPMENT)
            .append("stopId", "jai-vki")
            .append("estimatedArrival", T0.plusSeconds(12_000))
            .append("remainingKm", 999.0)
            .append("confidence", 0.77)
            .append("expectedSpeedKph", 61.0)
            .append("movingSeconds", 3400L)
            .append("lastMovingAt", T0.plusSeconds(3600))
            .append("lastFixAt", T0.plusSeconds(3600))
            .append("updatedAt", T0.plusSeconds(3600)),
        "shipment.eta");

    // What the exception service writes: one open incident and one already cleared.
    mongo.insert(
        new Document("_id", "inc-open")
            .append("shipmentId", SHIPMENT)
            .append("type", "ROUTE_DEVIATION")
            .append("severity", Severity.WARNING.name())
            .append("state", "OPEN")
            .append("onsetAt", T0.plusSeconds(3000))
            .append("raisedAt", T0.plusSeconds(3300))
            .append("lastSeenAt", T0.plusSeconds(3600))
            .append("detail", "18.4 km off the planned corridor"),
        "exceptions");
    mongo.insert(
        new Document("_id", "inc-cleared")
            .append("shipmentId", SHIPMENT)
            .append("type", "UNPLANNED_STOP")
            .append("severity", Severity.INFO.name())
            .append("state", "CLEARED")
            .append("onsetAt", T0.plusSeconds(600))
            .append("raisedAt", T0.plusSeconds(900))
            .append("clearedAt", T0.plusSeconds(1500))
            .append("resolution", "MOVING_AGAIN"),
        "exceptions");

    // What the shipment service writes: a typed envelope around an untyped body.
    mongo.insert(
        new Document("_id", SHIPMENT)
            .append("customerId", "MEDIVAULT")
            .append("mode", "COLD_CHAIN")
            .append("schemaVersion", "1.0.0")
            .append("createdAt", T0.minusSeconds(86_400))
            .append(
                "body",
                new Document("temperature", new Document("minCelsius", 2).append("maxCelsius", 8))
                    .append("consignee", "Apollo Pharmacy, Jaipur")),
        "manifests");

    // Two stored measurements, for the trail.
    mongo.insert(
        new Document("_id", "evt-1")
            .append("ts", T0.plusSeconds(3000))
            .append("shipmentId", SHIPMENT)
            .append("location", geoJson(28.2000, 77.0000))
            .append("speedKph", 58.0)
            .append("headingDegrees", 250.0)
            .append("source", "TELEMATICS"),
        "position.history");
    mongo.insert(
        new Document("_id", "evt-2")
            .append("ts", T0.plusSeconds(3600))
            .append("shipmentId", SHIPMENT)
            .append("location", geoJson(28.0000, 76.8000))
            .append("speedKph", 62.0)
            .append("headingDegrees", 250.0)
            .append("source", "TELEMATICS"),
        "position.history");
  }

  // -------------------------------------------------------------------------------------------

  /**
   * That this test is talking to its own database and not to the developer's.
   *
   * <p>The same assertion the gateway's integration test carries, for the same reason: Spring Boot
   * 4's renamed MongoDB properties bind to nothing rather than failing, and the fallback default is
   * a real, unrelated MongoDB on this machine. For a read-only service the symptom would be a fleet
   * view that is simply empty, which is indistinguishable from a quiet platform.
   */
  @Test
  void connectsToItsOwnContainerAndNotToWhateverIsOnTheDefaultPort() {
    int connectedPort =
        mongoClient.getClusterDescription().getServerDescriptions().getFirst().getAddress().getPort();

    assertThat(connectedPort).isEqualTo(MONGO.getFirstMappedPort());
    assertThat(connectedPort).isNotEqualTo(27017);
  }

  @Test
  void theFleetViewJoinsFourServicesDocumentsIntoOneMarker() {
    JsonNode fleet = getJson("/api/shipments");

    assertThat(fleet.size()).isEqualTo(1);
    JsonNode truck = fleet.get(0);

    assertThat(truck.path("shipmentId").asString()).isEqualTo(SHIPMENT);
    assertThat(truck.path("vehicleId").asString()).isEqualTo("VEH-0001");
    assertThat(truck.path("movement").asString()).isEqualTo("MOVING");
    assertThat(truck.path("latitude").asDouble()).isEqualTo(28.0);
    assertThat(truck.path("stopsCompleted").asInt()).isEqualTo(1);
    assertThat(truck.path("stopsTotal").asInt()).isEqualTo(2);

    // The estimate names the stop the truck is heading for, so it is shown.
    assertThat(truck.path("nextStop").path("stopId").asString()).isEqualTo("jai-vki");
    assertThat(truck.path("nextStop").path("estimatedArrival").asString())
        .isEqualTo(T0.plusSeconds(12_000).toString());
    assertThat(truck.path("nextStop").has("estimatePending")).isFalse();

    // Only the open incident reaches the marker, and it sets the summary's severity.
    assertThat(truck.path("openExceptions").size()).isEqualTo(1);
    assertThat(truck.path("openExceptions").get(0).path("type").asString())
        .isEqualTo("ROUTE_DEVIATION");
    assertThat(truck.path("worstSeverity").asString()).isEqualTo("WARNING");
  }

  /**
   * The distance is measured from the live position, not read from the estimate.
   *
   * <p>The seeded ETA document says {@code remainingKm: 999}, which is what S11 would have written
   * when that estimate was last published. The truck is about 190 km from Jaipur. A dashboard
   * reading the stored field would report a shipment on the wrong continent, and would be reporting
   * it from a document with a perfectly fresh {@code updatedAt}.
   */
  @Test
  void theRemainingDistanceIsMeasuredRatherThanRead() {
    JsonNode truck = getJson("/api/shipments").get(0);

    double remaining = truck.path("nextStop").path("remainingKm").asDouble();

    assertThat(remaining).isNotEqualTo(999.0);
    assertThat(remaining).isBetween(150.0, 300.0);
  }

  @Test
  void theDetailCarriesTheManifestBodyExactlyAsTheCustomerSentIt() {
    JsonNode detail = getJson("/api/shipments/" + SHIPMENT);

    assertThat(detail.path("manifest").path("customerId").asString()).isEqualTo("MEDIVAULT");
    // Nothing in this service knows what a cold-chain manifest looks like; the body is passed on.
    assertThat(detail.path("manifest").path("body").path("temperature").path("maxCelsius").asInt())
        .isEqualTo(8);
    assertThat(detail.path("manifest").path("body").path("consignee").asString())
        .isEqualTo("Apollo Pharmacy, Jaipur");

    // The plan, with what happened at each stop folded in.
    assertThat(detail.path("stops").size()).isEqualTo(2);
    assertThat(detail.path("stops").get(0).path("status").asString()).isEqualTo("DEPARTED");
    assertThat(detail.path("stops").get(0).path("dwellSeconds").asLong()).isEqualTo(2400);
    assertThat(detail.path("stops").get(1).path("status").asString()).isEqualTo("PENDING");

    // Both incidents, unlike the marker, which showed only the open one.
    assertThat(detail.path("exceptions").size()).isEqualTo(2);

    // The trail, oldest first.
    assertThat(detail.path("track").size()).isEqualTo(2);
    assertThat(detail.path("track").get(0).path("ts").asString())
        .isEqualTo(T0.plusSeconds(3000).toString());
  }

  @Test
  void aShipmentNothingHasReportedIsNotFound() {
    HttpResponse<String> response = get("/api/shipments/SHP-NOBODY-0000");

    assertThat(response.statusCode()).isEqualTo(404);
  }

  /**
   * The exceptions panel, and the field this endpoint is useless without.
   *
   * <p>The first version of this test asserted only how many incidents came back, which passed
   * while every row was missing its {@code shipmentId} — fine nested inside a shipment, where the
   * context is implied, and meaningless in a flat list where a panel has to link a row to a marker.
   * Found by querying the running service. A count is not an assertion about content.
   */
  @Test
  void theExceptionsPanelShowsWhatIsOpenAndSaysWhichShipmentEachBelongsTo() {
    JsonNode open = getJson("/api/exceptions");

    assertThat(open.size()).isEqualTo(1);
    assertThat(open.get(0).path("shipmentId").asString()).isEqualTo(SHIPMENT);
    assertThat(open.get(0).path("type").asString()).isEqualTo("ROUTE_DEVIATION");
    assertThat(open.get(0).path("detail").asString()).contains("off the planned corridor");

    JsonNode everything = getJson("/api/exceptions?open=false");
    assertThat(everything.size()).isEqualTo(2);
    assertThat(everything).allMatch(incident -> !incident.path("shipmentId").asString().isBlank());
  }

  /**
   * M5's first exit criterion: a live position event reaches a subscriber.
   *
   * <p>The event is published repeatedly rather than once. The stream begins at the moment of
   * subscription — {@code auto-offset-reset} is {@code latest} here, deliberately — so a single
   * event published before this instance's consumer had finished joining its group would be missed,
   * and the test would fail for a reason that has nothing to do with the code under test.
   */
  @Test
  void aLivePositionEventReachesASubscriber() {
    List<String> lines = subscribeToTheStream();

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(
            () -> {
              publish(Topics.POSITION, SHIPMENT, EventJson.mapper().writeValueAsString(aFix()));
              assertThat(lines).anyMatch(line -> line.startsWith("event:position"));
              assertThat(lines)
                  .anyMatch(line -> line.startsWith("data:") && line.contains(SHIPMENT));
            });

    String data =
        lines.stream()
            .filter(line -> line.startsWith("data:") && line.contains(SHIPMENT))
            .findFirst()
            .orElseThrow();

    // The raw source payload is deliberately not forwarded: it is the largest part of a canonical
    // event and no use at all to a map.
    assertThat(data).doesNotContain("\"raw\"");
    assertThat(data).contains("\"latitude\"").contains("\"speedKph\"");
  }

  /** And an exception event, which is the other half of the same criterion. */
  @Test
  void aRaisedExceptionReachesASubscriber() {
    List<String> lines = subscribeToTheStream();

    // Written out rather than serialized from the record, so that this states what is actually on
    // the topic -- including the "type" discriminator every event on this platform carries.
    String raised =
        """
        {"type":"exception.raised","eventId":"evt-x","shipmentId":"%s","occurredAt":"%s",\
        "causedBy":"evt-1","exceptionId":"inc-live","exceptionType":"TEMPERATURE_EXCURSION",\
        "severity":"CRITICAL","detail":"31.7C is outside the agreed 2.0 to 8.0C band"}"""
            .formatted(SHIPMENT, T0.plusSeconds(4000));

    await()
        .atMost(Duration.ofSeconds(60))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(
            () -> {
              publish(Topics.EXCEPTIONS, SHIPMENT, raised);
              assertThat(lines).anyMatch(line -> line.startsWith("event:exception.raised"));
              assertThat(lines).anyMatch(line -> line.contains("inc-live"));
            });
  }

  @Test
  void theMetaEndpointSaysWhatTheServiceCanSee() {
    JsonNode meta = getJson("/api/meta");

    assertThat(meta.path("trackedShipments").asLong()).isEqualTo(1);
    assertThat(meta.path("openExceptions").asLong()).isEqualTo(1);
  }

  // -------------------------------------------------------------------------------------------

  private static Document geoJson(double latitude, double longitude) {
    // GeoJSON is [longitude, latitude] -- the opposite order to how a person states a coordinate,
    // and the commonest way to put a truck in the wrong hemisphere.
    return new Document("type", "Point").append("coordinates", List.of(longitude, latitude));
  }

  private PositionEvent aFix() {
    return new PositionEvent(
        "evt-live-" + System.nanoTime(),
        SHIPMENT,
        "VEH-0001",
        "DEV-0001",
        T0.plusSeconds(4000),
        T0.plusSeconds(4000),
        new GeoPoint(27.9, 76.7),
        61.0,
        250.0,
        184_100.0,
        6.0,
        new RawPayload(SourceSystem.TELEMATICS, "application/json", "{\"vin\":\"...\"}"));
  }

  private void publish(String topic, String key, String value) {
    try {
      producer.send(new ProducerRecord<>(topic, key, value)).get();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    } catch (ExecutionException failed) {
      throw new IllegalStateException(failed);
    }
  }

  /**
   * Opens the stream on a background thread and collects the raw lines.
   *
   * <p>Raw lines rather than parsed events on purpose: what is asserted is the wire format a browser
   * or {@code curl} actually sees, {@code event:} and {@code data:} and all.
   */
  private List<String> subscribeToTheStream() {
    List<String> lines = new CopyOnWriteArrayList<>();
    CompletableFuture<?> subscription =
        http.sendAsync(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/stream"))
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofLines())
            .thenAccept(response -> response.body().forEach(lines::add));
    subscriptions.add(subscription);
    return lines;
  }

  /**
   * Closes every stream this test opened.
   *
   * <p>A connection to {@code /api/stream} never ends on its own — that is the whole point of it —
   * so a test that opens one and walks away leaves the client reading for ever. The symptom is not a
   * failure: the tests pass and then the forked JVM will not exit, and Failsafe kills it thirty
   * seconds after {@code System.exit}, printing an error that names neither this test nor the
   * stream. Thirty seconds on every build for a connection nobody closed.
   */
  @AfterAll
  static void closeTheStreams() {
    subscriptions.forEach(subscription -> subscription.cancel(true));
    subscriptions.clear();
  }

  private HttpResponse<String> get(String path) {
    try {
      return http.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (IOException failed) {
      throw new UncheckedIOException(failed);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private JsonNode getJson(String path) {
    HttpResponse<String> response = get(path);
    assertThat(response.statusCode()).isEqualTo(200);
    return EventJson.mapper().readTree(response.body());
  }
}
