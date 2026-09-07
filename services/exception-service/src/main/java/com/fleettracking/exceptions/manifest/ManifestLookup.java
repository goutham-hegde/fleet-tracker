package com.fleettracking.exceptions.manifest;

import java.util.Optional;

/**
 * Finds what a customer committed to for a shipment.
 *
 * <h2>Why this is an interface with one implementation</h2>
 *
 * <p>The implementation reads the {@code manifests} collection directly, and that collection
 * belongs to the shipment service. Reading another service's store is a real coupling and it is
 * being taken on with its eyes open rather than by accident, so it is worth writing down what was
 * weighed in S13.
 *
 * <ul>
 *   <li><b>An HTTP call to the shipment service.</b> Respects the boundary properly. It also puts a
 *       synchronous dependency on the busiest path in the platform: every position event would wait
 *       on a second service, and that service being down would stall this one — turning one
 *       outage into two. A cache would fix the cost and reintroduce staleness.
 *   <li><b>The shipment service publishes manifests to a topic, and this service keeps its own
 *       copy.</b> The right long-term answer, and properly decoupled. It is also a new topic, a
 *       producer, a consumer and a store — a session's work before the first rule exists.
 *   <li><b>Read the collection, read-only, behind this interface.</b> Chosen. It is what every
 *       other consumer in this platform already does with shared data, the read is by primary key,
 *       and nothing here writes: the shipment service remains the only thing that can change a
 *       manifest.
 * </ul>
 *
 * <p>The seam is what makes that reversible. Moving to a topic-backed copy later is a different
 * implementation of this interface and a changed bean — the rules never learn that anything moved.
 *
 * <h2>No cache, for the third time</h2>
 *
 * <p>The same reasoning as {@code MongoIdentityResolver} in the gateway and {@code ItineraryStore}
 * in the tracking processor: this is reference data somebody else maintains, and a cache buys one
 * indexed lookup while paying with a window in which a rule knowingly judges a shipment against
 * terms that have since been renegotiated. An exception raised against a superseded contract is
 * worse than an exception raised a millisecond later.
 */
public interface ManifestLookup {

  /**
   * The SLA terms for a shipment, or empty if nothing has filed a manifest for it.
   *
   * <p>Empty is ordinary. A load whose paperwork has not arrived yet is still a load being tracked,
   * and the rules that need no manifest — an unplanned stop, a route deviation, a silent device —
   * carry on working for it. Only the two that read a customer's commitment go quiet.
   */
  Optional<SlaTerms> forShipment(String shipmentId);

  /** How many manifests are on file. Logged at startup, so an unseeded database is visible. */
  long count();
}
