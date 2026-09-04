package com.fleettracking.simulator;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about a simulation run that is worth changing without recompiling.
 *
 * <p>Bound from the {@code fleet.simulator} prefix in {@code application.yml} or from environment
 * variables — Spring maps {@code FLEET_SIMULATOR_TIME_SCALE} onto {@code timeScale}, which is how
 * this gets configured once it runs in Kubernetes in M6.
 *
 * @param tickInterval real time between ticks. One second is the natural choice: it is roughly the
 *     rate a telematics unit reports at, and it makes the arithmetic between simulated and real
 *     time legible
 * @param timeScale how many simulated seconds pass per real second. At 1.0 the trucks run in real
 *     time, which is what a live demo wants — trucks visibly crawling across a map. At 60.0 a
 *     thirty-hour Delhi-Mumbai run finishes in thirty minutes, which is what a soak test wants.
 *     Raising this does <em>not</em> raise the event rate; it raises how much ground each tick
 *     covers, so the two knobs stay independent
 * @param trucks how many trucks to run. They are spread round-robin across the available lanes
 * @param seed the master seed. Every truck derives its own from this and its index, so a run is
 *     reproducible end to end — the same seed replays the same traffic, the same wander, and in S5
 *     the same faults. A simulator you cannot replay is a poor test fixture
 * @param repeatRoutes whether a truck that finishes its route restarts on a fresh one. True keeps a
 *     demo populated indefinitely; false lets a test run to a definite end
 * @param autoStart whether to begin ticking on startup. Tests set this false and drive the
 *     simulation by hand
 */
@ConfigurationProperties(prefix = "fleet.simulator")
public record SimulatorProperties(
    Duration tickInterval, double timeScale, int trucks, long seed, boolean repeatRoutes, boolean autoStart) {

  public SimulatorProperties {
    if (tickInterval == null) {
      tickInterval = Duration.ofSeconds(1);
    }
    if (timeScale <= 0) {
      timeScale = 1.0;
    }
    if (trucks <= 0) {
      trucks = 8;
    }
    if (tickInterval.isNegative() || tickInterval.isZero()) {
      throw new IllegalArgumentException("tickInterval must be positive: " + tickInterval);
    }
  }

  /** How much simulated time one tick covers. */
  public Duration simulatedTickDelta() {
    return Duration.ofNanos((long) (tickInterval.toNanos() * timeScale));
  }
}
