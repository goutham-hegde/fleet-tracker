package com.fleettracking.tracking.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleettracking.events.Topics;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * The bounded set on its own.
 *
 * <p>Two of these are about the limit rather than the feature: what happens at the capacity, and
 * what happens once the capacity is exceeded. A bounded cache whose eviction is never tested is a
 * cache that is assumed to be unbounded, and the assumption only fails in production.
 */
class RecentEventIdsTest {

  private static final TopicPartition ONE = new TopicPartition(Topics.POSITION, 1);
  private static final TopicPartition TWO = new TopicPartition(Topics.POSITION, 2);

  @Test
  void recognisesTheSecondSightingOfAnId() {
    RecentEventIds recent = new RecentEventIds(10);

    assertThat(recent.isFirstSighting(ONE, "evt-1")).isTrue();
    assertThat(recent.isFirstSighting(ONE, "evt-1")).isFalse();
    assertThat(recent.isFirstSighting(ONE, "evt-1")).isFalse();
  }

  @Test
  void treatsDistinctIdsIndependently() {
    RecentEventIds recent = new RecentEventIds(10);

    assertThat(recent.isFirstSighting(ONE, "evt-1")).isTrue();
    assertThat(recent.isFirstSighting(ONE, "evt-2")).isTrue();
    assertThat(recent.rememberedFor(ONE)).isEqualTo(2);
  }

  /**
   * Partitions do not share memory. If they did, the set would be a single hot object shared by
   * every consumer thread, and the eviction of one busy partition's ids would blind another.
   */
  @Test
  void keepsPartitionsSeparate() {
    RecentEventIds recent = new RecentEventIds(10);

    assertThat(recent.isFirstSighting(ONE, "evt-1")).isTrue();
    assertThat(recent.isFirstSighting(TWO, "evt-1")).isTrue();
    assertThat(recent.rememberedFor(ONE)).isEqualTo(1);
    assertThat(recent.rememberedFor(TWO)).isEqualTo(1);
  }

  @Test
  void neverHoldsMoreThanItsCapacity() {
    RecentEventIds recent = new RecentEventIds(50);

    for (int i = 0; i < 500; i++) {
      recent.isFirstSighting(ONE, "evt-" + i);
    }

    assertThat(recent.rememberedFor(ONE)).isEqualTo(50);
  }

  /**
   * The documented limit, asserted rather than described: a duplicate whose copies are separated by
   * more than the window is not recognised, and would be stored twice. Writing it down as a test is
   * what stops it being rediscovered later as a defect.
   */
  @Test
  void forgetsAnIdPushedOutByNewerOnes() {
    RecentEventIds recent = new RecentEventIds(3);

    recent.isFirstSighting(ONE, "evt-old");
    recent.isFirstSighting(ONE, "evt-1");
    recent.isFirstSighting(ONE, "evt-2");
    recent.isFirstSighting(ONE, "evt-3");

    assertThat(recent.isFirstSighting(ONE, "evt-old")).isTrue();
  }

  /** An id seen again is refreshed, so a repeatedly-seen id is not the one evicted. */
  @Test
  void keepsAnIdThatIsStillBeingSeen() {
    RecentEventIds recent = new RecentEventIds(3);

    recent.isFirstSighting(ONE, "evt-busy");
    recent.isFirstSighting(ONE, "evt-1");
    recent.isFirstSighting(ONE, "evt-busy");
    recent.isFirstSighting(ONE, "evt-2");
    recent.isFirstSighting(ONE, "evt-3");

    assertThat(recent.isFirstSighting(ONE, "evt-busy")).isFalse();
  }

  @Test
  void forgettingAPartitionDropsEverythingItHeld() {
    RecentEventIds recent = new RecentEventIds(10);
    recent.isFirstSighting(ONE, "evt-1");
    recent.isFirstSighting(TWO, "evt-1");

    recent.forget(ONE);

    assertThat(recent.rememberedFor(ONE)).isZero();
    assertThat(recent.isFirstSighting(ONE, "evt-1")).isTrue();
    // The other partition is untouched.
    assertThat(recent.isFirstSighting(TWO, "evt-1")).isFalse();
  }

  @Test
  void forgettingAPartitionItNeverHeldIsHarmless() {
    RecentEventIds recent = new RecentEventIds(10);

    recent.forget(ONE);

    assertThat(recent.rememberedFor(ONE)).isZero();
  }

  @Test
  void refusesACapacityThatCouldRememberNothing() {
    assertThatThrownBy(() -> new RecentEventIds(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capacity");
  }
}
