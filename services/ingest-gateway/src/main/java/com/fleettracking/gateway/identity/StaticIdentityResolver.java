package com.fleettracking.gateway.identity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves identity from a fixed list of assignments, indexed once at construction.
 *
 * <p>Three maps rather than one, and they are built eagerly. The alternative — scanning the list on
 * every lookup — is defensible at eight trucks and indefensible at eight thousand, and this is the
 * component every single telematics message passes through. Getting the shape right while the data
 * is small costs nothing.
 *
 * <p>Immutable after construction, and therefore safe to share across the web server's request
 * threads without a lock. That is a deliberate property rather than an accident: S8's Mongo-backed
 * replacement will need a cache that can be refreshed, and refreshing an immutable snapshot by
 * swapping the whole reference is far easier to reason about than mutating maps under readers.
 *
 * <p>A duplicate vehicle, device or shipment id is a fault in the reference data rather than
 * something to resolve arbitrarily, so it fails at startup. Silently keeping the last one would
 * attribute a truck's positions to whichever load happened to be listed later in a YAML file.
 */
public class StaticIdentityResolver implements IdentityResolver {

  private final Map<String, Identity> byVehicle;
  private final Map<String, Identity> byDevice;
  private final Map<String, Identity> byShipment;

  public StaticIdentityResolver(List<IdentityProperties.Assignment> assignments) {
    Map<String, Identity> vehicles = new HashMap<>();
    Map<String, Identity> devices = new HashMap<>();
    Map<String, Identity> shipments = new HashMap<>();

    for (IdentityProperties.Assignment assignment : assignments) {
      Identity identity = new Identity(assignment.shipmentId(), assignment.vehicleId());
      put(vehicles, assignment.vehicleId(), identity, "vehicle");
      put(shipments, assignment.shipmentId(), identity, "shipment");
      for (String deviceId : assignment.deviceIds()) {
        put(devices, deviceId, identity, "device");
      }
    }

    this.byVehicle = Map.copyOf(vehicles);
    this.byDevice = Map.copyOf(devices);
    this.byShipment = Map.copyOf(shipments);
  }

  private static void put(Map<String, Identity> index, String key, Identity value, String what) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("identity assignment is missing a " + what + " id");
    }
    Identity existing = index.putIfAbsent(key, value);
    if (existing != null && !existing.equals(value)) {
      throw new IllegalArgumentException(
          "%s id %s is assigned twice, to %s and to %s"
              .formatted(what, key, existing.shipmentId(), value.shipmentId()));
    }
  }

  @Override
  public Optional<Identity> byVehicle(String vehicleId) {
    return Optional.ofNullable(vehicleId).map(byVehicle::get);
  }

  @Override
  public Optional<Identity> byDevice(String deviceId) {
    return Optional.ofNullable(deviceId).map(byDevice::get);
  }

  @Override
  public Optional<Identity> byShipment(String shipmentId) {
    return Optional.ofNullable(shipmentId).map(byShipment::get);
  }

  /** How many vehicles this resolver knows about. Logged at startup; also a test seam. */
  public int size() {
    return byVehicle.size();
  }
}
