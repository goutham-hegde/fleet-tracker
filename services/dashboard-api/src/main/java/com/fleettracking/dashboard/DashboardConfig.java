package com.fleettracking.dashboard;

import com.fleettracking.dashboard.read.FleetAssembler;
import com.fleettracking.dashboard.read.FleetReader;
import com.fleettracking.dashboard.read.FleetService;
import com.fleettracking.dashboard.read.RemainingDistance;
import com.fleettracking.dashboard.stream.DashboardConsumers;
import com.fleettracking.dashboard.stream.StreamBroadcaster;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the service.
 *
 * <p>Explicit beans rather than component scanning, matching the other four: one file shows
 * everything a request touches, which is what you want in front of you when a map is empty and
 * nobody knows which of five components is the reason.
 */
@Configuration
@EnableConfigurationProperties(DashboardProperties.class)
public class DashboardConfig {

  private static final Logger log = LoggerFactory.getLogger(DashboardConfig.class);

  /** Wall-clock time, injected so a test can fix it. Staleness is measured against it. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * The reads, and the loudest startup line in this service.
   *
   * <p>The database name is logged for the reason that cost a session in S8: Spring Boot 4 renamed
   * the MongoDB connection properties, the old names bind to nothing at all, and the fallback is a
   * database called {@code test} on port 27017 — which on this machine is a real, unrelated MongoDB
   * that accepts the connection and answers every query with nothing. For a read-only service that
   * failure is particularly quiet: the map simply has no trucks on it, which looks exactly like a
   * simulator that is not running.
   */
  @Bean
  public FleetReader fleetReader(
      MongoOperations mongo, MongoDatabaseFactory factory, DashboardProperties properties) {
    FleetReader reader = new FleetReader(mongo, properties.fleetLimit());
    long tracked = reader.trackedCount();
    log.info(
        "reading MongoDB database '{}': {} shipments have a current position, {} exceptions open",
        factory.getMongoDatabase().getName(),
        tracked,
        reader.openIncidentCount());
    if (tracked == 0) {
      log.warn(
          "no shipments have a position: the map will be empty. Is the tracking processor running,"
              + " and has the simulator posted to the gateway?");
    }
    return reader;
  }

  @Bean
  public RemainingDistance remainingDistance(DashboardProperties properties) {
    log.info(
        "distances billed against a road {}x the straight line, measured per request",
        properties.roadCircuity());
    return new RemainingDistance(properties.roadCircuity());
  }

  @Bean
  public FleetAssembler fleetAssembler(RemainingDistance distances, Clock clock) {
    return new FleetAssembler(distances, clock);
  }

  @Bean
  public FleetService fleetService(
      FleetReader reader, FleetAssembler assembler, DashboardProperties properties) {
    return new FleetService(reader, assembler, properties.trackLimit());
  }

  /**
   * The fan-out.
   *
   * <p>Declared as a bean with a {@code destroyMethod} so that a shutdown completes every open
   * connection rather than dropping the sockets: a browser whose stream ends cleanly reconnects
   * once, while one whose connection is severed reconnects immediately and repeatedly against a
   * service that is going away.
   */
  @Bean(destroyMethod = "close")
  public StreamBroadcaster streamBroadcaster(DashboardProperties properties) {
    DashboardProperties.Stream stream = properties.stream();
    log.info(
        "stream: up to {} viewers, {} pending updates each, keep-alive every {}",
        stream.maxSubscribers(),
        stream.queueCapacity(),
        stream.keepAlive());
    return new StreamBroadcaster(
        stream.queueCapacity(), stream.maxSubscribers(), stream.keepAlive());
  }

  @Bean
  public DashboardConsumers dashboardConsumers(
      StreamBroadcaster broadcaster, DashboardProperties properties) {
    log.info(
        "stream: forwarding at most one position per shipment every {}",
        properties.stream().sampleInterval());
    return new DashboardConsumers(broadcaster, properties.stream().sampleInterval());
  }

  /**
   * The error handler.
   *
   * <p>Deliberately different from the tracking processor's and the exception service's, which retry
   * for ever because a record they failed to handle is data that would otherwise be lost. Nothing is
   * lost here: this service stores nothing, and a record it failed to forward is a frame of a map
   * that has already been superseded by the next position of the same truck. Retrying it for ever
   * would stall the partition and freeze every marker on it to preserve a single stale frame.
   *
   * <p>So a failure is logged and the record is passed over. This is the one consumer in the
   * platform where giving up is the right answer, and it is the read-only, ephemeral nature of the
   * work that makes it so.
   */
  @Bean
  public CommonErrorHandler skipAndCarryOnErrorHandler() {
    DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(0L, 0L));
    handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
    return handler;
  }

  @Bean
  public DashboardHeartbeat dashboardHeartbeat(
      DashboardConsumers consumers, StreamBroadcaster broadcaster, DashboardProperties properties) {
    return new DashboardHeartbeat(consumers, broadcaster, properties.heartbeatInterval());
  }

  /**
   * Which browsers may call this service.
   *
   * <p>Needed because of where the dashboard is served from rather than because of anything this
   * service does. In development the React app runs on Vite's own port and this API answers on
   * another, and a browser treats a different port on the same host as a different origin — so
   * every fetch is cross-origin and is blocked unless the server says otherwise. The failure is
   * distinctive and worth recognising: the request reaches this service and is answered normally,
   * and the browser then refuses to hand the response to the page. Server logs show success while
   * the console shows a CORS error.
   *
   * <p>Origins are configured rather than wildcarded, and the list is empty by default. A read-only
   * API of a logistics fleet is not public data, and {@code *} in a manifest that is going to be
   * deployed to a cloud in M8 is the kind of development convenience that survives into production
   * because nothing ever fails because of it.
   */
  @Bean
  public WebMvcConfigurer dashboardCors(DashboardProperties properties) {
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        if (properties.corsOrigins().isEmpty()) {
          log.info("CORS: no origins allowed. Set fleet.dashboard.cors-origins for a browser client");
          return;
        }
        log.info("CORS: allowing {}", properties.corsOrigins());
        registry
            .addMapping("/api/**")
            .allowedOrigins(properties.corsOrigins().toArray(String[]::new))
            .allowedMethods("GET");
      }
    };
  }
}
