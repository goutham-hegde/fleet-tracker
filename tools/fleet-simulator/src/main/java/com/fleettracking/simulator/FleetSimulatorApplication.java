package com.fleettracking.simulator;

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
@EnableConfigurationProperties(SimulatorProperties.class)
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
}
