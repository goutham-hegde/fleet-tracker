package com.fleettracking.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.Event;
import com.fleettracking.events.EventJson;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.gateway.publish.Topics;
import com.fleettracking.gateway.web.IngestResponse;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

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

  @LocalServerPort private int port;

  /**
   * A plain JDK client rather than a Spring test client, for the same reason the simulator uses
   * one: what is being tested is an endpoint a telematics vendor will call with no Spring on their
   * side at all. Anything that binds request and response through the framework's own conversion
   * would be testing that conversion as much as the gateway.
   */
  private final HttpClient client = HttpClient.newHttpClient();

  @DynamicPropertySource
  static void kafkaBroker(DynamicPropertyRegistry registry) {
    // The container's port is assigned when it starts, so it cannot be written in application.yaml.
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
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
  void aFeedWithNoNormalizerYetIsRefusedWithoutDeadLetteringGoodData() {
    String mobile = "{\"marker\":\"mobile-probe\",\"sid\":\"SHP-LAX-0002\",\"ts\":1788169339744}";

    Posted response = post("/ingest/mobile", "application/json", mobile);

    // Nothing is wrong with this message; the gateway is unfinished. 503 tells the producer to
    // come back, which is true, rather than filling the rejection topic with valid data.
    assertThat(response.status()).isEqualTo(503);
    assertThat(drain(Topics.DEAD_LETTER))
        .noneSatisfy(r -> assertThat(r.value()).contains("mobile-probe"));
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

  /** The {@code raw.body} of a published event, used to find the record a given test produced. */
  private static String rawBodyOf(ConsumerRecord<String, String> record) {
    return ((PositionEvent) EventJson.mapper().readValue(record.value(), Event.class)).raw().body();
  }

  private static String headerOf(ConsumerRecord<String, String> record, String key) {
    var header = record.headers().lastHeader(key);
    return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
  }
}
