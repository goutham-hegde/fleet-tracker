package com.fleettracking.shipment.schema;

import com.fleettracking.shipment.manifest.FreightMode;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Reads the customer contracts that {@link ManifestValidator} enforces.
 *
 * <h2>This does not cache, and that is a decision rather than an omission</h2>
 *
 * <p>The same reasoning as {@code MongoIdentityResolver} and {@code ItineraryStore}: this is
 * reference data somebody else maintains. A cache buys one indexed lookup on the same cluster and
 * pays for it with a window in which this service validates against a schema the customer has
 * already replaced — accepting a manifest their current contract forbids, which is precisely the
 * failure validation exists to prevent.
 *
 * <p>The throughput argument that might justify a cache does not apply here either. A manifest is
 * written once per shipment, not thirty times a minute per truck like a position fix. The busy path
 * in this platform is the tracking processor's, and this is not it.
 *
 * <p>Contrast {@code EtaStateStore} in the tracking processor, which <em>does</em> cache: that is
 * the consumer's own working state, written by the one process that owns the shipment's partition,
 * not somebody else's plan.
 */
public class SchemaStore {

  private final MongoOperations mongo;

  public SchemaStore(MongoOperations mongo) {
    this.mongo = mongo;
  }

  /**
   * Creates the index the lookup below relies on.
   *
   * <p>Explicit, for the same reason topics are created by a Job rather than by auto-creation: an
   * index that appears only because somebody once ran the right query is not a property of the
   * deployment. Without it this is a collection scan on every manifest write — invisible at four
   * customers and not at four thousand.
   *
   * <p>Idempotent, so it is safe on every start.
   */
  public void ensureIndexes() {
    mongo
        .indexOps(ManifestSchema.COLLECTION)
        .createIndex(new Index().on("customerId", org.springframework.data.domain.Sort.Direction.ASC)
            .on("mode", org.springframework.data.domain.Sort.Direction.ASC)
            .named("customer_mode"));
  }

  /**
   * The contract on file for this customer and mode, if there is one.
   *
   * <p>An empty result is not an error here — it is reported as {@link
   * ValidationResult.NoSchema} so that a customer onboarded without their schema loaded is
   * distinguishable from a customer sending bad data.
   */
  public Optional<ManifestSchema> find(String customerId, FreightMode mode) {
    Query query =
        Query.query(
            Criteria.where("customerId").is(customerId).and("mode").is(mode.name()));
    return Optional.ofNullable(mongo.findOne(query, ManifestSchema.class));
  }
}
