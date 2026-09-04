package com.fleettracking.gateway.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The same reference-data question, answered from a list held in memory.
 *
 * <p>Test-only, and deliberately so. Until S8 a resolver of roughly this shape was the production
 * implementation, backed by a list of assignments in {@code application.yaml}; it was replaced
 * because a fixed list cannot express that an assignment starts and ends. What survives is its
 * usefulness to a unit test: a normalizer test is about unit conversion, timestamp parsing and
 * segment counting, and starting a database container to prove that {@code DEV-0002} maps to
 * {@code SHP-HYD-0002} would make those tests slower without making them stricter.
 *
 * <p>It honours validity windows rather than ignoring them, because a stub that answered every
 * instant identically would let a normalizer pass the wrong timestamp to the resolver and no test
 * would notice. {@link com.fleettracking.gateway.identity.MongoIdentityResolverIT} proves the real
 * implementation agrees.
 */
public class InMemoryIdentityResolver implements IdentityResolver {

  private final List<Assignment> assignments;

  public InMemoryIdentityResolver(List<Assignment> assignments) {
    this.assignments = List.copyOf(assignments);
  }

  @Override
  public Optional<Identity> byVehicle(String vehicleId, Instant asOf) {
    return find(a -> a.vehicleId().equals(vehicleId), vehicleId, asOf);
  }

  @Override
  public Optional<Identity> byDevice(String deviceId, Instant asOf) {
    return find(a -> a.deviceIds().contains(deviceId), deviceId, asOf);
  }

  @Override
  public Optional<Identity> byShipment(String shipmentId, Instant asOf) {
    return find(a -> a.shipmentId().equals(shipmentId), shipmentId, asOf);
  }

  private Optional<Identity> find(
      java.util.function.Predicate<Assignment> matches, String identifier, Instant asOf) {
    if (identifier == null || identifier.isBlank() || asOf == null) {
      return Optional.empty();
    }
    return assignments.stream()
        .filter(matches)
        .filter(a -> a.covers(asOf))
        .findFirst()
        .map(Assignment::identity);
  }

  public int size() {
    return assignments.size();
  }
}
