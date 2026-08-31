package com.fleettracking.simulator.emit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CadenceTest {

  private static final Instant START = Instant.parse("2026-08-31T14:00:00Z");

  @Test
  @DisplayName("fires at the configured rate over a long run")
  void firesAtTheConfiguredRate() {
    Cadence cadence = new Cadence(Duration.ofSeconds(30), new Random(1));

    int fired = 0;
    for (int i = 1; i <= 1200; i++) { // 1200 simulated seconds, one tick each
      if (cadence.due("VEH-0001", START.plusSeconds(i))) {
        fired++;
      }
    }

    assertThat(fired).isBetween(39, 40); // 1200 / 30, less the random starting phase
  }

  @Test
  @DisplayName("staggers devices instead of firing them all on the same tick")
  void staggersDevices() {
    Cadence cadence = new Cadence(Duration.ofSeconds(30), new Random(1));

    int simultaneous = 0;
    for (int i = 1; i <= 120; i++) {
      Instant at = START.plusSeconds(i);
      long firedThisTick =
          java.util.stream.IntStream.rangeClosed(1, 8)
              .filter(truck -> cadence.due("VEH-%04d".formatted(truck), at))
              .count();
      if (firedThisTick == 8) {
        simultaneous++;
      }
    }

    assertThat(simultaneous).isZero();
  }

  @Test
  @DisplayName("skips whole intervals rather than firing a backlog when time jumps")
  void skipsRatherThanBursts() {
    Cadence cadence = new Cadence(Duration.ofSeconds(30), new Random(1));

    // Establish the schedule, then jump an hour in one tick -- what a high time-scale does.
    cadence.due("VEH-0001", START);
    assertThat(cadence.due("VEH-0001", START.plus(Duration.ofHours(1)))).isTrue();
    // The next tick a second later must not fire a hundred backdated reports.
    assertThat(cadence.due("VEH-0001", START.plus(Duration.ofHours(1)).plusSeconds(1))).isFalse();
  }

  @Test
  @DisplayName("forgets trucks that have left the fleet")
  void prunesRetiredKeys() {
    Cadence cadence = new Cadence(Duration.ofSeconds(30), new Random(1));
    cadence.due("VEH-0001", START);
    cadence.due("VEH-0002", START);
    assertThat(cadence.tracked()).isEqualTo(2);

    cadence.retainOnly(Set.of("VEH-0002"));

    assertThat(cadence.tracked()).isEqualTo(1);
  }

  @Test
  @DisplayName("rejects an interval that would fire forever")
  void rejectsNonPositiveInterval() {
    assertThatThrownBy(() -> new Cadence(Duration.ZERO, new Random(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
