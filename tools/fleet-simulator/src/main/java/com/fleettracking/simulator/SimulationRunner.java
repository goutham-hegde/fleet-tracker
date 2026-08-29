package com.fleettracking.simulator;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Drives the simulation on a real-time schedule and fans each tick out to the observers.
 *
 * <p>Implemented as a {@link SmartLifecycle} rather than with {@code @Scheduled} for two reasons.
 * The tick interval is configuration, and {@code @Scheduled} wants it as an annotation attribute
 * resolvable at startup, which makes it awkward to vary. More importantly, a lifecycle bean gets a
 * real {@link #stop()} — so shutting the application down finishes the tick in flight and then
 * stops, rather than being killed halfway through stepping the fleet.
 *
 * <p>The loop uses {@code scheduleWithFixedDelay}, not {@code scheduleAtFixedRate}. If a tick ever
 * overruns its interval, fixed-rate scheduling responds by firing the backlog back to back, which
 * would make an overloaded simulator produce a burst of events at the exact moment it is already
 * struggling. Fixed delay lets it simply run slower, which is the behaviour to want here.
 */
@Component
public class SimulationRunner implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(SimulationRunner.class);

  private final SimulatorProperties properties;
  private final List<TickObserver> observers;
  private final Clock clock;

  private volatile Simulation simulation;
  private ScheduledExecutorService executor;
  private volatile boolean running;

  public SimulationRunner(SimulatorProperties properties, List<TickObserver> observers, Clock clock) {
    this.properties = properties;
    this.observers = observers;
    this.clock = clock;
  }

  @Override
  public boolean isAutoStartup() {
    return properties.autoStart();
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    simulation = Simulation.from(properties, clock.instant());
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "fleet-simulator-tick");
              // Deliberately NOT a daemon. This application serves no HTTP port, so the tick
              // thread is the only thing holding the JVM open; as a daemon it would be abandoned
              // the moment main returned and the process would exit after a single tick, having
              // logged a clean startup. Being non-daemon is safe because stop() below is what
              // ends it, and Spring calls that from the shutdown hook.
              t.setDaemon(false);
              return t;
            });

    log.info(
        "Starting simulation: {} trucks over {} lanes, tick {} of real time = {} simulated, seed {}",
        properties.trucks(),
        com.fleettracking.simulator.route.Lanes.ALL.size(),
        properties.tickInterval(),
        properties.simulatedTickDelta(),
        properties.seed());

    running = true;
    long delayMillis = Math.max(1, properties.tickInterval().toMillis());
    executor.scheduleWithFixedDelay(this::tickSafely, 0, delayMillis, TimeUnit.MILLISECONDS);
  }

  /**
   * Runs one tick, swallowing anything an observer throws.
   *
   * <p>A scheduled task that throws is silently cancelled by the executor and never runs again —
   * the whole simulation would stop with no error beyond one log line, which is a genuinely
   * horrible failure mode to debug. Catching here means a broken observer degrades to noisy logs
   * rather than a dead fleet.
   */
  private void tickSafely() {
    try {
      Simulation.TickReport report = simulation.tick();
      for (TickObserver observer : observers) {
        try {
          observer.onTick(report);
        } catch (RuntimeException e) {
          log.error("Observer {} failed on tick {}", observer.getClass().getSimpleName(), report.tickNumber(), e);
        }
      }
      if (simulation.isFinished()) {
        log.info("Every truck has completed its route after {} ticks; stopping", report.tickNumber());
        stop();
      }
    } catch (RuntimeException e) {
      log.error("Simulation tick failed", e);
    }
  }

  @Override
  public void stop() {
    if (!running) {
      return;
    }
    running = false;
    executor.shutdown();
    try {
      // Let the tick in flight finish rather than tearing the fleet's state in half.
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
    log.info("Simulation stopped after {} ticks", simulation == null ? 0 : simulation.tickCount());
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** The live simulation, or {@code null} before {@link #start()}. */
  public Simulation simulation() {
    return simulation;
  }
}
