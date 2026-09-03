package com.fleettracking.tracking;

import com.fleettracking.tracking.consume.PartitionGuard;
import com.fleettracking.tracking.consume.PositionConsumer;
import com.fleettracking.tracking.consume.RecentEventIds;
import com.fleettracking.tracking.geofence.DerivedEventPublisher;
import com.fleettracking.tracking.geofence.GeofenceService;
import com.fleettracking.tracking.geofence.GeofenceStateStore;
import com.fleettracking.tracking.geofence.Geofencer;
import com.fleettracking.tracking.itinerary.ItineraryStore;
import com.fleettracking.tracking.consume.TrackingDeadLetters;
import com.fleettracking.tracking.store.PositionStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Wires the processor.
 *
 * <p>Explicit {@code @Bean} methods rather than component scanning, for the same reason the gateway
 * uses them: one file shows the whole path a message takes, which is what you want in front of you
 * when something arrives on a topic and never reaches the database.
 */
@Configuration
@EnableConfigurationProperties(TrackingProperties.class)
public class TrackingConfig {

  private static final Logger log = LoggerFactory.getLogger(TrackingConfig.class);

  /** Wall-clock time, injected so a test can fix it. Used only for {@code updatedAt}. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * The store, with its collections created before anything can insert into them.
   *
   * <p>Creating them here rather than in a startup listener is deliberate: a listener could fire
   * after the Kafka listener container has already started polling, and the first insert would
   * create the history as an ordinary collection. Doing it while the bean is being built means it
   * has happened before anything that depends on the store exists.
   *
   * <p>The database name is logged for the reason it cost an hour in S8: Spring Boot 4 renamed the
   * MongoDB connection properties, the old names bind to nothing at all, and the fallback default
   * is a database called {@code test} on port 27017 — which on this machine is a real, unrelated
   * MongoDB that accepts the connection and answers every query.
   */
  @Bean
  public PositionStore positionStore(
      MongoOperations mongo, MongoDatabaseFactory factory, Clock clock) {
    PositionStore store = new PositionStore(mongo, clock);
    store.ensureCollections();
    log.info(
        "position store ready in MongoDB database '{}': {} measurements, {} shipments tracked",
        factory.getMongoDatabase().getName(),
        store.historyCount(),
        store.trackedShipments());
    return store;
  }

  /**
   * The redelivery guard, which is also the container's rebalance listener.
   *
   * <p>Nothing here registers it as one. Spring Boot's Kafka auto-configuration takes any
   * {@code ConsumerAwareRebalanceListener} it finds in the context and hands it to every listener
   * container, which is how this bean comes to be told the moment a partition is assigned to this
   * instance — the moment at which redelivered records can appear.
   */
  @Bean
  public PartitionGuard partitionGuard(RecentEventIds recentEventIds) {
    return new PartitionGuard(recentEventIds);
  }

  /**
   * The bounded set of recently-seen event ids, which is what makes a message the source sent twice
   * get stored once.
   *
   * <p>Separate from the guard above because the two answer different questions — see
   * {@code PositionConsumer}. Sized per partition, so the memory cost is the capacity times the
   * number of partitions this instance is assigned, not times the topic's total.
   */
  @Bean
  public RecentEventIds recentEventIds(TrackingProperties properties) {
    return new RecentEventIds(properties.recentEventIds());
  }

  @Bean
  public TrackingDeadLetters trackingDeadLetters(
      KafkaTemplate<String, String> kafka, TrackingProperties properties) {
    return new TrackingDeadLetters(kafka, properties.sendTimeout().toMillis());
  }

  /**
   * The scheduled stops, read from reference data seeded by {@code scripts/seed-itinerary.sh}.
   *
   * <p>The count is logged at startup for the reason S8 established: a service that silently found
   * no reference data looks identical to one that is working, right up until nobody ever arrives
   * anywhere.
   */
  @Bean
  public ItineraryStore itineraryStore(MongoOperations mongo) {
    ItineraryStore store = new ItineraryStore(mongo);
    long planned = store.count();
    if (planned == 0) {
      log.warn(
          "no itineraries found: geofencing will announce nothing. Run ./scripts/seed-itinerary.sh");
    } else {
      log.info("itineraries: {} shipments have a plan", planned);
    }
    return store;
  }

  @Bean
  public GeofenceStateStore geofenceStateStore(MongoOperations mongo) {
    GeofenceStateStore store = new GeofenceStateStore(mongo);
    store.ensureIndexes();
    return store;
  }

  @Bean
  public Geofencer geofencer(TrackingProperties properties) {
    return new Geofencer(properties.dwellThreshold());
  }

  @Bean
  public DerivedEventPublisher derivedEventPublisher(
      KafkaTemplate<String, String> kafka, TrackingProperties properties) {
    return new DerivedEventPublisher(kafka, properties.sendTimeout().toMillis());
  }

  @Bean
  public GeofenceService geofenceService(
      ItineraryStore itineraries,
      GeofenceStateStore states,
      Geofencer geofencer,
      DerivedEventPublisher publisher) {
    return new GeofenceService(itineraries, states, geofencer, publisher);
  }

  @Bean
  public PositionConsumer positionConsumer(
      PositionStore store,
      PartitionGuard guard,
      RecentEventIds recentEventIds,
      GeofenceService geofence,
      TrackingDeadLetters deadLetters) {
    return new PositionConsumer(store, guard, recentEventIds, geofence, deadLetters);
  }

  /**
   * The error handler, which must never give up.
   *
   * <p>Spring's {@link DefaultErrorHandler}, unconfigured, retries a failing record ten times in
   * quick succession and then <b>logs it and moves on</b>. For a request-handling service that is a
   * reasonable default — one bad request should not stop the others. For this one it is silent data
   * loss: the failure that will actually happen in production is MongoDB being briefly unavailable,
   * during which ten rapid attempts take a few milliseconds, and then every position event for as
   * long as the outage lasts is discarded with a log line nobody is reading. Retrying for ever
   * instead means an outage stalls the affected partitions and they resume exactly where they
   * stopped, which is the behaviour a durable history requires.
   *
   * <p>This does not risk the poison-pill stall that unbounded retries usually invite, because a
   * record that can never succeed does not reach this handler: {@code PositionConsumer} recognises
   * an unusable record itself and sets it aside on the dead-letter topic. What is left here is,
   * by construction, only the retryable kind.
   *
   * <p>Declared as a plain bean rather than by building a listener container factory. Spring Boot's
   * Kafka auto-configuration looks in the context for a {@code CommonErrorHandler} and a
   * {@code ConsumerAwareRebalanceListener} and applies whatever it finds — so the two customisations
   * this service needs are two beans, and everything else about the container still comes from the
   * {@code spring.kafka} settings rather than from a factory assembled here that would have to be
   * kept in step with them by hand.
   */
  @Bean
  public CommonErrorHandler retryForeverErrorHandler(TrackingProperties properties) {
    return new DefaultErrorHandler(
        new FixedBackOff(properties.retryBackoff().toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
  }

  /**
   * A periodic line saying what the processor has done.
   *
   * <p>A healthy consumer is completely silent, which looks exactly like a wedged one. This is the
   * cheapest way to tell them apart while watching a run, and it is what makes "the history grows
   * and the current position tracks live" something you can see rather than something you have to
   * go and query for.
   */
  @Bean
  public TrackingHeartbeat trackingHeartbeat(
      PositionConsumer consumer,
      PositionStore store,
      GeofenceService geofence,
      TrackingProperties properties) {
    return new TrackingHeartbeat(consumer, store, geofence, properties.heartbeatInterval());
  }
}
