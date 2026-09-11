package com.fleettracking.publicview;

import static com.fleettracking.publicview.Events.arrived;
import static com.fleettracking.publicview.Events.cleared;
import static com.fleettracking.publicview.Events.departed;
import static com.fleettracking.publicview.Events.estimate;
import static com.fleettracking.publicview.Events.position;
import static com.fleettracking.publicview.Events.raised;
import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.Event;
import com.fleettracking.events.EventJson;
import com.fleettracking.events.Severity;
import com.fleettracking.events.Topics;
import com.fleettracking.publicview.index.IndexHandler;
import com.fleettracking.publicview.lookup.LookupHandler;
import com.fleettracking.publicview.plan.Plans;
import com.fleettracking.publicview.store.PublicTable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;

/**
 * Both functions end to end, against DynamoDB Local and an S3 emulator: archive files in the
 * archiver's layout go in through an S3 notification, and the public page's four questions come
 * out through a function URL request. Each handler is driven exactly as the Lambda runtime drives it,
 * with raw JSON in and raw JSON out.
 *
 * <p>What the emulators cannot stand in for is IAM, CloudFront and the notification itself, which
 * are proven against the real account. That is the same line {@code ArchiverIT} draws.
 */
@Testcontainers
class PublicViewIT {

  static final String BUCKET = "fleet-archive-it";
  static final String TABLE = "fleet-tracker-public-it";
  static final String HYD = "SHP-HYD-0002";
  static final String DEL = "SHP-DEL-0001";

  @Container
  static final GenericContainer<?> DYNAMO =
      new GenericContainer<>("amazon/dynamodb-local:3.3.1")
          .withCommand("-jar DynamoDBLocal.jar -inMemory -sharedDb")
          .withExposedPorts(8000)
          .waitingFor(Wait.forListeningPort());

  @Container
  static final GenericContainer<?> S3 =
      new GenericContainer<>("adobe/s3mock:5.2.0")
          .withExposedPorts(9090)
          .waitingFor(Wait.forHttp("/").forPort(9090).forStatusCode(200));

  static DynamoDbClient dynamo;
  static S3Client s3;
  static IndexHandler indexer;
  static LookupHandler lookup;

