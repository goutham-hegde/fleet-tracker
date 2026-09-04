package com.fleettracking.shipment.manifest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The paperwork for one load: a typed envelope every manifest shares, and a body that belongs to
 * the customer who sent it.
 *
 * <h2>Why the body is not typed</h2>
 *
 * <p>This split is the whole argument for storing manifests in MongoDB, so it is worth stating
 * precisely rather than leaving it to be inferred.
 *
 * <p>The four freight modes share almost no fields. A pharma consignment carries a temperature
 * range, a licence number and a custody chain; a retail replenishment carries purchase orders,
 * pallet counts and a booked delivery window; a part-load carries a freight class and a piece
 * count; a parcel carries a service level and little else. Flattened into one relational table
 * that is roughly forty mostly-null columns, and the alternative — an attribute-value side table —
 * trades the nulls for a join per field and loses every type in the process.
 *
 * <p>Worse, the field set is not this platform's to fix. It belongs to the customer's order system,
 * and customers add fields. If {@code body} were a Java record, a customer adding one field would
 * mean a code change, a release, and a migration coordinated across every environment — for data
 * this service never interprets. Keeping it an open document means that customer's schema document
 * is updated and nothing is redeployed.
 *
 * <p>What stops it becoming a junk drawer is that the body is <em>validated against that customer's
 * JSON Schema on every write</em>. It is unconstrained by this codebase, not unconstrained.
 *
 * <h2>What is in the envelope, and why so little</h2>
 *
 * <p>Only fields this platform itself acts on. {@code shipmentId} joins a manifest to the position
 * and geofence state the tracking processor maintains; {@code customerId} selects the schema to
 * validate against; {@code mode} decides which SLA rules apply in S13. Everything else a human
 * would call "the manifest" is body. The test is simple: if this service reads a field, it belongs
 * in the envelope; if it only stores and returns it, it belongs in the body.
 *
 * @param shipmentId the load this paperwork describes, and the document's own id
 * @param customerId whose schema the body is held to
 * @param mode how the freight is carried, which selects the SLA rules that apply
 * @param schemaVersion the version of that customer's schema this body was accepted against
 * @param createdAt when this platform accepted it, not when the customer raised it
 * @param body the customer's own fields, validated on write and otherwise untouched
 */
@Document(collection = Manifest.COLLECTION)
public record Manifest(

    /*
     * The shipment id is the document id rather than a field beside a generated ObjectId. One
     * shipment has one manifest, so a separate key would allow two -- and the second would be
     * found by nothing, because every reader here looks a manifest up by shipment.
     */
    @Id @NotBlank String shipmentId,
    @NotBlank String customerId,
    @NotNull FreightMode mode,
    @NotBlank String schemaVersion,
    @NotNull Instant createdAt,
    @NotNull Map<String, Object> body) {

  /** One collection for all four shapes. Splitting it per mode would concede the argument. */
  public static final String COLLECTION = "manifests";

  /**
   * Copies the body and makes it unmodifiable.
   *
   * <p>The copy is not ceremony. This record is handed a map that a caller — a request binding, a
   * test, a Mongo read — still holds a reference to, and a manifest that changes after it was
   * validated is a manifest that was never really validated. Copying at the boundary makes the
   * accepted body and the stored body provably the same thing.
   *
   * <p>{@link LinkedHashMap} rather than a plain copy so that field order survives a round trip.
   * Nothing depends on it, but a manifest read back in a different order than it was sent reads as
   * corruption to a human comparing two documents.
   */
  public Manifest {
    body = body == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(body));
  }
}
