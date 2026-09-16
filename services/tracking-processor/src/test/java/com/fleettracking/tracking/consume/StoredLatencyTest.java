package com.fleettracking.tracking.consume;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StoredLatencyTest {

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final StoredLatency latency = new StoredLatency(meters);

  private double count(String le) {
    return meters.get(StoredLatency.METRIC).tag("le", le).counter().count();
  }

  @Test
  void labelsEachBucketByItsUpperBoundInSeconds() {
    assertThat(StoredLatency.label(5)).isEqualTo("0.005");
    assertThat(StoredLatency.label(1_500)).isEqualTo("1.5");
    assertThat(StoredLatency.label(300_000)).isEqualTo("300");
    // Every bound plus the overflow, and nothing else: the load script asks for exactly these.
    assertThat(meters.get(StoredLatency.METRIC).counters())
        .hasSize(StoredLatency.BOUNDS_MILLIS.length + 1);
  }

  @Test
  void aLatencyOnABoundCountsInThatBucketAndOneJustPastItInTheNext() {
    latency.record(Duration.ofMillis(100));
    latency.record(Duration.ofMillis(101));

    assertThat(count("0.1")).isEqualTo(1.0);
    assertThat(count("0.15")).isEqualTo(1.0);
  }

  @Test
  void countsEachMeasurementOnceAndNeverForgetsIt() {
    latency.record(Duration.ofMillis(3));
    latency.record(Duration.ofMillis(3));
    latency.record(Duration.ZERO);

    assertThat(count("0.005")).isEqualTo(3.0);
    assertThat(meters.get(StoredLatency.METRIC).counters().stream().mapToDouble(c -> c.count()).sum())
        .isEqualTo(3.0);
  }

  @Test
  void anythingPastFiveMinutesIsCountedAsOverflowRatherThanLost() {
    latency.record(Duration.ofMinutes(20));

    assertThat(count("inf")).isEqualTo(1.0);
  }
}
