package com.fleettracking.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Checks that the application actually wires up and runs.
 *
 * <p>Worth having even though it looks trivial: everything else in this module is plain Java that a
 * context failure would not touch. A misspelled property prefix, a missing
 * {@code @EnableConfigurationProperties}, or a bean the runner cannot find would all leave 60-odd
 * green unit tests and an application that dies on startup.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      "fleet.simulator.tick-interval=20ms",
      "fleet.simulator.time-scale=30",
      "fleet.simulator.trucks=4",
      "fleet.simulator.seed=7",
      "fleet.simulator.auto-start=true"
    })
class FleetSimulatorApplicationTest {

  @Autowired private SimulatorProperties properties;
  @Autowired private SimulationRunner runner;

  @Test
  @DisplayName("binds configuration from properties")
  void bindsProperties() {
    assertThat(properties.tickInterval()).isEqualTo(Duration.ofMillis(20));
    assertThat(properties.timeScale()).isEqualTo(30.0);
    assertThat(properties.trucks()).isEqualTo(4);
    assertThat(properties.seed()).isEqualTo(7L);
    // 20 ms of real time carries 600 ms of simulated time at this scale.
    assertThat(properties.simulatedTickDelta()).isEqualTo(Duration.ofMillis(600));
  }

  @Test
  @DisplayName("starts ticking on its own and moves the fleet")
  void runsTheSimulation() {
    assertThat(runner.isRunning()).isTrue();

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              Simulation simulation = runner.simulation();
              assertThat(simulation).isNotNull();
              assertThat(simulation.tickCount()).isGreaterThan(20);
              assertThat(simulation.trucks()).hasSize(4);
            });
  }
}
