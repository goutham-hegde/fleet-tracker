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

  /** A dry-van truck driving down the Delhi-Mumbai lane at 60 km/h. */
  static VehicleSnapshot driving() {
    return new VehicleSnapshot(
        "VEH-0007",
        "SHP-DEL-0007",
        "DEV-0007",
        "del-bom-nh48",
        AT,
        new GeoPoint(26.912345678, 75.787412345),
        60.0,
        214.35,
        123456.789,
        TruckPhase.DRIVING,
        null,
        "amd-aslali",
        18.4);
  }

  /** A refrigerated truck sitting on a hospital dock in Kurnool. */
  static VehicleSnapshot coldChainDwelling() {
    return new VehicleSnapshot(
        "VEH-0002",
        "SHP-HYD-0002",
        "DEV-0002",
        "hyd-blr-cold",
        AT,
        new GeoPoint(15.8281, 78.0373),
        0.0,
        91.0,
        88_000.0,
        TruckPhase.DWELLING,
        "knl-clinic",
        "blr-hosp",
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
        TruckPhase.COMPLETED, "bom-bhiwandi", null, base.temperatureCelsius());
  }

  static final com.fleettracking.simulator.route.Stop PICKUP =
      new com.fleettracking.simulator.route.Stop(
          "del-okhla",
          "Okhla DC",
          "Delhi",
          "DL",
          new GeoPoint(28.5355, 77.2730),
          400,
          java.time.Duration.ofMinutes(75),
          com.fleettracking.simulator.route.Stop.StopKind.PICKUP);

  static final com.fleettracking.simulator.route.Stop DELIVERY =
      new com.fleettracking.simulator.route.Stop(
          "amd-aslali",
          "Aslali crossdock",
          "Ahmedabad",
          "GJ",
          new GeoPoint(23.0225, 72.5714),
          400,
          java.time.Duration.ofMinutes(65),
          com.fleettracking.simulator.route.Stop.StopKind.DELIVERY);

  /** A fuel stop: a real arrival that no carrier would ever file an EDI status for. */
  static final com.fleettracking.simulator.route.Stop WAYPOINT =
      new com.fleettracking.simulator.route.Stop(
          "pnv-fuel",
          "Panvel fuel stop",
          "Panvel",
          "MH",
          new GeoPoint(18.9894, 73.1175),
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
