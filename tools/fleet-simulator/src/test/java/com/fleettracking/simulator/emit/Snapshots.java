package com.fleettracking.simulator.emit;

import com.fleettracking.events.GeoPoint;
import com.fleettracking.simulator.Simulation;
import com.fleettracking.simulator.fleet.TruckPhase;
import com.fleettracking.simulator.fleet.TruckTransition;
import com.fleettracking.simulator.fleet.VehicleSnapshot;
import java.time.Instant;
import java.util.List;

/**
 * Hand-built snapshots, so an emitter test states the exact truck state it is about rather than
 * running a simulation until one happens to arise.
 */
final class Snapshots {

  static final Instant AT = Instant.parse("2026-08-31T14:12:03Z");

  private Snapshots() {}

  /** A dry-van truck driving down the Chicago-Dallas lane at 100 km/h. */
  static VehicleSnapshot driving() {
    return new VehicleSnapshot(
        "VEH-0007",
        "SHP-CHI-0007",
        "DEV-0007",
        "chi-dal-i55",
        AT,
        new GeoPoint(41.878123456, -87.629812345),
        100.0,
        214.35,
        123456.789,
        TruckPhase.DRIVING,
        null,
        "stl-xd",
        18.4);
  }

  /** A refrigerated truck sitting on a dock in Phoenix. */
  static VehicleSnapshot coldChainDwelling() {
    return new VehicleSnapshot(
        "VEH-0002",
        "SHP-LAX-0002",
        "DEV-0002",
        "lax-den-cold",
        AT,
        new GeoPoint(33.4484, -112.0740),
        0.0,
        91.0,
        88_000.0,
        TruckPhase.DWELLING,
        "phx-clinic",
        "den-hosp",
        4.2);
  }

  /** Same reefer, but the box has warmed well past its setpoint. */
  static VehicleSnapshot coldChainWarm() {
    VehicleSnapshot base = coldChainDwelling();
    return new VehicleSnapshot(
        base.vehicleId(), base.shipmentId(), base.deviceId(), base.routeId(), base.at(),
        base.position(), base.speedKph(), base.headingDegrees(), base.odometerKm(),
        base.phase(), base.currentStopId(), base.nextStopId(), 9.8);
  }

  static VehicleSnapshot completed() {
    VehicleSnapshot base = driving();
    return new VehicleSnapshot(
        base.vehicleId(), base.shipmentId(), base.deviceId(), base.routeId(), base.at(),
        base.position(), 0.0, base.headingDegrees(), base.odometerKm(),
        TruckPhase.COMPLETED, "dal-dc", null, base.temperatureCelsius());
  }

  static final com.fleettracking.simulator.route.Stop PICKUP =
      new com.fleettracking.simulator.route.Stop(
          "chi-dc",
          "Chicago DC",
          "Chicago",
          "IL",
          new GeoPoint(41.8781, -87.6298),
          400,
          java.time.Duration.ofMinutes(75),
          com.fleettracking.simulator.route.Stop.StopKind.PICKUP);

  static final com.fleettracking.simulator.route.Stop DELIVERY =
      new com.fleettracking.simulator.route.Stop(
          "mem-hub",
          "Memphis hub",
          "Memphis",
          "TN",
          new GeoPoint(35.1495, -90.0490),
          400,
          java.time.Duration.ofMinutes(65),
          com.fleettracking.simulator.route.Stop.StopKind.DELIVERY);

  /** A fuel stop: a real arrival that no carrier would ever file an EDI status for. */
  static final com.fleettracking.simulator.route.Stop WAYPOINT =
      new com.fleettracking.simulator.route.Stop(
          "sat-fuel",
          "San Antonio fuel stop",
          "San Antonio",
          "TX",
          new GeoPoint(29.4241, -98.4936),
          200,
          java.time.Duration.ofMinutes(20),
          com.fleettracking.simulator.route.Stop.StopKind.WAYPOINT);

  /** A tick report at the given time carrying the given trucks and no transitions. */
  static Simulation.TickReport report(Instant at, long tickNumber, VehicleSnapshot... snapshots) {
    return new Simulation.TickReport(at, tickNumber, List.of(snapshots), List.of());
  }

  static Simulation.TickReport report(
      Instant at, long tickNumber, List<VehicleSnapshot> snapshots, List<TruckTransition> transitions) {
    return new Simulation.TickReport(at, tickNumber, snapshots, transitions);
  }

  /**
   * Runs a snapshot forward through many ticks, moving only its clock.
   *
   * <p>Enough for cadence-driven emitters, which care about elapsed simulated time and not about
   * whether the truck actually moved.
   */
  static VehicleSnapshot at(VehicleSnapshot base, Instant when) {
    return new VehicleSnapshot(
        base.vehicleId(), base.shipmentId(), base.deviceId(), base.routeId(), when,
        base.position(), base.speedKph(), base.headingDegrees(), base.odometerKm(),
        base.phase(), base.currentStopId(), base.nextStopId(), base.temperatureCelsius());
  }
}