  @BeforeAll
  static void clientsTableAndBucket() {
    StaticCredentialsProvider fake =
        StaticCredentialsProvider.create(AwsBasicCredentials.create("it", "it"));
    dynamo =
        DynamoDbClient.builder()
            .region(Region.AP_SOUTH_1)
            .credentialsProvider(fake)
            .httpClient(UrlConnectionHttpClient.create())
            .endpointOverride(URI.create("http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000)))
            .build();
    s3 =
        S3Client.builder()
            .region(Region.AP_SOUTH_1)
            .credentialsProvider(fake)
            .httpClient(UrlConnectionHttpClient.create())
            .endpointOverride(URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(9090)))
            .forcePathStyle(true)
            .build();

    // The same key schema infra/cloud/public-view.tf declares. Written out rather than shared,
    // because Terraform cannot import a Java constant; PublicTable's constants keep the names
    // honest on this side.
    dynamo.createTable(
        t ->
            t.tableName(TABLE)
                .keySchema(
                    KeySchemaElement.builder().attributeName(PublicTable.PK).keyType(KeyType.HASH).build(),
                    KeySchemaElement.builder().attributeName(PublicTable.SK).keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                    AttributeDefinition.builder().attributeName(PublicTable.PK).attributeType(ScalarAttributeType.S).build(),
                    AttributeDefinition.builder().attributeName(PublicTable.SK).attributeType(ScalarAttributeType.S).build())
                .provisionedThroughput(ProvisionedThroughput.builder().readCapacityUnits(10L).writeCapacityUnits(10L).build()));
    s3.createBucket(b -> b.bucket(BUCKET));

    PublicTable table = new PublicTable(dynamo, TABLE, Clock.systemUTC());
    indexer = new IndexHandler(s3, table);
    lookup = new LookupHandler(table, Plans.packaged(), Clock.systemUTC());
  }

  @Test
  void archiveFilesInPublicPageOut() throws IOException {
    // Any test that could pass against the wrong destination must assert the destination.
    assertThat(dynamo.serviceClientConfiguration().endpointOverride()).get()
        .hasToString("http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000));

    // Hour 06: positions, out of order, for two shipments.
    String positions =
        put(
            Topics.POSITION,
            6,
            position(HYD, 30, 16.0, 78.1, 0.0),
            position(HYD, 10, 17.3, 78.3, 55.0),
            position(DEL, 12, 28.5, 77.2, 70.0));
    // The HYD load reached its origin, left it, and is now at the Kurnool clinic.
    String derived =
        put(
            Topics.DERIVED,
            6,
            arrived(HYD, "hyd-genome", 0),
            departed(HYD, "hyd-genome", 0, 5),
            estimate(HYD, "knl-clinic", 6),
            arrived(HYD, "knl-clinic", 25));
    // An incident whose clear is in an earlier-indexed file than its raise, and one still open.
    String clears = put(Topics.EXCEPTIONS, 7, cleared(HYD, "inc-cleared", 8, 20));
    String raises =
        put(
            Topics.EXCEPTIONS,
            6,
            raised(HYD, "inc-cleared", Severity.WARNING, 8),
            raised(DEL, "inc-open", Severity.CRITICAL, 11));
    // A status file never wakes the indexer in AWS; if one reaches it anyway, it is skipped.
    String status = "archive/status.events.v1/dt=2026-09-11/hour=06/p0-o0-1.ndjson.gz";

    JsonNode indexed = notify(positions, derived, clears, raises, status);
    JsonNode files = indexed.path("files");
    assertThat(files.get(0).path("lines").asInt()).isEqualTo(3);
    assertThat(files.get(0).path("written").asInt()).isEqualTo(2);
    assertThat(files.get(1).path("skipped").asInt()).isEqualTo(1);
    assertThat(files.get(4).path("lines").asInt()).isZero();

    // Re-indexing changes nothing: every position and stop row already holds something as new.
    JsonNode again = notify(positions, derived);
    assertThat(again.path("files").get(0).path("written").asInt()).isZero();
    assertThat(again.path("files").get(0).path("older").asInt()).isEqualTo(2);

    // The fleet: two markers, the troubled one first.
    JsonNode fleet = get("/api/shipments");
    assertThat(fleet.size()).isEqualTo(2);
    assertThat(fleet.get(0).path("shipmentId").asString()).isEqualTo(DEL);
    assertThat(fleet.get(0).path("worstSeverity").asString()).isEqualTo("CRITICAL");
    JsonNode hyd = fleet.get(1);
    assertThat(hyd.path("movement").asString()).isEqualTo("AT_STOP");
    assertThat(hyd.path("latitude").asDouble()).isEqualTo(16.0);
    assertThat(hyd.path("stopsCompleted").asInt()).isEqualTo(2);
    assertThat(hyd.path("stopsTotal").asInt()).isEqualTo(3);
    assertThat(hyd.has("openExceptions")).isFalse();
    assertThat(hyd.has("nextStop")).isFalse();

    // The lookup: one shipment in full.
    JsonNode detail = get("/api/shipments/" + HYD);
    assertThat(detail.path("stops").values()).extracting(s -> s.path("status").asString())
        .containsExactly("DEPARTED", "AT", "PENDING");
    assertThat(detail.path("stops").get(1).path("name").asString()).isEqualTo("Kurnool clinic dock");
    JsonNode incident = detail.path("exceptions").get(0);
    assertThat(incident.path("state").asString()).isEqualTo("CLEARED");
    assertThat(incident.path("severity").asString()).isEqualTo("WARNING");

    assertThat(get("/api/exceptions").size()).isEqualTo(1);
    assertThat(get("/api/exceptions?open=false").size()).isEqualTo(2);

    JsonNode meta = get("/api/meta");
    assertThat(meta.path("source").asString()).isEqualTo("archive");
    assertThat(meta.path("trackedShipments").asInt()).isEqualTo(2);
    assertThat(meta.path("archivedThrough").asString()).isNotEmpty();
  }

  @Test
  void theResponseShapeIsTheFunctionUrlsAndOnlyGetIsAnswered() throws IOException {
    JsonNode missing = invoke("GET", "/api/shipments/SHP-NOPE-9999", null);
    assertThat(missing.path("statusCode").asInt()).isEqualTo(404);
    // Cacheable, so a stream of made-up ids becomes CloudFront hits rather than invocations.
    assertThat(missing.path("headers").path("cache-control").asString()).isEqualTo("public, max-age=60");

    assertThat(invoke("GET", "/api/shipments/..%2F..%2Fetc", null).path("statusCode").asInt()).isEqualTo(404);
    assertThat(invoke("POST", "/api/shipments", null).path("statusCode").asInt()).isEqualTo(405);
    assertThat(invoke("GET", "/api/stream", null).path("statusCode").asInt()).isEqualTo(404);

    JsonNode ok = invoke("GET", "/api/meta", null);
    assertThat(ok.path("statusCode").asInt()).isEqualTo(200);
    assertThat(ok.path("headers").path("content-type").asString()).isEqualTo("application/json");
    assertThat(ok.path("isBase64Encoded").asBoolean()).isFalse();
  }

  // -----------------------------------------------------------------------------------------

  private static final AtomicLong WRITTEN = new AtomicLong(1_757_570_000_000L);

  /** Writes one archive file exactly as the archiver lays it out, and returns its key. */
  private static String put(String topic, int hour, Event... events) throws IOException {
    Instant received = Instant.parse("2026-09-11T%02d:30:00Z".formatted(hour));
    String key =
        "archive/%s/dt=2026-09-11/hour=%02d/p0-o0-%d.ndjson.gz"
            .formatted(topic, hour, WRITTEN.incrementAndGet());
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
      long offset = 0;
      for (Event event : events) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("topic", topic);
        line.put("partition", 0);
        line.put("offset", offset++);
        line.put("timestamp", received.toString());
        line.put("key", event.shipmentId());
        line.put("value", EventJson.mapper().writeValueAsString(event));
        gz.write((EventJson.mapper().writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8));
      }
    }
    s3.putObject(p -> p.bucket(BUCKET).key(key), RequestBody.fromBytes(bytes.toByteArray()));
    return key;
  }

