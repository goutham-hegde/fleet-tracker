package com.fleettracking.tracking.consume;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Where this consumer puts a message it can never succeed on.
 *
 * <h2>The poison pill</h2>
 *
 * <p>A consumer reads a partition strictly in order. If one record always throws, and the consumer
 * always retries it, that partition stops for ever — and because it is one partition of twelve, the
 * symptom is not an outage but something far more confusing: most of the fleet keeps updating and a
 * twelfth of it silently freezes. That single bad record is a <em>poison pill</em>, and every
 * consumer needs an answer for it before it meets one.
 *
 * <p>The answer is the same distinction the gateway draws, for the same reason. Some failures will
 * come out differently if tried again — the database is down, the broker is unreachable, the pod is
 * being drained. Those must be retried, indefinitely, because giving up on them loses real data.
 * Other failures cannot possibly come out differently, because the input itself is wrong: a record
 * that is not JSON, or that is JSON but not a position event. Retrying those is an infinite loop
 * that stalls a partition, so they come here instead — set aside with enough context to find out
 * what happened, while the partition moves on.
 *
 * <h2>This topic, not the gateway's</h2>
 *
 * <p>{@code ingest.dlq.v1} holds what the gateway refused from an external feed. This holds what
 * this consumer could not process from an internal, already-validated topic, which is a different
 * thing with a different audience: an entry here means something is wrong <em>inside</em> the
 * platform — a producer that went around the gateway, a bad deployment, an envelope change nobody
 * migrated. Mixing the two would bury a handful of real defects in a large pile of carriers sending
 * malformed EDI, which is routine.
 *
 * <p>Unlike the gateway's dead letters, these keep the original key. A message on
 * {@code position.events.v1} was keyed by the gateway with its shipment id, and that key is a
 * separate field from the value that failed to parse — so it is intact, and free provenance for
 * whoever investigates.
 */
public class TrackingDeadLetters {

  private static final Logger log = LoggerFactory.getLogger(TrackingDeadLetters.class);

  static final String HEADER_REASON = "fleet.dlq.reason";
  static final String HEADER_DETAIL = "fleet.dlq.detail";
  static final String HEADER_ORIGIN = "fleet.dlq.origin";

  private final KafkaTemplate<String, String> kafka;
  private final long sendTimeoutMillis;

  public TrackingDeadLetters(KafkaTemplate<String, String> kafka, long sendTimeoutMillis) {
    this.kafka = kafka;
    this.sendTimeoutMillis = sendTimeoutMillis;
  }

  /**
   * Sets a record aside, and waits for the broker to confirm it.
   *
   * <p>The wait matters. If this returned before the broker had the message, the consumer would go
   * on to commit an offset past a record that exists nowhere — the one case where a dead-letter
   * topic actively destroys data instead of preserving it. Letting the failure propagate instead
   * means the record is retried, which is the right response to an unreachable broker.
   *
   * @throws DeadLetterFailedException if the broker did not acknowledge in time
   */
  public void setAside(ConsumerRecord<String, String> record, String reason, String detail) {
    ProducerRecord<String, String> dead =
        new ProducerRecord<>(TrackingTopics.DEAD_LETTER, record.key(), record.value());
    header(dead, HEADER_REASON, reason);
    header(dead, HEADER_DETAIL, detail);
    header(
        dead,
        HEADER_ORIGIN,
        record.topic() + "-" + record.partition() + "@" + record.offset());

    try {
      kafka.send(dead).get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DeadLetterFailedException("interrupted while dead-lettering", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new DeadLetterFailedException("could not write to " + TrackingTopics.DEAD_LETTER, e);
    }

    log.warn(
        "dead-lettered {}-{}@{} (key {}): {} — {}",
        record.topic(),
        record.partition(),
        record.offset(),
        record.key(),
        reason,
        detail);
  }

  private static void header(ProducerRecord<String, String> record, String key, String value) {
    record
        .headers()
        .add(new RecordHeader(key, (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
  }

  /** The dead-letter write itself failed. Retryable, and must not be swallowed. */
  public static class DeadLetterFailedException extends RuntimeException {
    public DeadLetterFailedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
