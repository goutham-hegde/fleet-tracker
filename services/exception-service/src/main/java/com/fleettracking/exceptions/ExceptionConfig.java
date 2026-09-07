package com.fleettracking.exceptions;

import com.fleettracking.exceptions.consume.ExceptionConsumers;
import com.fleettracking.exceptions.consume.ExceptionDeadLetters;
import com.fleettracking.exceptions.incident.ExceptionPublisher;
import com.fleettracking.exceptions.incident.IncidentService;
import com.fleettracking.exceptions.incident.IncidentStore;
import com.fleettracking.exceptions.manifest.ManifestLookup;
import com.fleettracking.exceptions.manifest.MongoManifestLookup;
import com.fleettracking.exceptions.rule.ConditionStateStore;
import com.fleettracking.exceptions.rule.LateArrivalRule;
import com.fleettracking.exceptions.rule.RouteDeviationRule;
import com.fleettracking.exceptions.rule.RuleService;
import com.fleettracking.exceptions.rule.SignalLossRule;
import com.fleettracking.exceptions.rule.TemperatureExcursionRule;
import com.fleettracking.exceptions.rule.UnplannedStopRule;
import com.fleettracking.reference.ItineraryStore;
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
 * Wires the service.
 *
 * <p>Explicit beans rather than component scanning, matching the gateway and the tracking processor:
 * one file shows the whole path an event takes, which is what you want in front of you when a truck
 * has clearly broken down and no exception was raised.
 */
@Configuration
@EnableConfigurationProperties(ExceptionProperties.class)
public class ExceptionConfig {

  private static final Logger log = LoggerFactory.getLogger(ExceptionConfig.class);

  /** Wall-clock time, injected so a test can fix it. The signal-loss rule needs both clocks. */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * The incident collection, with its indexes created before anything can write to it.
   *
   * <p>The database name is logged for the reason that cost an hour in S8: Spring Boot 4 renamed the
   * MongoDB connection properties, the old names bind to nothing at all, and the fallback default is
   * a database called {@code test} on port 27017 — which on this machine is a real, unrelated
   * MongoDB that accepts the connection and answers every query.
   */
  @Bean
  public IncidentStore incidentStore(MongoOperations mongo, MongoDatabaseFactory factory) {
    IncidentStore store = new IncidentStore(mongo);
    store.ensureIndexes();
    log.info(
        "incident store ready in MongoDB database '{}': {} open, {} on record",
        factory.getMongoDatabase().getName(),
        store.openCount(),
        store.totalCount());
    return store;
  }

  /**
   * The manifest read, and the loudest startup warning in this service.
   *
   * <p>Two of the five rules are silent without manifests, and silent is exactly what a working
   * exception service looks like. An unseeded database therefore produces a system that appears
   * perfectly healthy while enforcing nothing a customer agreed to — the same failure S8 found in
   * the gateway, arriving through a different door.
   */
  @Bean
  public ManifestLookup manifestLookup(MongoOperations mongo) {
    ManifestLookup lookup = new MongoManifestLookup(mongo);
    long filed = lookup.count();
    if (filed == 0) {
      log.warn(
          "no manifests on file: temperature bands and delivery windows cannot be enforced. "
              + "Run ./scripts/seed-manifests.sh");
    } else {
      log.info("manifests: {} loads have paperwork on file", filed);
    }
    return lookup;
  }

  /** The scheduled stops, shared with the tracking processor and seeded by the same script. */
  @Bean
  public ItineraryStore itineraryStore(MongoOperations mongo) {
    ItineraryStore store = new ItineraryStore(mongo);
    long planned = store.count();
    if (planned == 0) {
      log.warn(
          "no itineraries found: unplanned stops and route deviations cannot be judged. "
              + "Run ./scripts/seed-itinerary.sh");
    } else {
      log.info("itineraries: {} shipments have a plan", planned);
    }
    return store;
  }

  @Bean
  public ConditionStateStore conditionStateStore(MongoOperations mongo) {
    // Three rules times a few thousand active shipments. Bounded, and an evicted state is in
    // MongoDB rather than lost.
    return new ConditionStateStore(mongo, 8_192);
  }

  @Bean
  public ExceptionPublisher exceptionPublisher(
      KafkaTemplate<String, String> kafka, ExceptionProperties properties) {
    return new ExceptionPublisher(kafka, properties.sendTimeout().toMillis());
  }

