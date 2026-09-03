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
 * @param recentEventIds how many recently-seen event ids to remember per partition, so that a
 *     message the source sent twice is stored once. The duplicate this defends against is the
 *     mobile app resending a backlogged message seconds later, so the window only has to span a
 *     burst; the cost of a larger one is memory, and the cost of too small a one is an extra
 *     measurement in an append-only history
 */
@ConfigurationProperties(prefix = "fleet.tracking")
public record TrackingProperties(
    Duration retryBackoff,
    Duration sendTimeout,
    Duration heartbeatInterval,
    Integer recentEventIds) {

  public TrackingProperties {
    retryBackoff = retryBackoff == null ? Duration.ofSeconds(5) : retryBackoff;
    sendTimeout = sendTimeout == null ? Duration.ofSeconds(15) : sendTimeout;
    heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(30) : heartbeatInterval;
    recentEventIds = recentEventIds == null ? 2_000 : recentEventIds;
  }
}
