package com.fleettracking.simulator.emit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LatencyHistogramTest {

  private static long millis(double ms) {
    return (long) (ms * 1_000_000);
  }

  @Test
  void reportsNothingBeforeAnythingIsRecorded() {
    LatencyHistogram histogram = new LatencyHistogram();

    assertThat(histogram.count()).isZero();
    assertThat(histogram.percentile(0.99)).isEqualTo(Duration.ZERO);
    assertThat(histogram.max()).isEqualTo(Duration.ZERO);
  }

  @Test
  void findsThePercentileToWithinOneBucketAndNeverUnderstatesIt() {
    LatencyHistogram histogram = new LatencyHistogram();
    // 1.00 ms, 1.01 ms ... 100.99 ms: ten thousand samples, one every ten microseconds.
    for (int i = 0; i < 10_000; i++) {
      histogram.record(millis(1.0) + i * 10_000L);
    }

    // The 9,900th sample is 99.99 ms, which falls in the bucket ending at 100.0 ms.
    assertThat(histogram.percentile(0.99)).isEqualTo(Duration.ofNanos(millis(100.0)));
    // The 5,000th is 50.99 ms, in the bucket ending at 51.0 ms.
    assertThat(histogram.percentile(0.50)).isEqualTo(Duration.ofNanos(millis(51.0)));
    assertThat(histogram.max()).isEqualTo(Duration.ofNanos(millis(1.0) + 9_999 * 10_000L));
    assertThat(histogram.count()).isEqualTo(10_000);
  }

  @Test
  void aSingleSlowRequestIsThePercentileOnlyWhenItIsInTheTail() {
    LatencyHistogram histogram = new LatencyHistogram();
    for (int i = 0; i < 99; i++) {
      histogram.record(millis(2.0));
    }
    histogram.record(millis(900.0));

    // 99 of 100 were 2 ms, so p99 is 2 ms; the slow one is the max, and the p100.
    assertThat(histogram.percentile(0.99)).isEqualTo(Duration.ofNanos(millis(2.1)));
    assertThat(histogram.percentile(1.0)).isEqualTo(Duration.ofNanos(millis(900.1)));
    assertThat(histogram.max()).isEqualTo(Duration.ofNanos(millis(900.0)));
  }

  @Test
  void somethingSlowerThanEveryBucketIsStillCountedAndReportedAsTheMax() {
    LatencyHistogram histogram = new LatencyHistogram();
    histogram.record(Duration.ofSeconds(45).toNanos());

    assertThat(histogram.count()).isEqualTo(1);
    assertThat(histogram.percentile(0.99)).isEqualTo(Duration.ofSeconds(45));
  }
}
