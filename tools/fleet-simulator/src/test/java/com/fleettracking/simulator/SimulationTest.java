package com.fleettracking.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleettracking.simulator.fleet.TruckTransition;
import com.fleettracking.simulator.fleet.VehicleSnapshot;
import com.fleettracking.simulator.route.Lanes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SimulationTest {

  private static final Instant START = Instant.parse("2026-08-29T06:00:00Z");

  private Simulation simulation(int trucks, boolean repeat) {
    return new Simulation(START, Duration.ofSeconds(30), trucks, Lanes.ALL, 20260829L, repeat);
  }

  @Test
  @DisplayName("spreads trucks across the lanes and gives each a distinct identity")
  void buildsAFleet() {
    Simulation sim = simulation(8, false);

    assertThat(sim.trucks()).hasSize(8);

    Set<String> vehicleIds =
        sim.trucks().stream().map(t -> t.vehicleId()).collect(Collectors.toSet());
    assertThat(vehicleIds).hasSize(8);

    Set<String> lanesUsed =
        sim.trucks().stream().map(t -> t.route().id()).collect(Collectors.toSet());
    assertThat(lanesUsed).hasSize(Lanes.ALL.size());
  }

  @Test
  @DisplayName("advances simulated time by the tick delta, not by wall-clock time")
  void keepsItsOwnClock() {
    Simulation sim = simulation(2, false);

    assertThat(sim.now()).isEqualTo(START);
    Simulation.TickReport first = sim.tick();
    assertThat(first.at()).isEqualTo(START.plusSeconds(30));
    assertThat(first.tickNumber()).isEqualTo(1);

    sim.tick();
    assertThat(sim.now()).isEqualTo(START.plusSeconds(60));
  }

  @Test
  @DisplayName("reports one snapshot per truck per tick, stamped with simulated time")
  void reportsEveryTruckEachTick() {
    Simulation sim = simulation(5, false);
    Simulation.TickReport report = sim.tick();

    assertThat(report.snapshots()).hasSize(5);
    assertThat(report.snapshots()).allSatisfy(s -> assertThat(s.at()).isEqualTo(report.at()));
  }

  @Test
  @DisplayName("runs a whole fleet to completion, arriving at every stop of every route")
  void runsToCompletion() {
    Simulation sim = simulation(4, false);

    List<TruckTransition> transitions = new java.util.ArrayList<>();
    int ticks = 0;
    // 30 s per tick, and the longest lane is around 20 hours of driving plus dwell.
    while (!sim.isFinished() && ticks < 20_000) {
      transitions.addAll(sim.tick().transitions());
      ticks++;
    }

    assertThat(sim.isFinished()).isTrue();

    // Four trucks, one per lane: every non-origin stop on every lane, arrived at exactly once.
    long expectedArrivals =
        Lanes.ALL.stream().mapToLong(r -> r.stops().size() - 1).sum();
    long arrivals =
        transitions.stream().filter(TruckTransition.Arrived.class::isInstance).count();
    assertThat(arrivals).isEqualTo(expectedArrivals);

    long completions =
        transitions.stream().filter(TruckTransition.RouteCompleted.class::isInstance).count();
    assertThat(completions).isEqualTo(4);
  }

  @Test
  @DisplayName("keeps the fleet populated when routes repeat")
  void replacesFinishedTrucks() {
    Simulation sim = simulation(4, true);

    for (int i = 0; i < 20_000; i++) {
      sim.tick();
    }

    // Never finishes, and never shrinks.
    assertThat(sim.isFinished()).isFalse();
    assertThat(sim.trucks()).hasSize(4);
    assertThat(sim.trucks()).noneMatch(t -> t.isFinished());
  }

  @Test
  @DisplayName("the same seed replays exactly; a different one does not")
  void isReproducible() {
    assertThat(traceOf(20260829L)).isEqualTo(traceOf(20260829L));
    assertThat(traceOf(20260829L)).isNotEqualTo(traceOf(1L));
  }

  private List<VehicleSnapshot> traceOf(long seed) {
    Simulation sim =
        new Simulation(START, Duration.ofSeconds(30), 4, Lanes.ALL, seed, false);
    List<VehicleSnapshot> snapshots = new java.util.ArrayList<>();
    for (int i = 0; i < 200; i++) {
      snapshots.addAll(sim.tick().snapshots());
    }
    return snapshots;
  }

  @Test
  @DisplayName("cold-chain lanes run a reefer set point; ambient lanes do not")
  void coldChainTrucksRunCold() {
    Simulation sim = simulation(8, false);
    Simulation.TickReport report = sim.tick();

    List<VehicleSnapshot> cold =
        report.snapshots().stream().filter(s -> s.routeId().contains("cold")).toList();
    List<VehicleSnapshot> ambient =
        report.snapshots().stream().filter(s -> !s.routeId().contains("cold")).toList();

    assertThat(cold).isNotEmpty();
    assertThat(ambient).isNotEmpty();
    assertThat(cold).allSatisfy(s -> assertThat(s.temperatureCelsius()).isBetween(0.0, 9.0));
    assertThat(ambient).allSatisfy(s -> assertThat(s.temperatureCelsius()).isBetween(13.0, 23.0));
  }

  @Test
  @DisplayName("rejects a fleet with no trucks or no lanes")
  void rejectsDegenerateConfigurations() {
    assertThatThrownBy(() -> new Simulation(START, Duration.ofSeconds(1), 0, Lanes.ALL, 1L, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("truckCount");

    assertThatThrownBy(() -> new Simulation(START, Duration.ofSeconds(1), 1, List.of(), 1L, false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one lane");
  }

  @Test
  @DisplayName("time scale converts real ticks into simulated ones")
  void timeScaleScalesTheTick() {
    SimulatorProperties realTime =
        new SimulatorProperties(Duration.ofSeconds(1), 1.0, 4, 1L, true, false);
    SimulatorProperties compressed =
        new SimulatorProperties(Duration.ofSeconds(1), 60.0, 4, 1L, true, false);

    assertThat(realTime.simulatedTickDelta()).isEqualTo(Duration.ofSeconds(1));
    assertThat(compressed.simulatedTickDelta()).isEqualTo(Duration.ofSeconds(60));
  }
}
