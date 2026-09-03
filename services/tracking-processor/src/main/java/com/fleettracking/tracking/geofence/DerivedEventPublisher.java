package com.fleettracking.tracking.geofence;

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
 * Puts what the platform concluded onto {@code shipment.derived.v1}.
 *
 * <h2>Keyed by shipment, like everything else</h2>
 *
 * <p>The key is the shipment id, for the same reason the source topics use it: it is what
 * guarantees that one shipment's events are read in the order they were produced, without paying
 * for global ordering. A consumer of this topic that sees a departure must be able to rely on
 * having already seen the matching arrival, and the key is what makes that true.
 *
 * <h2>The broker is waited for, and a failure is allowed to propagate</h2>
 *
 * <p>Returning before the broker has the event would let the consumer commit an offset past a
 * position whose arrival exists nowhere — the arrival would simply never be announced, silently and
 * permanently. Letting the failure propagate instead means the position is retried, which is the
 * correct response to an unreachable broker.
 *
 * <p>The retry is what makes it safe to publish <em>before</em> recording that the arrival was
 * announced: the second attempt regenerates a byte-identical event, because the id is derived from
 * the shipment, the stop and the crossing instant rather than generated. See
 * {@link DerivedEventIds}.
 */
public class DerivedEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(DerivedEventPublisher.class);

  private final KafkaTemplate<String, String> kafka;
  private final long sendTimeoutMillis;

  public DerivedEventPublisher(KafkaTemplate<String, String> kafka, long sendTimeoutMillis) {
    this.kafka = kafka;
    this.sendTimeoutMillis = sendTimeoutMillis;
  }

  /**
   * Publishes one derived event and waits for the acknowledgement.
   *
   * @throws DerivedPublishFailedException if the broker did not acknowledge in time. Retryable, and
   *     deliberately not swallowed
   */
  public void publish(DerivedEvent event) {
    String payload = EventJson.mapper().writeValueAsString(event);
    ProducerRecord<String, String> record =
        new ProducerRecord<>(Topics.DERIVED, event.shipmentId(), payload);

    try {
      kafka.send(record).get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DerivedPublishFailedException("interrupted while publishing a derived event", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new DerivedPublishFailedException("could not write to " + Topics.DERIVED, e);
    }

    log.info(
        "published {} for shipment {} ({})",
        event.getClass().getSimpleName(),
        event.shipmentId(),
        event.eventId());
  }

  /** The derived-event write failed. Retryable, and must not be swallowed. */
  public static class DerivedPublishFailedException extends RuntimeException {
    public DerivedPublishFailedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
