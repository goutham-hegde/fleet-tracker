package com.fleettracking.gateway.identity;

import java.time.Instant;
import java.util.Optional;

/**
 * Completes a partial identity from reference data, as it stood at a stated instant.
 *
 * <p>Three lookups rather than one, because the question each feed can ask is different. Asking by
 * shipment is nearly free — the feed already named the thing the platform keys on. Asking by
 * vehicle means "what was this truck pulling", and asking by device is the same question with an
 * extra hop in front of it.
 *
 * <h2>Every lookup takes an instant</h2>
 *
 * <p>S6 shipped these three methods without one, backed by a fixed list in configuration where the
 * question could only ever mean "right now". S8 made reference data temporal, and a time-aware
 * question cannot be asked through a time-free interface: the resolver would have to substitute its
 * own idea of the current moment, which is wrong for every feed that does not arrive instantly.
 *
 * <p>The instant to pass is <em>the one the source stated</em> — the GPS fix time, the app's epoch
 * millis, the EDI segment's own date and time — never the moment the gateway read the message. An
 * EDI 214 batch is delayed by a filing delay and a batch window both, so a status filed at 14:00
 * can arrive at 18:00; resolving it against arrival time would attribute the end of one load to the
 * next one the tractor picked up. Using the stated instant also makes a replayed message resolve to
 * exactly what it resolved to the first time, which is the same property derived event ids give the
 * rest of the pipeline.
 *
 * <p>Every lookup returns {@link Optional} rather than throwing or inventing a value. An
 * unresolvable message is a real and expected event — a truck fitted with a unit before it was
 * entered in the fleet system, a probe swapped without the paperwork, an event from before the
 * platform knew the vehicle existed — and the honest response is to dead-letter it with a reason,
 * not to publish an event attributed to a shipment that does not exist. A wrongly attributed
 * position is worse than a missing one: it moves a real shipment to the wrong place on a real map.
 */
public interface IdentityResolver {

  /**
   * What load was this tractor pulling at {@code asOf}? Asked by the telematics feed, which knows
   * the vehicle and nothing else.
   */
  Optional<Identity> byVehicle(String vehicleId, Instant asOf);

  /**
   * What load and tractor did this hardware belong to at {@code asOf}? Asked by the reefer probe,
   * which knows only its own id and needs both hops: device to vehicle, vehicle to load. The
   * instant matters most here — a trailer and its probe are routinely dropped at a yard and picked
   * up by a different tractor, so the answer genuinely changes mid-day.
   */
  Optional<Identity> byDevice(String deviceId, Instant asOf);

  /**
   * Which tractor was carrying this load at {@code asOf}? Asked by the mobile app and by EDI, which
   * name the shipment but never the vehicle. Also the cheapest way to confirm a shipment id is one
   * the platform has actually heard of, rather than a typo that would otherwise create a phantom
   * partition key.
   */
  Optional<Identity> byShipment(String shipmentId, Instant asOf);
}
