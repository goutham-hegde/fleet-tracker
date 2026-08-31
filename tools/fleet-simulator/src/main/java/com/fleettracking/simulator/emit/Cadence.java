package com.fleettracking.simulator.emit;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Decides when each device is next due to report.
 *
 * <p>The tick loop is uniform — every truck is advanced every tick — but the feeds are not. A
 * telematics unit reports every half minute, a reefer probe every few minutes, and a driver's phone
 * only when it has something to say. Cadence is the small piece of state that turns one uniform
 * clock into several independent reporting rates, measured in <em>simulated</em> time so that the
 * rates stay honest at any {@code time-scale}.
 *
 * <h2>Why the phase is random</h2>
 *
 * <p>Devices are not synchronised in reality: eight trucks do not all report on the same second
 * merely because they all report every thirty. Giving each key a random offset into its first
 * interval spreads them out, which matters for two concrete reasons. A captured fixture where every
 * message shares a timestamp would let a normalizer bug that depends on ordering hide, and a
 * downstream consumer that batches by time would see one spike per interval and idle in between —
 * a load shape the platform will never actually meet.
 *
 * <p>Not thread-safe; it is stepped from the tick thread like everything else in the simulator.
 */
public final class Cadence {

  private final Duration interval;
  private final RandomGenerator random;
  private final Map<String, Instant> nextDue = new HashMap<>();

  public Cadence(Duration interval, RandomGenerator random) {
    if (interval == null || interval.isNegative() || interval.isZero()) {
      throw new IllegalArgumentException("interval must be positive: " + interval);
    }
    this.interval = interval;
    this.random = random;
  }

  /**
   * Whether this key should report now, advancing its schedule if so.
   *
   * <p>A key seen for the first time is scheduled a random fraction of an interval ahead and
   * reports on that later tick rather than immediately.
   */
  public boolean due(String key, Instant now) {
    Instant due = nextDue.get(key);
    if (due == null) {
      nextDue.put(key, now.plusNanos((long) (random.nextDouble() * interval.toNanos())));
      return false;
    }
    if (now.isBefore(due)) {
      return false;
    }
    // Advance from the scheduled time, not from now, so a slow tick does not permanently shift the
    // reporting rate. Skip whole intervals if simulated time has jumped past several of them --
    // which it does at high time-scale, where one tick can cover more than one reporting interval.
    Instant next = due.plus(interval);
    while (!next.isAfter(now)) {
      next = next.plus(interval);
    }
    nextDue.put(key, next);
    return true;
  }

  /**
   * Forgets keys not in the given set.
   *
   * <p>Trucks are replaced by fresh ones with new ids when they finish their routes, so without
   * this a long demo run accumulates one dead entry per completed truck forever.
   */
  public void retainOnly(Set<String> keys) {
    nextDue.keySet().retainAll(keys);
  }

  /** How many keys are currently scheduled. Test seam. */
  public int tracked() {
    return nextDue.size();
  }
}
