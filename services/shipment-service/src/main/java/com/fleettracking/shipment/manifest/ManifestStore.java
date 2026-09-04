package com.fleettracking.shipment.manifest;

import java.util.List;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Persistence for manifests: one collection, four shapes.
 *
 * <p>Keeping them together is the point rather than a convenience. Four collections — one per mode
 * — would make "show me every shipment for this customer" a four-way fan-in that has to be widened
 * every time a mode is added, and would quietly reintroduce the schema-per-shape coupling this
 * milestone argues against. One collection with a validated envelope and an open body gives a
 * single place to query and a single place to index.
 *
 * <h2>The envelope validator, and what it is <em>not</em> for</h2>
 *
 * <p>MongoDB enforces a {@code $jsonSchema} on this collection covering the envelope only. It
 * cannot do the per-customer part of the job: a collection carries exactly one validator, and there
 * is one per customer and mode. So the division is by capability, not by preference —
 * {@link com.fleettracking.shipment.schema.ManifestValidator} holds the body to the customer's
 * contract and can explain what was wrong, and the server independently guarantees that nothing,
 * by any route, writes a document missing a shipment id or carrying an unknown mode.
 *
 * <p>That second layer earns its place because this service is not the only thing that can reach
 * the database. A migration script, a future service, or somebody at a {@code mongosh} prompt all
 * bypass the application entirely. The validator is the constraint that survives them.
 */
public class ManifestStore {

  private static final Logger log = LoggerFactory.getLogger(ManifestStore.class);

  private final MongoOperations mongo;

  public ManifestStore(MongoOperations mongo) {
    this.mongo = mongo;
  }

  /**
   * Creates the collection with its envelope validator, or brings an existing one up to date.
   *
   * <p>Both paths are needed and they use different commands. A collection that does not exist is
   * created with the validator attached; one that already exists cannot be re-created, and its
   * validator is replaced with {@code collMod}. Skipping the second path would mean a schema change
   * silently applied only to environments provisioned after it — the validator would be present in
   * a fresh cluster and absent in the one that has been running for months, which is the worst
   * possible distribution of a safety check.
   *
   * <p>Idempotent, so it is safe on every start.
   */
  public void ensureCollection() {
    Document validator = new Document("$jsonSchema", envelopeSchema());

    if (mongo.collectionExists(Manifest.COLLECTION)) {
      mongo.executeCommand(
          new Document("collMod", Manifest.COLLECTION)
              .append("validator", validator)
              .append("validationLevel", "strict")
              .append("validationAction", "error"));
      log.info("Manifest collection validator brought up to date");
    } else {
      mongo.executeCommand(
          new Document("create", Manifest.COLLECTION)
              .append("validator", validator)
              .append("validationLevel", "strict")
              .append("validationAction", "error"));
      log.info("Created manifest collection with envelope validator");
    }

    // Customer and mode are what the dashboard and S13's rules select by; neither is the document
    // id, so without these every such query is a collection scan.
    mongo
        .indexOps(Manifest.COLLECTION)
        .createIndex(new Index().on("customerId", Sort.Direction.ASC).named("customer"));
    mongo
        .indexOps(Manifest.COLLECTION)
        .createIndex(new Index().on("mode", Sort.Direction.ASC).named("mode"));
  }

  /**
   * The envelope every manifest shares, as a MongoDB {@code $jsonSchema}.
   *
   * <p>Note what is <em>absent</em>: any constraint on the contents of {@code body} beyond it being
   * an object. That is not an oversight — it is the design. The body is the customer's, and
   * constraining it here would be a second, coarser statement of a contract that already exists as
   * data, drifting away from it the moment a customer changes anything.
   */
  private static Document envelopeSchema() {
    List<String> modes = java.util.Arrays.stream(FreightMode.values()).map(Enum::name).toList();

    return new Document()
        .append("bsonType", "object")
        .append("required", List.of("_id", "customerId", "mode", "schemaVersion", "createdAt", "body"))
        .append(
            "properties",
            new Document()
                .append("_id", new Document("bsonType", "string"))
                .append("customerId", new Document("bsonType", "string"))
                // An enum rather than a free string. A mode this platform does not recognise is a
                // manifest nothing can route or apply rules to, so it must not reach the database.
                .append("mode", new Document("enum", modes))
                .append("schemaVersion", new Document("bsonType", "string"))
                .append("createdAt", new Document("bsonType", "date"))
                .append("body", new Document("bsonType", "object")));
  }

  /** Stores a manifest, replacing any existing one for the same shipment. */
  public void save(Manifest manifest) {
    mongo.save(manifest);
  }

  /** The manifest for one shipment, which is the read the dashboard makes. */
  public java.util.Optional<Manifest> findByShipment(String shipmentId) {
    return java.util.Optional.ofNullable(mongo.findById(shipmentId, Manifest.class));
  }

  /** Every manifest belonging to one customer, newest first, across all of its freight modes. */
  public List<Manifest> findByCustomer(String customerId) {
    return mongo.find(
        Query.query(Criteria.where("customerId").is(customerId))
            .with(Sort.by(Sort.Direction.DESC, "createdAt")),
        Manifest.class);
  }

  /** Every manifest of one freight mode, which is how S13 will select the rules that apply. */
  public List<Manifest> findByMode(FreightMode mode) {
    return mongo.find(Query.query(Criteria.where("mode").is(mode.name())), Manifest.class);
  }

  /**
   * Finds manifests by a field inside the untyped body.
   *
   * <p>This is the query that answers the "why MongoDB" question in one line. {@code field} is a
   * dotted path into a document whose shape this service has never been told — {@code
   * temperature.maxC}, {@code deliveryWindow.opensAt}, {@code freightClass} — and it is queryable
   * and indexable exactly like a declared column would be. An attribute-value table could store the
   * same data and would need a join per field to ask this; a wide sparse table could ask it and
   * would need a migration to hold it.
   */
  public List<Manifest> findByBodyField(String field, Object value) {
    return mongo.find(Query.query(Criteria.where("body." + field).is(value)), Manifest.class);
  }

  /** How many manifests are stored, used by the tests and by the startup log. */
  public long count() {
    return mongo.count(new Query(), Manifest.class);
  }
}
