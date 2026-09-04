package com.fleettracking.shipment.schema;

import com.fleettracking.shipment.manifest.FreightMode;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One customer's contract for one freight mode, expressed as a JSON Schema.
 *
 * <p>This document is what makes an untyped manifest body safe, and it is deliberately <em>data</em>
 * rather than code. Onboarding a customer is an insert here. A customer adding a field is an update
 * here. Neither is a release of this service, and that is the entire claim M4 is built to
 * demonstrate — the alternative, a Java record per customer, turns every customer's paperwork
 * change into a coordinated deployment across every environment.
 *
 * <h2>Why the key is customer <em>and</em> mode</h2>
 *
 * <p>A customer is not a shape. A pharmaceutical distributor that also sends samples by parcel has
 * two genuinely different manifests, and holding one schema per customer would force the union of
 * both — which validates neither. Keying on the pair means each shape is constrained exactly, and a
 * customer who ships one way simply has one document.
 *
 * <h2>Why the version is recorded on the manifest</h2>
 *
 * <p>Schemas change, and a manifest accepted last month was accepted against last month's rules.
 * Storing {@code version} on the accepted {@link com.fleettracking.shipment.manifest.Manifest}
 * means a document can always be explained: a stored manifest that would fail today's schema is not
 * a mystery or a corruption, it is a manifest from before the change. Without it, tightening a
 * schema silently reclassifies history as invalid.
 *
 * @param id {@code customerId + "/" + mode}, derived so a re-seed updates rather than duplicates
 * @param customerId whose contract this is
 * @param mode the freight mode it constrains
 * @param version an opaque label recorded on every manifest accepted against it
 * @param schema the JSON Schema document itself, stored as-is
 * @param updatedAt when this version was written
 */
@Document(collection = ManifestSchema.COLLECTION)
public record ManifestSchema(
    @Id String id,
    String customerId,
    FreightMode mode,
    String version,
    Map<String, Object> schema,
    Instant updatedAt) {

  /** Reference data, kept beside the manifests it constrains but in its own collection. */
  public static final String COLLECTION = "manifest.schemas";

  /**
   * The document id for a customer and mode.
   *
   * <p>Derived rather than generated, which is what makes {@code seed-manifest-schemas.sh}
   * idempotent: running it twice updates two documents instead of creating four. The same reasoning
   * as the identity seed script in the gateway.
   */
  public static String idFor(String customerId, FreightMode mode) {
    return customerId + "/" + mode.name();
  }
}
