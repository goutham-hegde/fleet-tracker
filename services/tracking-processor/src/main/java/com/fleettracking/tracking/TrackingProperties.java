package com.fleettracking.tracking;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the processor itself. Kafka's own settings stay under {@code spring.kafka}, where
 * Spring Boot already defines them; only what this service invents lives here.
 *
 * @param retryBackoff how long to wait between attempts when a record fails for a reason that might
 *     come out differently next time — the database being unavailable, most likely. The retry count
 *     is deliberately unbounded, so this interval is also the rate at which a stalled partition
 *     probes for recovery. Short enough to resume promptly, long enough not to spin
 * @param sendTimeout how long to wait for the broker to acknowledge a dead-letter write before
 *     treating it as a failure and retrying the whole record
 * @param heartbeatInterval how often to log a line saying what the processor has done. Purely
 *     operational, and worth having: the normal state of this service is complete silence, which is
 *     indistinguishable from being wedged
 * @param dwellThreshold how long a vehicle must stay on one side of a geofence boundary before
 *     the crossing is believed. Applied both ways: inwards it separates a truck that has parked
 *     from one that drove past the gate, and outwards it stops a single stray fix reading as a
 *     departure. Measured in event time, so it behaves identically under a simulator running at
 *     sixty times speed. It must be comfortably shorter than the shortest real stop -- the shortest
 *     on these lanes is thirty-five minutes -- and comfortably longer than a traffic light
 * @param recentEventIds how many recently-seen event ids to remember per partition, so that a
 *     message the source sent twice is stored once. The duplicate this defends against is the
 *     mobile app resending a backlogged message seconds later, so the window only has to span a
 *     burst; the cost of a larger one is memory, and the cost of too small a one is an extra
 *     measurement in an append-only history
 * @param eta the settings the arrival estimate invents, grouped rather than flattened because they
 *     only make sense against each other: how long to smooth for and how far a change must move
 *     before it is worth saying are two ends of the same trade-off
 */
@ConfigurationProperties(prefix = "fleet.tracking")
public record TrackingProperties(
    Duration retryBackoff,
    Duration sendTimeout,
    Duration heartbeatInterval,
    Duration dwellThreshold,
    Integer recentEventIds,
    Eta eta) {

  public TrackingProperties {
    retryBackoff = retryBackoff == null ? Duration.ofSeconds(5) : retryBackoff;
    sendTimeout = sendTimeout == null ? Duration.ofSeconds(15) : sendTimeout;
    heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(30) : heartbeatInterval;
    dwellThreshold = dwellThreshold == null ? Duration.ofMinutes(3) : dwellThreshold;
    recentEventIds = recentEventIds == null ? 2_000 : recentEventIds;
    eta = eta == null ? new Eta(null, null, null, null, null) : eta;
  }

  /**
   * How the arrival estimate behaves.
   *
   * @param publishThreshold how far the estimate must move from the last one published before a new
   *     event is emitted. This is the anti-thrash setting and it is a genuine trade-off: too small
   *     and the topic fills with revisions nobody can act on, too large and a truck that has lost an
   *     hour keeps claiming it is on time. Two minutes against a dwell threshold of three keeps the
   *     stream quiet without letting an estimate go wrong by more than the length of the stop it is
   *     predicting
   * @param speedHalfLife how quickly the learned travel speed forgets. A sample arriving one
   *     half-life after the previous one is worth half the average. Measured in event time and in
   *     time rather than in messages, so it means the same thing for a telematics unit reporting
   *     every ten seconds and a phone reporting every two minutes
   * @param nominalSpeedKph what to assume before anything has been learned — the first fix of a
   *     shipment this process has never seen. A national highway freight average; the confidence on that
   *     first estimate says plainly that it is an assumption
   * @param roadCircuity how much longer the road is than the straight line. A stated assumption of
   *     this platform, and the place a real deployment would put a routing engine. The simulator
   *     bills its trucks against exactly this ratio, so an estimate that used 1.0 here would be
   *     short by thirty per cent on every leg
   * @param cacheSize how many shipments' models to keep in memory. Bounded so that a process that
   *     has seen a hundred thousand loads holds a working set; an evicted model is in MongoDB and is
   *     read back on the shipment's next fix
   */
  public record Eta(
      Duration publishThreshold,
      Duration speedHalfLife,
      Double nominalSpeedKph,
      Double roadCircuity,
      Integer cacheSize) {

    public Eta {
      publishThreshold = publishThreshold == null ? Duration.ofMinutes(2) : publishThreshold;
      speedHalfLife = speedHalfLife == null ? Duration.ofMinutes(5) : speedHalfLife;
      nominalSpeedKph = nominalSpeedKph == null ? 60.0 : nominalSpeedKph;
      roadCircuity = roadCircuity == null ? 1.30 : roadCircuity;
      cacheSize = cacheSize == null ? 4_096 : cacheSize;
    }
  }
}
