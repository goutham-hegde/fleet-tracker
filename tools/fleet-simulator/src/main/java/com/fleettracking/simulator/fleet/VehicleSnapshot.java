package com.fleettracking.simulator.fleet;

import com.fleettracking.events.GeoPoint;
import java.time.Instant;

/**
 * Everything true about one truck at one instant — an immutable reading taken after a tick.
 *
 * <p>This is the seam between the simulation and everything that observes it. {@link Truck} is
 * mutable by necessity (it is a moving object), but nothing outside this package ever sees that
 * mutable object: the tick loop hands out snapshots. So an emitter that formats a telematics
 * payload cannot accidentally hold a reference that changes under it a tick later, and a test can
 * collect a whole run's worth of readings and assert over the trajectory afterwards.
 *
 * <p>The identity triple is carried in full because the four feeds each know a <em>different</em>
 * subset of it, and S5's whole difficulty is that mismatch: telematics knows the vehicle, the
 * mobile app knows the shipment, and a reefer sensor knows only its own {@code deviceId} and has to
 * be joined back through device → vehicle → shipment by the gateway in M2.
 *
 * @param vehicleId the tractor
 * @param shipmentId the load it is carrying, and the Kafka partition key downstream
 * @param deviceId the reefer probe bolted to the trailer — the only identity that feed carries
 * @param routeId which itinerary it is running
 * @param at simulated wall-clock time of this reading
 * @param position where it is
 * @param speedKph ground speed over the road, not along the simplified straight-line path
 * @param headingDegrees compass heading, {@code [0, 360)}; retained while stationary
 * @param odometerKm lifetime road distance, monotonically increasing
 * @param phase what it is doing
 * @param currentStopId the stop it is dwelling at, or {@code null} while driving
 * @param nextStopId the stop it is driving toward, or {@code null} once the route is complete
 * @param temperatureCelsius reefer set-point reading, present on every truck carrying a probe
 */
public record VehicleSnapshot(
    String vehicleId,
    String shipmentId,
    String deviceId,
    String routeId,
    Instant at,
    GeoPoint position,
    double speedKph,
    double headingDegrees,
    double odometerKm,
    TruckPhase phase,
    String currentStopId,
    String nextStopId,
    double temperatureCelsius) {

  /** True while the truck is stationary at a stop. */
  public boolean isStationary() {
    return phase != TruckPhase.DRIVING;
  }
}
