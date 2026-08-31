package com.fleettracking.simulator.emit;

import com.fleettracking.simulator.SimulatorProperties;
import com.fleettracking.simulator.fault.FaultInjectingSink;
import com.fleettracking.simulator.fault.FaultProfile;
import com.fleettracking.simulator.fault.FaultProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the emission chain: which sinks exist, and which feeds are switched on.
 *
 * <p>Written as explicit {@code @Bean} methods rather than by scattering {@code @Component} across
 * the sinks, because the order things wrap in is the design. One place shows the whole path a
 * message takes — emitter, then sink, then console and disk — and there is no bean-scanning puzzle
 * to solve when a message fails to appear somewhere.
 *
 * <h2>Seeding</h2>
 *
 * <p>Each feed gets its own generator derived from the master seed, for the same reason each truck
 * does in S4: shared randomness couples things that should be independent. With one generator per
 * feed, switching the mobile app off does not change a single byte of what telematics emits, and a
 * captured fixture set can be regenerated exactly. With a shared one, disabling any feed reshuffles
 * every other feed's output and every fixture changes.
 */
@Configuration
@EnableConfigurationProperties({EmissionProperties.class, FaultProperties.class})
public class EmissionConfig {

  /**
   * Where every emitted message goes.
   *
   * <p>Closed on shutdown so the capture files are flushed; a fixture file truncated mid-line
   * because the JVM exited is a fixture that fails to parse.
   */
  @Bean(destroyMethod = "close")
  public MessageSink messageSink(
      EmissionProperties properties, FaultProperties faults, SimulatorProperties simulator) {
    List<MessageSink> sinks = new ArrayList<>();
    if (properties.logging()) {
      sinks.add(new LoggingMessageSink(properties.logSummaryEvery()));
    }
    if (properties.capturing()) {
      sinks.add(
          new FileMessageSink(
              Path.of(properties.captureDir()),
              properties.captureMaxPerFeed(),
              properties.captureMaxInterchanges()));
    }

    // Transport faults wrap the real sinks rather than sitting beside them, so what is captured to
    // disk is what the platform would actually receive -- dropped messages absent, duplicates and
    // corruption present.
    return new FaultInjectingSink(
        new CompositeMessageSink(sinks),
        new FaultProfile(faults, feedRandom(simulator.seed(), "transport")));
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "fleet.simulator.emit.telematics",
      name = "enabled",
      matchIfMissing = true)
  public TelematicsEmitter telematicsEmitter(
      EmissionProperties properties,
      FaultProperties faults,
      SimulatorProperties simulator,
      MessageSink sink) {
    return new TelematicsEmitter(
        properties.telematics(),
        sink,
        feedRandom(simulator.seed(), "telematics"),
        new FaultProfile(faults, feedRandom(simulator.seed(), "telematics-gps")));
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "fleet.simulator.emit.mobile",
      name = "enabled",
      matchIfMissing = true)
  public MobileAppEmitter mobileAppEmitter(
      EmissionProperties properties,
      FaultProperties faults,
      SimulatorProperties simulator,
      MessageSink sink) {
    return new MobileAppEmitter(
        properties.mobile(),
        sink,
        feedRandom(simulator.seed(), "mobile"),
        // Its own generator: two receivers on one truck do not share a GPS error.
        new FaultProfile(faults, feedRandom(simulator.seed(), "mobile-gps")));
  }

  /** The only feed with no randomness in it: a back office is late, but it is late predictably. */
  @Bean
  @ConditionalOnProperty(
      prefix = "fleet.simulator.emit.edi",
      name = "enabled",
      matchIfMissing = true)
  public Edi214Emitter edi214Emitter(EmissionProperties properties, MessageSink sink) {
    return new Edi214Emitter(properties.edi(), sink);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "fleet.simulator.emit.reefer",
      name = "enabled",
      matchIfMissing = true)
  public ReeferEmitter reeferEmitter(
      EmissionProperties properties, SimulatorProperties simulator, MessageSink sink) {
    return new ReeferEmitter(properties.reefer(), sink, feedRandom(simulator.seed(), "reefer"));
  }

  /** A generator that depends on the run's seed and on which feed is asking, and nothing else. */
  static RandomGenerator feedRandom(long seed, String feed) {
    return new java.util.Random(seed * 1_000_003L + feed.hashCode());
  }
}
