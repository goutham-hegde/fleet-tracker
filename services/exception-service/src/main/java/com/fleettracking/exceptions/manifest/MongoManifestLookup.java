package com.fleettracking.exceptions.manifest;

import java.util.Map;
import java.util.Optional;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Reads SLA terms out of the shipment service's manifest collection.
 *
 * <h2>A projection, not the other service's model</h2>
 *
 * <p>The obvious shortcut would be to depend on the shipment service and reuse its {@code Manifest}
 * record. That would be worse than the coupling it appears to avoid: this service would then be
 * compiled against every field of another service's write model, and a change there — a field
 * promoted into the envelope, a validation tightened — would rebuild and redeploy this one for a
 * reason that has nothing to do with exceptions.
 *
 * <p>What is declared here instead is the smallest read model that answers the question: an id, two
 * envelope fields for the log line, and the body. It is a statement of exactly how much of another
 * service's document this one depends on, which is the part that has to stay stable.
 */
public class MongoManifestLookup implements ManifestLookup {

  /**
   * The shipment service's collection, named here as a string rather than imported.
   *
   * <p>Deliberately not shared through a constant in a library. A shared constant would say the two
   * services agree on a contract; they do not. This is one service reading another's store, and
   * the name being written out in full is the honest form of that — it is visible to anyone reading
   * this file, and it is what a search for the collection name will find.
   */
  static final String COLLECTION = "manifests";

  private final MongoOperations mongo;

  public MongoManifestLookup(MongoOperations mongo) {
    this.mongo = mongo;
  }

  @Override
  public Optional<SlaTerms> forShipment(String shipmentId) {
    if (shipmentId == null || shipmentId.isBlank()) {
      return Optional.empty();
    }
    ManifestView view = mongo.findById(shipmentId, ManifestView.class);
    if (view == null) {
      return Optional.empty();
    }
    return Optional.of(
        SlaTerms.from(view.shipmentId(), view.customerId(), view.mode(), view.body()));
  }

  @Override
  public long count() {
    return mongo.getCollection(COLLECTION).countDocuments();
  }

  /**
   * As much of a manifest as an SLA rule has any business seeing.
   *
   * <p>The mode is a {@code String} rather than the shipment service's {@code FreightMode} enum,
   * and that is the point of a projection: a fifth mode added there must not fail deserialization
   * here. Nothing in this service branches on the value — it appears in a log line so that an
   * operator reading about a temperature excursion can see it was a cold-chain load.
   */
  @Document(collection = COLLECTION)
  record ManifestView(
      @Id String shipmentId, String customerId, String mode, Map<String, Object> body) {}
}
