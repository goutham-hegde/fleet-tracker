package com.fleettracking.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.simulator.fault.DisruptionScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The runner's one decision about real time: when a bounded run is over.
 *
 * <p>Driven by a clock the test moves by hand, so "stops at the deadline" is shown by moving past
 * it rather than by sleeping until it arrives and hoping the machine was not busy.
 */
class SimulationRunnerTest {

  private static final Instant START = Instant.parse("2026-09-16T10:00:00Z");

  /** A clock that stands still until told otherwise. */
  static final class MovableClock extends Clock {
    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    void advance(Duration by) {
      now.updateAndGet(i -> i.plus(by));
    }

    @Override
    public Instant instant() {
      return now.get();
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }

  private static SimulatorProperties properties(Duration runFor) {
    return new SimulatorProperties(Duration.ofMillis(5), 60.0, 2, 1L, true, false, runFor);
  }

  @Test
  @DisplayName("a bounded run keeps ticking until its time is up, then stops by itself")
  void stopsWhenRunForHasElapsed() {
    MovableClock clock = new MovableClock();
    AtomicLong ticks = new AtomicLong();
    SimulationRunner runner =
        new SimulationRunner(
            properties(Duration.ofMinutes(3)),
            List.of(report -> ticks.incrementAndGet()),
            clock,
            DisruptionScheduler.none());

    runner.start();
    try {
      Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> ticks.get() > 20);
      assertThat(runner.isRunning()).isTrue();

      clock.advance(Duration.ofMinutes(3));

      Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !runner.isRunning());
    } finally {
      runner.stop();
    }
  }

  @Test
  @DisplayName("an unbounded run ignores the clock entirely")
  void runsForEverWithoutRunFor() {
    MovableClock clock = new MovableClock();
    AtomicLong ticks = new AtomicLong();
    SimulationRunner runner =
        new SimulationRunner(
            properties(null), List.of(report -> ticks.incrementAndGet()), clock, DisruptionScheduler.none());

    runner.start();
    try {
      clock.advance(Duration.ofDays(365));
      long seen = ticks.get();
      Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> ticks.get() > seen + 20);
      assertThat(runner.isRunning()).isTrue();
    } finally {
      runner.stop();
    }
  }
}
