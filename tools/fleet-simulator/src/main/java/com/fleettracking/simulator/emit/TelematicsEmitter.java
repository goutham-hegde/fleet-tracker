package com.fleettracking.simulator.emit;

import com.fleettracking.events.EventJson;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.simulator.Simulation;
import com.fleettracking.simulator.TickObserver;
import com.fleettracking.simulator.fault.FaultProfile;
import com.fleettracking.simulator.fleet.TruckPhase;
import com.fleettracking.simulator.fleet.VehicleSnapshot;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * The in-cab telematics unit: high-frequency, deeply nested, imperial JSON.
 *
 * <p>This is the feed that behaves best and is therefore the easiest to get wrong. It reports on a
 * fixed cadence whether or not anything is happening, it never goes quiet, and it never lies about
 * where it is. What it does do is describe the world in a shape nothing downstream wants:
 *
 * <ul>
 *   <li><b>Imperial units.</b> Miles per hour and miles on the odometer, Fahrenheit on the engine.
 *       The canonical envelope is metric, so every number here is converted by the normalizer —
 *       never by a consumer, and never twice.
 *   <li><b>Nesting.</b> Position, odometer and engine data each sit in their own object, because
 *       that is how a device vendor models a device rather than how a logistics platform models a
 *       shipment. Flattening it is the normalizer's job.
 *   <li><b>No shipment id.</b> A telematics box is bolted to a tractor and knows nothing about
 *       loads. It reports a vehicle, and identity resolution in S8 has to find the shipment that
 *       vehicle is currently pulling. An event that arrives here with a shipment id already on it
 *       would make that step untestable.
 *   <li><b>Its own device namespace.</b> The unit is {@code TLM-0001} while the reefer probe on the
 *       same trailer is {@code DEV-0001}. Two feeds, two identifiers, one truck — which is exactly
 *       the mapping problem S8 exists to solve, and it would vanish if both used one id.
 *   <li><b>Accuracy as HDOP.</b> The unit reports horizontal dilution of precision, a unitless
 *       quality figure from the GPS constellation's geometry, not a radius in metres. The canonical
 *       envelope wants metres. Roughly, metres are HDOP multiplied by the receiver's baseline
 *       accuracy — a conversion the normalizer has to know about, because a consumer reading 0.9 as
 *       "0.9 metres" would trust a fix far more than it deserves.
 * </ul>
 */
public class TelematicsEmitter implements TickObserver {

  private final EmissionProperties.Telematics config;
  private final MessageSink sink;
  private final Cadence cadence;
  private final java.util.random.RandomGenerator random;
  private final FaultProfile faults;

  public TelematicsEmitter(
      EmissionProperties.Telematics config,
      MessageSink sink,
      java.util.random.RandomGenerator random,
      FaultProfile faults) {
    this.config = config;
    this.sink = sink;
    this.random = random;
    this.faults = faults;
    this.cadence = new Cadence(config.interval(), random);
  }

  @Override
  public void onTick(Simulation.TickReport report) {
    for (VehicleSnapshot snapshot : report.snapshots()) {
      if (snapshot.phase() == TruckPhase.COMPLETED) {
        // The tractor is done and the unit has stopped reporting on this load.
        continue;
      }
      if (cadence.due(snapshot.vehicleId(), report.at())) {
        sink.accept(message(snapshot));
      }
    }
    cadence.retainOnly(
        report.snapshots().stream().map(VehicleSnapshot::vehicleId).collect(Collectors.toSet()));
  }

  private SourceMessage message(VehicleSnapshot s) {
    String body = EventJson.mapper().writeValueAsString(payload(s));
    // Keyed by vehicle because that is genuinely all this feed knows.
    return SourceMessage.live(SourceSystem.TELEMATICS, s.vehicleId(), s.at(), body);
  }

  private Payload payload(VehicleSnapshot s) {
    boolean moving = s.phase() == TruckPhase.DRIVING;
    // The receiver's view of where it is, not the truth. The simulation's own position is
    // untouched -- only what goes on the wire is degraded.
    com.fleettracking.events.GeoPoint reported = faults.perturb(s.position());
    return new Payload(
        unitId(s.vehicleId()),
        new Vehicle(s.vehicleId(), unitNumber(s.vehicleId()), make(s.vehicleId())),
        new Gps(
            Units.round(reported.latitude(), 6),
            Units.round(reported.longitude(), 6),
            Units.round(Units.kphToMph(s.speedKph()), 1),
            Units.round(s.headingDegrees(), 1),
            7 + random.nextInt(6),
            Units.round(0.7 + random.nextDouble() * 0.9, 2),
            s.at()),
        new Odometer(Units.round(Units.kmToMiles(s.odometerKm()), 1), "mi"),
        new Engine(
            moving ? (int) Math.round(900 + Math.min(s.speedKph(), 110) * 6) : 650,
            (int) Math.round(Units.celsiusToFahrenheit(moving ? 90.5 : 85.0)),
            Units.round(fuelLevelPct(s.odometerKm()), 1),
            "ON"),
        s.at(),
        "2.3");
  }

  /**
   * Fuel as a function of distance since the last fill, with a 1 600 km tank.
   *
   * <p>Derived from the odometer rather than tracked as state, which keeps the emitter stateless
   * and gives the same truck the same fuel level on a replay of the same seed. It falls steadily
   * and jumps back to full at each refuel, which is the shape a consumer would expect.
   */
  private static double fuelLevelPct(double odometerKm) {
    double tankRangeKm = 1600.0;
    double used = odometerKm % tankRangeKm;
    return 100.0 * (1.0 - used / tankRangeKm);
  }

  /** {@code VEH-0007} to {@code TLM-0007}: same truck, different box, different id namespace. */
  private String unitId(String vehicleId) {
    return config.unitPrefix() + "-" + unitNumber(vehicleId);
  }

  private static String unitNumber(String vehicleId) {
    int dash = vehicleId.lastIndexOf('-');
    return dash < 0 ? vehicleId : vehicleId.substring(dash + 1);
  }

  /** Deterministic from the vehicle number, so a truck keeps the same make across a run. */
  private static String make(String vehicleId) {
    String[] makes = {"Tata", "Ashok Leyland", "BharatBenz", "Eicher", "Mahindra"};
    int n = Math.abs(vehicleId.hashCode());
    return makes[n % makes.length];
  }

  /** The wire shape. Nested exactly as a device vendor would nest it. */
  record Payload(
      String deviceId,
      Vehicle vehicle,
      Gps gps,
      Odometer odometer,
      Engine engine,
      Instant sentAt,
      String schemaVersion) {}

  record Vehicle(String id, String unitNumber, String make) {}

  /** Note {@code speedMph} and {@code hdop}: neither is what the canonical envelope carries. */
  record Gps(
      double lat,
      double lon,
      double speedMph,
      double headingDeg,
      int satellites,
      double hdop,
      Instant fixTime) {}

  record Odometer(double value, String unit) {}

  record Engine(int rpm, int coolantTempF, double fuelLevelPct, String ignition) {}
}