  /** An S3 notification for these keys, URL-encoded the way S3 sends them. */
  private static JsonNode notify(String... keys) throws IOException {
    List<Map<String, Object>> records =
        java.util.Arrays.stream(keys)
            .map(
                key ->
                    Map.<String, Object>of(
                        "eventName", "ObjectCreated:Put",
                        "s3",
                        Map.of(
                            "bucket", Map.of("name", BUCKET),
                            "object", Map.of("key", URLEncoder.encode(key, StandardCharsets.UTF_8)))))
            .toList();
    byte[] request = EventJson.mapper().writeValueAsBytes(Map.of("Records", records));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    indexer.handleRequest(new ByteArrayInputStream(request), out, null);
    return EventJson.mapper().readTree(out.toByteArray());
  }

  /** A function URL request (payload format 2.0), and the response as the runtime returns it. */
  private static JsonNode invoke(String method, String pathAndQuery, String unused) throws IOException {
    String path = pathAndQuery.contains("?") ? pathAndQuery.substring(0, pathAndQuery.indexOf('?')) : pathAndQuery;
    Map<String, Object> query = new LinkedHashMap<>();
    if (pathAndQuery.contains("?")) {
      for (String pair : pathAndQuery.substring(pathAndQuery.indexOf('?') + 1).split("&")) {
        String[] kv = pair.split("=", 2);
        query.put(kv[0], kv.length > 1 ? kv[1] : "");
      }
    }
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("version", "2.0");
    request.put("rawPath", path);
    request.put("rawQueryString", pathAndQuery.contains("?") ? pathAndQuery.substring(pathAndQuery.indexOf('?') + 1) : "");
    if (!query.isEmpty()) {
      request.put("queryStringParameters", query);
    }
    request.put("requestContext", Map.of("http", Map.of("method", method, "path", path)));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    lookup.handleRequest(new ByteArrayInputStream(EventJson.mapper().writeValueAsBytes(request)), out, null);
    return EventJson.mapper().readTree(out.toByteArray());
  }

  private static JsonNode get(String pathAndQuery) throws IOException {
    JsonNode response = invoke("GET", pathAndQuery, null);
    assertThat(response.path("statusCode").asInt()).as(pathAndQuery).isEqualTo(200);
    return EventJson.mapper().readTree(response.path("body").asString());
  }
}
