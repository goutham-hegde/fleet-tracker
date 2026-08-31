package com.fleettracking.simulator.emit;

import com.fleettracking.events.EventJson;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.simulator.Simulation;
import com.fleettracking.simulator.TickObserver;
import com.fleettracking.simulator.fleet.TruckPhase;
import com.fleettracking.simulator.fleet.VehicleSnapshot;
import java.time.Instant;
import java.util.stream.Collectors;

/**
 * The refrigerated trailer's temperature probe: a thermometer with a radio and no idea what it is
 * attached to.
 *
 * <p>Every other feed says something about a journey. This one says something about a box. It
 * reports a temperature and its own serial number, and that is the entire contents of the message:
 *
 * <ul>
 *   <li><b>No position.</b> The probe has no GPS. A reading cannot be drawn on a map, and it cannot
 *       be geofenced. It is the reason {@link com.fleettracking.events.StatusEvent} has to tolerate
 *       an event with neither coordinates nor a place name.
 *   <li><b>No shipment, and no vehicle either.</b> The only identity on the wire is a device id.
 *       Turning that into "the pharma load currently running Los Angeles to Denver" needs two hops
 *       through reference data — device to trailer to load — which is the whole of S8. A reading
 *       whose device cannot be resolved has no Kafka key and goes to the dead-letter topic.
 *   <li><b>A setpoint alongside the measurement.</b> Both numbers travel together because neither
 *       means anything alone: 4°C is healthy for pharma and catastrophic for ice cream. An SLA rule
 *       that hard-codes a threshold gets one of them wrong.
 * </ul>
 *
 * <p>Only refrigerated lanes carry a probe, which is what {@code onlyColdChain} controls. A dry van
 * has nothing to measure, and emitting ambient readings from one would invent a feed that does not
 * exist — and would quietly double the volume M4's cold-chain rules have to filter.
 *
 * <p>Doors open while the truck is on a dock and shut while it drives, so warm readings cluster
 * around dwells. That correlation is deliberate: it is the signal that separates a genuine
 * refrigeration failure from the ordinary warming of an open trailer, and M4 has to tell them
 * apart.
 */
public class ReeferEmitter implements TickObserver {

  /** Beyond this far from setpoint the unit raises its own alarm flag. */
  private static final double ALARM_DEVIATION_CELSIUS = 3.0;

  private final EmissionProperties.Reefer config;
  private final MessageSink sink;
  private final Cadence cadence;
  private final java.util.random.RandomGenerator random;

  public ReeferEmitter(
      EmissionProperties.Reefer config,
      MessageSink sink,
      java.util.random.RandomGenerator random) {
    this.config = config;
    this.sink = sink;
    this.random = random;
    this.cadence = new Cadence(config.interval(), random);
  }

  @Override
  public void onTick(Simulation.TickReport report) {
    for (VehicleSnapshot snapshot : report.snapshots()) {
      if (!hasProbe(snapshot) || snapshot.phase() == TruckPhase.COMPLETED) {
        continue;
      }
      if (cadence.due(snapshot.deviceId(), report.at())) {
        sink.accept(message(snapshot));
      }
    }
    cadence.retainOnly(
        report.snapshots().stream().map(VehicleSnapshot::deviceId).collect(Collectors.toSet()));
  }

  /**
   * Whether this trailer is refrigerated.
   *
   * <p>Keyed off the lane id the same way the simulation decides a truck's setpoint, so the two
   * cannot drift apart: a lane that is cold-chain for temperature purposes is cold-chain for
   * reporting purposes.
   */
  private boolean hasProbe(VehicleSnapshot snapshot) {
    return !config.onlyColdChain() || snapshot.routeId().contains("cold");
  }

  private SourceMessage message(VehicleSnapshot s) {
    String body = EventJson.mapper().writeValueAsString(payload(s));
    // Keyed by device, because the device id is the only identity in the payload.
    return SourceMessage.live(SourceSystem.REEFER_SENSOR, s.deviceId(), s.at(), body);
  }

  private Payload payload(VehicleSnapshot s) {
    double measured = s.temperatureCelsius();
    double setpoint = setpointFor(s);
    boolean doorOpen = s.phase() == TruckPhase.DWELLING;
    double deviation = Math.abs(measured - setpoint);

    return new Payload(
        s.deviceId(),
        config.model(),
        s.at(),
        Units.round(measured, 2),
        Units.round(setpoint, 1),
        // Return air comes back warmer than the box; supply air is what the unit blows in, colder.
        Units.round(measured + 0.3 + random.nextDouble() * 0.6, 2),
        Units.round(measured - 0.8 - random.nextDouble() * 0.6, 2),
        doorOpen ? "OPEN" : "CLOSED",
        Units.round(12.2 + random.nextDouble() * 0.6, 2),
        deviation > ALARM_DEVIATION_CELSIUS ? "TEMP_DEVIATION" : null);
  }

  /**
   * The setpoint the unit was told to hold.
   *
   * <p>The simulation gives cold-chain lanes 4°C and everything else 18°C, and the truck's measured
   * temperature drifts around that. Recovering it here rather than carrying it on the snapshot
   * keeps the movement core free of a field only one feed cares about.
   */
  private static double setpointFor(VehicleSnapshot s) {
    return s.routeId().contains("cold") ? 4.0 : 18.0;
  }

  /**
   * The wire shape.
   *
   * <p>{@code alarm} is null in the ordinary case and omitted from the JSON entirely, since the
   * shared mapper drops nulls. That absence is realistic — probes report exceptions, not a
   * perpetual "nothing is wrong" field — and it is a small trap for a normalizer that assumes every
   * documented field is present.
   */
  record Payload(
      String probe,
      String model,
      Instant readingUtc,
      double tempC,
      double setpointC,
      double returnAirC,
      double supplyAirC,
      String door,
      double batteryV,
      String alarm) {}
}
