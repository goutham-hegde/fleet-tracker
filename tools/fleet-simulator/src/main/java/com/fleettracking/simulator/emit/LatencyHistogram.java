package com.fleettracking.simulator.emit;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Request round-trip times, counted into fixed buckets.
 *
 * <p>A load run at a few thousand requests a second for ten minutes is millions of samples, and
 * keeping each one to sort later would cost the simulator more memory than its pod is given. So a
 * sample is not kept at all: it increments the counter of the tenth-of-a-millisecond bucket it
 * falls in, and a percentile is read by walking the buckets until enough samples have been passed.
 * Memory is fixed at a few megabytes whatever the run's length, recording is one atomic increment,
 * and the answer is exact to within one bucket.
 *
 * <p>Thirty seconds of buckets, which is six times the sink's default request timeout: nothing that
 * completes can land past the end. Anything that somehow does is counted in a final overflow bucket
 * and still reported through {@link #max()}.
 *
 * <p>Safe to record into from several threads at once; the sink's workers share one.
 */
final class LatencyHistogram {

  static final long RESOLUTION_NANOS = 100_000;
  private static final int BUCKETS = 300_000;

  private final AtomicLongArray counts = new AtomicLongArray(BUCKETS + 1);
  private final AtomicLong count = new AtomicLong();
  private final AtomicLong maxNanos = new AtomicLong();

  void record(long nanos) {
    long clamped = Math.max(0, nanos);
    counts.incrementAndGet((int) Math.min(BUCKETS, clamped / RESOLUTION_NANOS));
    count.incrementAndGet();
    maxNanos.accumulateAndGet(clamped, Math::max);
  }

  long count() {
    return count.get();
  }

  Duration max() {
    return Duration.ofNanos(maxNanos.get());
  }

  /**
   * The smallest bucket edge that at least this share of the samples fall at or below.
   *
   * <p>The upper edge rather than the lower one, so the figure errs towards reporting a request as
   * slower than it was, never faster. Zero when nothing has been recorded.
   *
   * @param quantile between 0 and 1: 0.99 is p99
   */
  Duration percentile(double quantile) {
    long total = count.get();
    if (total == 0) {
      return Duration.ZERO;
    }
    long rank = Math.max(1, (long) Math.ceil(quantile * total));
    long seen = 0;
    for (int bucket = 0; bucket < BUCKETS; bucket++) {
      seen += counts.get(bucket);
      if (seen >= rank) {
        return Duration.ofNanos((bucket + 1) * RESOLUTION_NANOS);
      }
    }
    return max();
  }
}