  @Bean
  public ExceptionDeadLetters exceptionDeadLetters(
      KafkaTemplate<String, String> kafka, ExceptionProperties properties) {
    return new ExceptionDeadLetters(kafka, properties.sendTimeout().toMillis());
  }

  @Bean
  public IncidentService incidentService(IncidentStore store, ExceptionPublisher publisher) {
    return new IncidentService(store, publisher);
  }

  @Bean
  public TemperatureExcursionRule temperatureExcursionRule(ExceptionProperties properties) {
    log.info(
        "temperature: default tolerance {}, setpoint fallback +/-{}C",
        properties.temperature().defaultTolerance(),
        properties.temperature().setpointToleranceCelsius());
    return new TemperatureExcursionRule(properties.temperature());
  }

  @Bean
  public UnplannedStopRule unplannedStopRule(ExceptionProperties properties) {
    log.info(
        "unplanned stop: below {} km/h for {}, more than {}x a stop's radius away",
        properties.unplannedStop().movingSpeedKph(),
        properties.unplannedStop().threshold(),
        properties.unplannedStop().stopMarginRatio());
    return new UnplannedStopRule(properties.unplannedStop());
  }

  @Bean
  public RouteDeviationRule routeDeviationRule(ExceptionProperties properties) {
    log.info(
        "route deviation: more than {} km off the planned corridor for {}",
        properties.routeDeviation().corridorKm(),
        properties.routeDeviation().threshold());
    return new RouteDeviationRule(properties.routeDeviation());
  }

  @Bean
  public LateArrivalRule lateArrivalRule(ExceptionProperties properties) {
    log.info("late arrival: {} past a booked delivery window", properties.lateArrival().grace());
    return new LateArrivalRule(properties.lateArrival());
  }

  @Bean
  public SignalLossRule signalLossRule(ExceptionProperties properties, Clock clock) {
    log.info(
        "signal loss: {} of shipment time with no events, swept every {}",
        properties.signalLoss().threshold(),
        properties.signalLoss().sweepInterval());
    return new SignalLossRule(properties.signalLoss(), clock);
  }

  @Bean
  public RuleService ruleService(
      ItineraryStore itineraries,
      ManifestLookup manifests,
      ConditionStateStore states,
      IncidentService incidents,
      TemperatureExcursionRule temperature,
      UnplannedStopRule unplannedStop,
      RouteDeviationRule routeDeviation,
      LateArrivalRule lateArrival,
      SignalLossRule signalLoss) {
    return new RuleService(
        itineraries,
        manifests,
        states,
        incidents,
        temperature,
        unplannedStop,
        routeDeviation,
        lateArrival,
        signalLoss);
  }

  @Bean
  public ExceptionConsumers exceptionConsumers(
      RuleService rules, ExceptionDeadLetters deadLetters) {
    return new ExceptionConsumers(rules, deadLetters);
  }

  /**
   * The error handler, which must never give up.
   *
   * <p>Same reasoning as the tracking processor's, and it is worth restating because the instinct to
   * bound a retry is strong. Spring's default gives up after ten fast attempts and moves on, which
   * during a brief MongoDB outage means every event for the length of the outage is discarded with a
   * log line nobody is reading — and here that means SLA breaches that silently never happened.
   *
   * <p>Safe only because a record that can never succeed does not reach this handler: the consumers
   * recognise an unusable record themselves and set it aside on the dead-letter topic. What reaches
   * here is, by construction, only the kind that might work next time.
   */
  @Bean
  public CommonErrorHandler retryForeverErrorHandler(ExceptionProperties properties) {
    return new DefaultErrorHandler(
        new FixedBackOff(properties.retryBackoff().toMillis(), FixedBackOff.UNLIMITED_ATTEMPTS));
  }

  @Bean
  public ExceptionHeartbeat exceptionHeartbeat(
      ExceptionConsumers consumers,
      IncidentService incidents,
      IncidentStore store,
      RuleService rules,
      SignalLossRule signalLoss,
      ExceptionProperties properties) {
    return new ExceptionHeartbeat(
        consumers,
        incidents,
        store,
        rules,
        signalLoss,
        properties.heartbeatInterval(),
        properties.signalLoss().sweepInterval());
  }
}
