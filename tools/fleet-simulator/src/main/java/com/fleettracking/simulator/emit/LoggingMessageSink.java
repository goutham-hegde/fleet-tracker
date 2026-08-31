package com.fleettracking.simulator.emit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prints each message to the console.
 *
 * <p>Logged at DEBUG rather than INFO because the feeds are noisy by design — eight trucks
 * reporting telematics every thirty simulated seconds fills a terminal quickly, and drowning the
 * arrival and departure lines would make a run harder to follow rather than easier. Turn it up
 * with {@code logging.level.com.fleettracking.simulator.emit=DEBUG} when the payloads themselves
 * are what you want to look at.
 *
 * <p>An INFO summary of how much each feed has produced is logged periodically instead, which is
 * what tells you at a glance that a feed has silently stopped.
 */
public class LoggingMessageSink implements MessageSink {

  private static final Logger log = LoggerFactory.getLogger(LoggingMessageSink.class);

  private final java.util.EnumMap<com.fleettracking.events.SourceSystem, Long> counts =
      new java.util.EnumMap<>(com.fleettracking.events.SourceSystem.class);
  private final long summariseEvery;
  private long total;

  /**
   * @param summariseEvery emit a per-feed count line every this many messages; zero disables it
   */
  public LoggingMessageSink(long summariseEvery) {
    this.summariseEvery = summariseEvery;
  }

  @Override
  public void accept(SourceMessage message) {
    counts.merge(message.source(), 1L, Long::sum);
    total++;

    if (log.isDebugEnabled()) {
      log.debug(
          "{} {} key={} lag={} {}",
          message.source(),
          message.occurredAt(),
          message.routingKey(),
          message.lag(),
          singleLine(message.body()));
    }

    if (summariseEvery > 0 && total % summariseEvery == 0) {
      log.info("emitted {} messages: {}", total, counts);
    }
  }

  /** EDI is multi-line by nature; a log line that spans twenty lines is unreadable. */
  private static String singleLine(String body) {
    String flat = body.replace("\r", "").replace('\n', ' ');
    return flat.length() <= 400 ? flat : flat.substring(0, 400) + "...";
  }

  @Override
  public void close() {
    if (total > 0) {
      log.info("sink closed after {} messages: {}", total, counts);
    }
  }
}
