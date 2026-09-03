package com.fleettracking.gateway.publish;

import com.fleettracking.events.EventJson;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.SourceEvent;
import com.fleettracking.events.StatusEvent;
import com.fleettracking.events.Topics;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Writes canonical events, and messages that could not become one, to Kafka.
 *
 * <h2>Why the send is awaited</h2>
 *
 * <p>{@code KafkaTemplate.send} returns immediately and completes later. Returning {@code 202
 * Accepted} to a producer at that point would be a lie: the message is in a client-side batch in
 * this JVM's heap, and if the broker is unreachable or the pod is rescheduled it is simply gone,
 * while the vendor that sent it has been told it was received and will never send it again. The
 * gateway is the point at which responsibility for a message transfers, so it waits for the broker
 * to acknowledge before it accepts that responsibility. The cost is latency on a request that has
 * nothing else to do anyway.
 *
 * <h2>Keys</h2>
 *
 * <p>Every canonical event is keyed by its shipment id. Kafka guarantees ordering within a
 * partition and a key always hashes to the same partition, so one shipment's events arrive in the
 * order they were produced without the platform ever paying for global ordering — which would mean
 * a single partition and a single consumer for the whole fleet.
 *
 * <p>Dead letters are deliberately <em>unkeyed</em>. A message that failed to parse usually has no
 * readable shipment id, and keying the ones that do while leaving the rest null would spread one
 * feed's failures unevenly across partitions for no benefit: nothing consumes this topic in order.
 */
public class EventPublisher {

  private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

  private static final String HEADER_SOURCE = "fleet.source";
  private static final String HEADER_REASON = "fleet.rejection-reason";
  private static final String HEADER_EVENT_TYPE = "fleet.event-type";

  private final KafkaTemplate<String, String> kafka;
  private final long sendTimeoutMillis;

  public EventPublisher(KafkaTemplate<String, String> kafka, long sendTimeoutMillis) {
    this.kafka = kafka;
    this.sendTimeoutMillis = sendTimeoutMillis;
  }

  /**
   * Publishes one canonical event and waits for the broker to acknowledge it.
   *
   * @return the topic it landed on
   * @throws PublishFailedException if the broker did not acknowledge in time. The caller must
   *     surface this to the producer as a failure so that it retries, rather than dead-lettering
   *     it: the message was fine and it is this platform that is broken, and a dead-letter write
   *     would be going to the same unreachable broker anyway
   */
  public String publish(SourceEvent event) {
    String topic =
        switch (event) {
          case PositionEvent ignored -> Topics.POSITION;
          case StatusEvent ignored -> Topics.STATUS;
        };

    ProducerRecord<String, String> record =
        new ProducerRecord<>(topic, event.shipmentId(), EventJson.mapper().writeValueAsString(event));
    header(record, HEADER_SOURCE, event.raw().source().name());
    header(record, HEADER_EVENT_TYPE, event instanceof PositionEvent ? "position" : "status");

    send(record);
    return topic;
  }

  /** Publishes a rejected message to the dead-letter topic and waits for acknowledgement. */
  public void publishDeadLetter(DeadLetter deadLetter) {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(
            Topics.DEAD_LETTER, null, EventJson.mapper().writeValueAsString(deadLetter));
    header(record, HEADER_SOURCE, deadLetter.source().name());
    header(record, HEADER_REASON, deadLetter.reason().name());

    send(record);
    log.info(
        "dead-lettered {} message: {} ({})",
        deadLetter.source(),
        deadLetter.reason(),
        deadLetter.detail());
  }

  private void send(ProducerRecord<String, String> record) {
    try {
      kafka.send(record).get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      // Restore the flag rather than swallowing it: something is shutting this thread down, and a
      // request thread that quietly ignores an interrupt keeps a pod alive past its grace period.
      Thread.currentThread().interrupt();
      throw new PublishFailedException("interrupted while publishing to " + record.topic(), e);
    } catch (ExecutionException | TimeoutException e) {
      throw new PublishFailedException("could not publish to " + record.topic(), e);
    }
  }

  private static void header(ProducerRecord<String, String> record, String key, String value) {
    record.headers().add(new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8)));
  }

  /** The broker did not accept a message. Distinct from a message this gateway rejected. */
  public static class PublishFailedException extends RuntimeException {
    public PublishFailedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
