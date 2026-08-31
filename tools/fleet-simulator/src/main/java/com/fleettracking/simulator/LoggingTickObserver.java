package com.fleettracking.simulator;

import com.fleettracking.simulator.fleet.TruckTransition;
import com.fleettracking.simulator.fleet.VehicleSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Prints what the fleet is doing to the console.
 *
 * <p>The only observer S4 ships, and it exists so that a run is watchable: until S5 writes actual
 * wire formats and S6 sends them to Kafka, this log is the entire observable output of the
 * simulator. Every arrival and departure is logged as it happens, and a periodic summary line keeps
 * a long run from looking hung without drowning the console in one line per truck per second.
 */
@Component
public class LoggingTickObserver implements TickObserver {

  private static final Logger log = LoggerFactory.getLogger(LoggingTickObserver.class);

  private final long summaryEveryTicks;

  public LoggingTickObserver(SimulatorProperties properties) {
    // Summarise about once a simulated minute, whatever the tick rate happens to be.
    long perMinute = Math.max(1, 60_000 / Math.max(1, properties.simulatedTickDelta().toMillis()));
    this.summaryEveryTicks = perMinute;
  }

  @Override
  public void onTick(Simulation.TickReport report) {
    for (TruckTransition transition : report.transitions()) {
      switch (transition) {
        case TruckTransition.Arrived a ->
            log.info("{}  {}  ARRIVED  {} ({}, {})", a.at(), a.shipmentId(), a.stop().name(), a.stop().city(), a.stop().state());
        case TruckTransition.Departed d ->
            log.info("{}  {}  DEPARTED {} ({}, {})", d.at(), d.shipmentId(), d.stop().name(), d.stop().city(), d.stop().state());
        case TruckTransition.RouteCompleted c ->
            log.info("{}  {}  COMPLETE {} ({}, {})", c.at(), c.shipmentId(), c.stop().name(), c.stop().city(), c.stop().state());
      }
    }

    if (report.tickNumber() % summaryEveryTicks != 0) {
      return;
    }

    long driving = report.snapshots().stream().filter(s -> !s.isStationary()).count();
    double averageSpeed =
        report.snapshots().stream()
            .filter(s -> !s.isStationary())
            .mapToDouble(VehicleSnapshot::speedKph)
            .average()
            .orElse(0);

    log.info(
        "{}  tick {}  {} trucks, {} driving, avg {} km/h",
        report.at(),
        report.tickNumber(),
        report.snapshots().size(),
        driving,
        Math.round(averageSpeed));

    if (log.isDebugEnabled()) {
      for (VehicleSnapshot s : report.snapshots()) {
        log.debug(
            "  {} {} {} ({}, {}) {} km/h heading {} -> {}",
            s.vehicleId(),
            s.shipmentId(),
            s.phase(),
            String.format("%.4f", s.position().latitude()),
            String.format("%.4f", s.position().longitude()),
            Math.round(s.speedKph()),
            Math.round(s.headingDegrees()),
            s.nextStopId() == null ? "done" : s.nextStopId());
      }
    }
  }
}
