package com.fleettracking.tracking.consume;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Locale;

/**
 * How long positions took to become durable, counted into fixed buckets that only ever go up.
 *
 * <p>Each measurement stored increments exactly one counter: the one for the smallest bound at or
 * above its latency, or {@code inf} past the last. The counters are exposed as
 * {@code fleet.position.stored.latency} with an {@code le} tag ("less than or equal to", in
 * seconds), and {@code scripts/load-test.sh} reads every pod's counters before a load step and
 * after its backlog has drained. The difference, summed over pods, is the exact distribution of
 * every position that step offered.
 *
 * <h2>Why not a timer with percentiles</h2>
 *
 * <p>That was the first version, and the first load sweep exposed it. A Micrometer timer computes
 * its percentiles over a sliding window, but the window only moves when something is recorded: a
 * pod that stops receiving work goes on reporting the p99 of its last busy minute indefinitely. A
 * pod that had worked through a backlog then went quiet reported 53 seconds through the entire
 * next step, in which nothing waited longer than a few. And a percentile per pod cannot be combined
 * into one for the fleet. Counters have neither problem: they never decay, so they are never stale,
 * and counts from several pods simply add.
 *
 * <p>The cost is resolution. A percentile read from these is the upper bound of the bucket it falls
 * in — "no more than 150 ms" — and consecutive bounds are about 1.5 times apart, so that is the
 * precision of the answer. It errs towards reporting a position as slower than it was.
 */
public final class StoredLatency {

  static final String METRIC = "fleet.position.stored.latency";

  /** Upper bounds, in milliseconds. Roughly 1.5 times apart, from "instant" to "five minutes". */
  static final long[] BOUNDS_MILLIS = {
    5, 10, 15, 20, 30, 50, 75, 100, 150, 200, 300, 500, 750,
    1_000, 1_500, 2_000, 3_000, 5_000, 7_500, 10_000, 15_000, 20_000, 30_000, 60_000, 120_000, 300_000
  };

  private final Counter[] buckets = new Counter[BOUNDS_MILLIS.length + 1];

  public StoredLatency(MeterRegistry meters) {
    for (int i = 0; i < BOUNDS_MILLIS.length; i++) {
      buckets[i] = bucket(meters, label(BOUNDS_MILLIS[i]));
    }
    buckets[BOUNDS_MILLIS.length] = bucket(meters, "inf");
  }

  private static Counter bucket(MeterRegistry meters, String le) {
    return Counter.builder(METRIC)
        .description("Positions stored, by time from the gateway receiving them to being stored")
        .tag("le", le)
        .register(meters);
  }

  /** Seconds, as the tag value: {@code 0.005}, {@code 1.5}, {@code 300}. */
  static String label(long millis) {
    return java.math.BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString().toLowerCase(Locale.ROOT);
  }

  public void record(Duration latency) {
    long millis = Math.max(0, latency.toMillis());
    int bucket = BOUNDS_MILLIS.length;
    for (int i = 0; i < BOUNDS_MILLIS.length; i++) {
      if (millis <= BOUNDS_MILLIS[i]) {
        bucket = i;
        break;
      }
    }
    buckets[bucket].increment();
  }
}
