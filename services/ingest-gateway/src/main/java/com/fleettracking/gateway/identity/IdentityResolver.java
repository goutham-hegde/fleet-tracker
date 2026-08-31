package com.fleettracking.gateway.identity;

import java.util.Optional;

/**
 * Completes a partial identity from reference data.
 *
 * <p>Three lookups rather than one, because the question each feed can ask is different and the
 * answer has different confidence. Asking by shipment is nearly free — the feed already named the
 * thing the platform keys on. Asking by vehicle means "what is this truck pulling <em>right
 * now</em>", which is a question with a time dimension: the same tractor pulls a different load
 * tomorrow. Asking by device is the same question with an extra hop in front of it.
 *
 * <p>Every lookup returns {@link Optional} rather than throwing or inventing a value. An
 * unresolvable message is a real and expected event — a truck fitted with a unit before it was
 * entered in the fleet system, a probe swapped without the paperwork — and the honest response is
 * to dead-letter it with a reason, not to publish an event attributed to a shipment that does not
 * exist. A wrongly attributed position is worse than a missing one: it moves a real shipment to the
 * wrong place on a real map.
 *
 * <p>S6 ships a fixed, configuration-backed implementation. S8 replaces it with one that reads
 * dispatch reference data from MongoDB and caches it. Nothing but the bean definition changes,
 * because no normalizer knows which implementation it is talking to.
 */
public interface IdentityResolver {

  /** What load is this tractor pulling? Asked by the telematics feed, which knows nothing else. */
  Optional<Identity> byVehicle(String vehicleId);

  /**
   * What load and tractor does this hardware belong to? Asked by the reefer probe, which knows only
   * its own id and needs both hops: device to vehicle, vehicle to load.
   */
  Optional<Identity> byDevice(String deviceId);

  /**
   * Which tractor is carrying this load? Asked by the mobile app and by EDI, which name the
   * shipment but never the vehicle. Also the cheapest way to confirm a shipment id is one the
   * platform has actually heard of, rather than a typo that would otherwise create a phantom
   * partition key.
   */
  Optional<Identity> byShipment(String shipmentId);
}
