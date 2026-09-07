package com.fleettracking.simulator;

import com.fleettracking.simulator.fault.DisruptionProperties;
import com.fleettracking.simulator.fault.DisruptionScheduler;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The synthetic fleet.
 *
 * <p>Runs trucks along real freight lanes with plausible movement physics and reports what they do.
 * As of S4 the only output is the log; S5 adds the four source wire formats, and S6 sends them to
 * Kafka.
 *
 * <p>Nothing in the cluster is required to run this — no Kafka, no MongoDB, no Kubernetes. It is
 * deliberately built before the services that consume its data, because until a data source exists
 * nothing downstream can be tested or demonstrated.
 */
@SpringBootApplication
@EnableConfigurationProperties({SimulatorProperties.class, DisruptionProperties.class})
public class FleetSimulatorApplication {

  public static void main(String[] args) {
    SpringApplication.run(FleetSimulatorApplication.class, args);
  }

  /**
   * The clock the simulation starts from.
   *
   * <p>Injected rather than read directly so a test can start a run at a fixed instant and assert
   * on exact timestamps. Note that this fixes only the <em>start</em>: once running, the simulation
   * advances its own clock a tick at a time and never consults this again.
   */
  @Bean
  public Clock simulationStartClock() {
    return Clock.systemUTC();
  }

  /**
   * What can go wrong with the trucks themselves.
   *
   * <p>Its generator is derived from the run's seed the same way each feed's is, and for the same
   * reason: a disruption has to replay. Sharing one generator with the emitters would mean that
   * switching a feed off silently changed which truck broke down, which is exactly the coupling
   * the per-consumer seeding exists to prevent.
   */
  @Bean
  public DisruptionScheduler disruptionScheduler(
      DisruptionProperties disruptions, SimulatorProperties simulator) {
    return new DisruptionScheduler(
        disruptions, new java.util.Random(simulator.seed() * 1_000_003L + "disruptions".hashCode()));
  }
}
