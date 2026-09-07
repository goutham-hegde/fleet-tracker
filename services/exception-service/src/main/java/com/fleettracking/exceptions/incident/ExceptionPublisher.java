package com.fleettracking.exceptions.incident;

import com.fleettracking.events.DerivedEvent;
import com.fleettracking.events.EventJson;
import com.fleettracking.events.Topics;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Puts raises and clears onto {@code exceptions.v1}.
 *
 * <h2>Its own topic, not the derived one</h2>
 *
 * <p>Arrivals, departures and estimates share {@code shipment.derived.v1} and are told apart by
 * shape. Exceptions could have joined them and deliberately do not, for two reasons that pull the
 * same way.
 *
 * <p>They are read by different people. Everything on the derived topic describes a shipment
 * progressing normally, and its consumers are maps and reports. An exception is an interruption,
 * and its consumer is whoever has to do something about it. A separate topic means a subscriber can
 * have the alerts without the firehose — the position topic produces thousands of events per truck
 * per journey and this one produces a handful per fleet per day.
 *
 * <p>And it keeps the shape-discrimination problem from getting worse. CLAUDE.md records what the
 * third shape on the derived topic already cost: S11's estimates were counted as arrivals by a
 * geofencing test that had never needed to filter before, and "exactly one arrival" failed for
 * reasons unrelated to geofencing. Adding two more shapes there would compound a mechanism that has
 * already bitten once.
 *
 * <h2>Keyed by shipment, waited for, and allowed to fail</h2>
 *
 * <p>The key is the shipment id, like every other topic on this platform, so a clear cannot be read
 * before the raise it closes. The send is awaited: returning before the broker has the event would
 * let the consumer commit past a record whose exception exists nowhere, and an alert nobody ever
 * receives is worse than a duplicate. A failure propagates, the record is retried, and the retry
 * republishes a byte-identical event because the ids are derived from the incident rather than
 * generated.
 */
public class ExceptionPublisher {

  private static final Logger log = LoggerFactory.getLogger(ExceptionPublisher.class);

  private final KafkaTemplate<String, String> kafka;
  private final long sendTimeoutMillis;

  public ExceptionPublisher(KafkaTemplate<String, String> kafka, long sendTimeoutMillis) {
    this.kafka = kafka;
    this.sendTimeoutMillis = sendTimeoutMillis;
  }

  /**
   * Publishes one exception event and waits for the acknowledgement.
   *
   * @throws ExceptionPublishFailedException if the broker did not acknowledge in time. Retryable,
   *     and deliberately not swallowed
   */
  public void publish(DerivedEvent event) {
    String payload = EventJson.mapper().writeValueAsString(event);
    ProducerRecord<String, String> record =
        new ProducerRecord<>(Topics.EXCEPTIONS, event.shipmentId(), payload);

    try {
      kafka.send(record).get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ExceptionPublishFailedException("interrupted while publishing an exception", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new ExceptionPublishFailedException("could not write to " + Topics.EXCEPTIONS, e);
    }

    log.info(
        "published {} for shipment {} ({})",
        event.getClass().getSimpleName(),
        event.shipmentId(),
        event.eventId());
  }

  /** The exception write failed. Retryable, and must not be swallowed. */
  public static class ExceptionPublishFailedException extends RuntimeException {
    public ExceptionPublishFailedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
