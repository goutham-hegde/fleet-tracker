package com.fleettracking.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleettracking.events.EventJson;
import com.fleettracking.shipment.manifest.FreightMode;
import com.fleettracking.shipment.manifest.Manifest;
import com.fleettracking.shipment.manifest.ManifestStore;
import com.fleettracking.shipment.schema.ManifestSchema;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
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
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The whole service against a real MongoDB: HTTP in, one collection out.
 *
 * <p>The unit tests prove the validator applies the schemas and the service refuses to store what
 * fails them. They cannot prove that four documents with no fields in common actually coexist in
 * one collection, that a body field is queryable when nothing declared it, that the server-side
 * envelope validator is really attached, or that a manifest survives a round trip through BSON with
 * its nested arrays intact. Those are all storage questions, and storage questions are only
 * answered by storage.
 *
 * <p>This class covers both of M4's S12 exit criteria end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ShipmentServiceIT {

  /** Standalone, matching the cluster: no replica set, so no transactions and no change streams. */
  @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

  @Autowired private MongoOperations mongo;
  @Autowired private MongoClient mongoClient;
  @Autowired private ManifestStore store;

  @LocalServerPort private int port;

  /**
   * A plain JDK client rather than a Spring test client. What is being tested is an endpoint a
   * customer's order system calls with no Spring on their side, and {@code TestRestTemplate} no
   * longer exists in Spring Boot 4 in any case.
   */
  private final HttpClient client = HttpClient.newHttpClient();

  @DynamicPropertySource
  static void mongoAddress(DynamicPropertyRegistry registry) {
    // spring.mongodb, not spring.data.mongodb. Boot 4 deprecated the Spring Data namespace for
    // connection settings at level "error", so the old names bind to nothing -- and this machine
    // has an unrelated mongod on the default port that would happily accept every write.
    registry.add("spring.mongodb.uri", MONGO::getConnectionString);
    registry.add("spring.mongodb.database", () -> "fleet");
  }

  @BeforeEach
  void seedSchemas() {
    mongo.remove(new Query(), Manifest.COLLECTION);
    mongo.remove(new Query(), ManifestSchema.COLLECTION);

    // The same four contracts seed-manifest-schemas.sh loads, read from the same committed files.
    Manifests.allSchemas().forEach(mongo::save);
  }

  /**
   * Any test that could pass against the wrong database must assert the destination.
   *
   * <p>Not paranoia: in S8 an integration test passed for a whole session while reading and writing
   * an unrelated MongoDB on this machine's default port, because a test that writes a database and
   * reads it back is satisfied by any database at all.
   */
  @Test
  void isConnectedToItsOwnContainerAndNotToTheMachinesLocalMongo() {
    int connectedPort =
        mongoClient.getClusterDescription().getServerDescriptions().getFirst().getAddress().getPort();

    assertThat(connectedPort).isEqualTo(MONGO.getFirstMappedPort());
    assertThat(connectedPort).isNotEqualTo(27017);
  }

  // --- exit criterion: all four shapes persist and query from one collection ---------------------

  @Test
  void allFourManifestShapesPersistInOneCollection() {
    submit("SHP-HYD-0002", Manifests.PHARMA_CUSTOMER, FreightMode.PHARMA_COLD_CHAIN, Manifests.pharmaBody());
    submit("SHP-DEL-0001", Manifests.RETAIL_CUSTOMER, FreightMode.RETAIL_REPLENISHMENT, Manifests.retailBody());
    submit("SHP-BLR-0003", Manifests.LTL_CUSTOMER, FreightMode.LTL, Manifests.ltlBody());
    submit("SHP-BOM-0004", Manifests.PARCEL_CUSTOMER, FreightMode.PARCEL, Manifests.parcelBody());

    assertThat(store.count()).isEqualTo(4);

    // One collection, and the documents in it genuinely share no body fields.
    List<String> collections =
        mongo.execute(db -> db.listCollectionNames().into(new java.util.ArrayList<>()));
    assertThat(collections).contains(Manifest.COLLECTION);
    assertThat(collections)
        .noneSatisfy(name -> assertThat(name).isEqualTo("manifests.pharma"));
  }

  @Test
  void eachShapeComesBackWithItsOwnFieldsIntact() {
    submit("SHP-HYD-0002", Manifests.PHARMA_CUSTOMER, FreightMode.PHARMA_COLD_CHAIN, Manifests.pharmaBody());
    submit("SHP-BOM-0004", Manifests.PARCEL_CUSTOMER, FreightMode.PARCEL, Manifests.parcelBody());

    Manifest pharma = store.findByShipment("SHP-HYD-0002").orElseThrow();
    Manifest parcel = store.findByShipment("SHP-BOM-0004").orElseThrow();

    // Nested objects and arrays survive the trip through BSON.
    assertThat(pharma.body()).containsKey("custody");
    assertThat((List<?>) pharma.body().get("custody")).hasSize(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> temperature = (Map<String, Object>) pharma.body().get("temperature");
    assertThat(temperature).containsEntry("maxC", 8.0);

    // And the two shapes share nothing: neither has a single one of the other's fields.
    assertThat(pharma.body().keySet()).doesNotContainAnyElementsOf(parcel.body().keySet());
  }

  @Test
  void queriesByCustomerAcrossTheWholeCollection() {
    submit("SHP-HYD-0002", Manifests.PHARMA_CUSTOMER, FreightMode.PHARMA_COLD_CHAIN, Manifests.pharmaBody());
    submit("SHP-BLR-0003", Manifests.LTL_CUSTOMER, FreightMode.LTL, Manifests.ltlBody());

    assertThat(store.findByCustomer(Manifests.PHARMA_CUSTOMER))
        .extracting(Manifest::shipmentId)
        .containsExactly("SHP-HYD-0002");
  }

  @Test
  void queriesByFreightModeAcrossTheWholeCollection() {
    submit("SHP-DEL-0001", Manifests.RETAIL_CUSTOMER, FreightMode.RETAIL_REPLENISHMENT, Manifests.retailBody());
    submit("SHP-BOM-0004", Manifests.PARCEL_CUSTOMER, FreightMode.PARCEL, Manifests.parcelBody());

    assertThat(store.findByMode(FreightMode.PARCEL))
        .extracting(Manifest::shipmentId)
        .containsExactly("SHP-BOM-0004");
    assertThat(store.findByMode(FreightMode.LTL)).isEmpty();
  }

  /**
   * The query that answers "why MongoDB" in one line: a field this service was never told about,
   * inside a document whose shape it does not know, queried like a declared column.
   */
  @Test
  void queriesByAFieldInsideTheUntypedBody() {
    submit("SHP-BLR-0003", Manifests.LTL_CUSTOMER, FreightMode.LTL, Manifests.ltlBody());
    submit("SHP-BOM-0004", Manifests.PARCEL_CUSTOMER, FreightMode.PARCEL, Manifests.parcelBody());

    assertThat(store.findByBodyField("freightClass", "85"))
        .extracting(Manifest::shipmentId)
        .containsExactly("SHP-BLR-0003");

    // Including a nested path, which is where a relational attribute table would need a join.
    assertThat(store.findByBodyField("recipient.pincode", "600028"))
        .extracting(Manifest::shipmentId)
        .containsExactly("SHP-BOM-0004");
  }

  // --- exit criterion: a manifest violating its schema is rejected with a useful error -----------

  @Test
  void rejectsAManifestThatBreaksItsCustomersSchemaAndSaysWhy() {
    Map<String, Object> broken = Manifests.pharmaBody();
    @SuppressWarnings("unchecked")
    Map<String, Object> temperature = (Map<String, Object>) broken.get("temperature");
    temperature.put("maxC", 400.0);

    HttpResponse<String> response =
        post("SHP-HYD-0002", Manifests.PHARMA_CUSTOMER, FreightMode.PHARMA_COLD_CHAIN, broken);

    // 422, not 400: the request was understood perfectly and its contents were unacceptable.
    assertThat(response.statusCode()).isEqualTo(422);

    // "A useful error" means the field and the constraint, not a bare status code.
    assertThat(response.body()).contains("maxC");
    assertThat(response.body()).contains("maximum");
    assertThat(response.body()).contains(Manifests.VERSION);

    // And nothing was stored.
    assertThat(store.count()).isZero();
  }

  @Test
  void answersServiceUnavailableWhenNoSchemaIsOnFile() {
    // Following the gateway's rule: 503 is for the cases where the identical request can succeed
    // later. Nothing is wrong with this manifest -- the platform has not been given the contract.
    HttpResponse<String> response =
        post("SHP-BOM-0008", "NEVER-ONBOARDED", FreightMode.PARCEL, Manifests.parcelBody());

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.body()).contains("NEVER-ONBOARDED");
    assertThat(store.count()).isZero();
  }

  @Test
  void answersBadRequestForMalformedJson() {
    // Unlike the gateway, this endpoint has a caller who can fix and resend, so there is no
    // dead-letter path and a 400 is the honest answer.
    HttpResponse<String> response = postRaw("{\"shipmentId\": \"SHP-1\", ");

    assertThat(response.statusCode()).isEqualTo(400);
  }

  @Test
  void acceptsAValidManifestAndReturnsItAtItsOwnUrl() {
    HttpResponse<String> created =
        post("SHP-BLR-0003", Manifests.LTL_CUSTOMER, FreightMode.LTL, Manifests.ltlBody());
    assertThat(created.statusCode()).isEqualTo(201);

    HttpResponse<String> fetched = get("/manifests/SHP-BLR-0003");
    assertThat(fetched.statusCode()).isEqualTo(200);
    assertThat(fetched.body()).contains("381004827155");
    assertThat(fetched.body()).contains(Manifests.VERSION);
  }

  @Test
  void answersNotFoundForAShipmentWithNoManifest() {
    assertThat(get("/manifests/SHP-NOTHING").statusCode()).isEqualTo(404);
  }

  // --- the server-side envelope validator --------------------------------------------------------

  /**
   * The second layer, proven to be real.
   *
   * <p>This write bypasses the service entirely, which is exactly the route the validator exists to
   * cover: a migration script, a future service, or somebody at a {@code mongosh} prompt. If the
   * validator were not attached, this document would be stored and every later reader would trust
   * it.
   */
  @Test
  void mongoRefusesADocumentWithAnUnknownFreightModeEvenWhenTheServiceIsBypassed() {
    Document rogue =
        new Document("_id", "SHP-ROGUE-0001")
            .append("customerId", Manifests.PARCEL_CUSTOMER)
            .append("mode", "TELEPORTATION")
            .append("schemaVersion", Manifests.VERSION)
            .append("createdAt", java.util.Date.from(Instant.now()))
            .append("body", new Document("trackingNumber", "QS0000000001IN"));

    assertThatThrownBy(() -> mongo.getCollection(Manifest.COLLECTION).insertOne(rogue))
        .isInstanceOf(MongoWriteException.class)
        .hasMessageContaining("validation");

    assertThat(store.count()).isZero();
  }

  @Test
  void mongoRefusesADocumentMissingAnEnvelopeField() {
    Document rogue =
        new Document("_id", "SHP-ROGUE-0002")
            .append("customerId", Manifests.PARCEL_CUSTOMER)
            .append("mode", FreightMode.PARCEL.name())
            // no schemaVersion, no createdAt
            .append("body", new Document("trackingNumber", "QS0000000001IN"));

    assertThatThrownBy(() -> mongo.getCollection(Manifest.COLLECTION).insertOne(rogue))
        .isInstanceOf(MongoWriteException.class);
  }

  /**
   * The validator constrains the envelope and deliberately says nothing about the body.
   *
   * <p>If this failed, the server would be enforcing a second, coarser copy of a contract that
   * already exists as data — and the two would drift the first time a customer changed anything.
   */
  @Test
  void mongoAcceptsAnyBodyShapeSoLongAsTheEnvelopeIsRight() {
    Document unusual =
        new Document("_id", "SHP-FUTURE-0001")
            .append("customerId", "SOME-NEW-CUSTOMER")
            .append("mode", FreightMode.LTL.name())
            .append("schemaVersion", "2027-01-01")
            .append("createdAt", java.util.Date.from(Instant.now()))
            .append("body", new Document("aFieldNobodyHasEverSeen", List.of(1, 2, 3)));

    mongo.getCollection(Manifest.COLLECTION).insertOne(unusual);

    assertThat(store.count()).isEqualTo(1);
  }

  // --- helpers -----------------------------------------------------------------------------------

  private void submit(String shipmentId, String customerId, FreightMode mode, Map<String, Object> body) {
    HttpResponse<String> response = post(shipmentId, customerId, mode, body);
    assertThat(response.statusCode())
        .as("submitting %s for %s: %s", shipmentId, customerId, response.body())
        .isEqualTo(201);
  }

  private HttpResponse<String> post(
      String shipmentId, String customerId, FreightMode mode, Map<String, Object> body) {
    return postRaw(
        EventJson.mapper()
            .writeValueAsString(
                Manifests.map(
                    "shipmentId", shipmentId,
                    "customerId", customerId,
                    "mode", mode.name(),
                    "body", body)));
  }

  private HttpResponse<String> postRaw(String json) {
    try {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/manifests"))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(json))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private HttpResponse<String> get(String path) {
    try {
      return client.send(
          HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
