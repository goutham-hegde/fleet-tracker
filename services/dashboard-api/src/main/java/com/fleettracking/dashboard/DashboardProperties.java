package com.fleettracking.dashboard;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings this service invents. Kafka's and MongoDB's own stay where Spring Boot defines them.
 *
 * @param roadCircuity how much longer the road is than the straight line between two points. Must
 *     be the same 1.30 the tracking processor estimates against and the simulator drives against:
 *     a dashboard measuring the crow-fly distance beside an ETA computed over a road thirty per
 *     cent longer would show the two disagreeing for the whole of every journey
 * @param fleetLimit the most markers one fleet request will return, newest news first. A screen,
 *     not an export
 * @param trackLimit how many stored positions to send behind a marker as its trail. Long enough to
 *     show where a truck has been on this leg, short enough that a detail panel opens immediately
 * @param heartbeatInterval how often to log what this service has served and streamed. The normal
 *     state of a read API is silence, which looks identical to being wedged
 * @param corsOrigins where a browser is allowed to call this from. The dashboard is served by Vite
 *     on another port during development, which makes every request cross-origin — see
 *     {@code DashboardConfig}
 * @param stream how the live stream behaves under load
 */
@ConfigurationProperties(prefix = "fleet.dashboard")
public record DashboardProperties(
    Double roadCircuity,
    Integer fleetLimit,
    Integer trackLimit,
    Duration heartbeatInterval,
    List<String> corsOrigins,
    Stream stream) {

  public DashboardProperties {
    roadCircuity = roadCircuity == null ? 1.30 : roadCircuity;
    fleetLimit = fleetLimit == null ? 500 : fleetLimit;
    trackLimit = trackLimit == null ? 300 : trackLimit;
    heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(30) : heartbeatInterval;
    corsOrigins = corsOrigins == null ? List.of() : corsOrigins;
    stream = stream == null ? new Stream(null, null, null, null) : stream;
  }

  /**
   * How the live stream behaves when a viewer cannot keep up.
   *
   * @param queueCapacity how many pending updates one subscriber may fall behind by before the
   *     oldest are dropped. This is the setting that decides what a slow browser costs everybody
   *     else: unbounded, one laptop that has gone to sleep would grow a queue until the service ran
   *     out of memory, and a queue that blocked instead would stall the Kafka listener thread
   *     filling it — which stops the consumer polling, which drops the whole service out of its
   *     consumer group. A bounded queue that discards is the only option that fails locally
   * @param keepAlive how often to send a comment down an idle connection. Nothing between a browser
   *     and this service will hold a silent socket open indefinitely — proxies and load balancers
   *     close them — and a stream that is correct but quiet is exactly the case that looks broken
   * @param maxSubscribers how many viewers to serve at once. A demonstration limit rather than a
   *     capacity one; refusing the six hundredth connection is better than degrading the first
   * @param sampleInterval the shortest gap between two position updates for the same shipment. A
   *     telematics unit reports every ten seconds of simulated time, which at a time scale of three
   *     hundred is thirty times a second per truck — far more than a map can draw and far more than
   *     an eye can see. Thinning per shipment rather than globally means a quiet truck still gets
   *     through immediately; set it to zero to forward everything
   */
  public record Stream(
      Integer queueCapacity, Duration keepAlive, Integer maxSubscribers, Duration sampleInterval) {

    public Stream {
      queueCapacity = queueCapacity == null ? 512 : queueCapacity;
      keepAlive = keepAlive == null ? Duration.ofSeconds(15) : keepAlive;
      maxSubscribers = maxSubscribers == null ? 64 : maxSubscribers;
      sampleInterval = sampleInterval == null ? Duration.ofMillis(500) : sampleInterval;
    }
  }
}
