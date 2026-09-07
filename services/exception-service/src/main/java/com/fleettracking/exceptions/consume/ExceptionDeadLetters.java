package com.fleettracking.exceptions.consume;

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
 * Where this service puts a record it can never process.
 *
 * <h2>A third dead-letter topic, and why they are not one topic</h2>
 *
 * <p>The gateway's {@code ingest.dlq.v1} holds bad data from carriers: a truncated EDI file, a
 * corrupted payload, an HTML error page where a JSON body should have been. That is routine, it is
 * somebody else's fault, and the response is usually to tell them.
 *
 * <p>The tracking processor's {@code tracking.dlq.v1} and this service's {@code exceptions.dlq.v1}
 * mean something entirely different. Everything on the canonical topics was validated by the gateway
 * before it was published, so a record here that cannot be parsed means <em>this platform</em>
 * produced something that should not exist. It is a bug, not a bad supplier, and mixing the two
 * would bury a handful of real defects under thousands of ordinary carrier mistakes.
 *
 * <p>Separate from the tracking processor's for the narrower version of the same reason: an entry
 * there means position history was not written, and an entry here means an exception was not
 * evaluated. Those are different severities and different people.
 *
 * <h2>The key is kept</h2>
 *
 * <p>Unlike the gateway's dead-letter topic, which is deliberately unkeyed because a message that
 * failed to parse has no readable shipment id. Here the original record already carries the key the
 * platform assigned it, so preserving it means the dead letters for one shipment stay in order and
 * beside each other.
 */
public class ExceptionDeadLetters {

  /** This service's own, named the way the other two are. */
  public static final String TOPIC = "exceptions.dlq.v1";

  private static final String SOURCE_TOPIC_HEADER = "fleet.source-topic";
  private static final String REASON_HEADER = "fleet.rejection-reason";
  private static final String DETAIL_HEADER = "fleet.rejection-detail";

  private static final Logger log = LoggerFactory.getLogger(ExceptionDeadLetters.class);

  private final KafkaTemplate<String, String> kafka;
  private final long sendTimeoutMillis;

  public ExceptionDeadLetters(KafkaTemplate<String, String> kafka, long sendTimeoutMillis) {
    this.kafka = kafka;
    this.sendTimeoutMillis = sendTimeoutMillis;
  }

  /**
   * Sets a record aside with the original bytes and a reason.
   *
   * <p>The original value is republished untouched rather than re-rendered, so that whatever was
   * wrong with it is still wrong in the dead letter and can be diagnosed from it. The reason travels
   * as a header for the same purpose the gateway's does: filtering a dead-letter topic by why
   * something failed should not require parsing the thing that failed to parse.
   *
   * @throws DeadLetterFailedException if the broker did not acknowledge. Deliberately fatal to the
   *     record: the alternative is committing an offset past a record that now exists nowhere at all
   */
  public void setAside(
      org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record,
      String reason,
      String detail) {

    ProducerRecord<String, String> dead =
        new ProducerRecord<>(TOPIC, record.key(), record.value());
    dead.headers().add(new RecordHeader(SOURCE_TOPIC_HEADER, bytes(record.topic())));
    dead.headers().add(new RecordHeader(REASON_HEADER, bytes(reason)));
    if (detail != null) {
      dead.headers().add(new RecordHeader(DETAIL_HEADER, bytes(detail)));
    }

    try {
      kafka.send(dead).get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DeadLetterFailedException("interrupted while dead-lettering a record", e);
    } catch (ExecutionException | TimeoutException e) {
      throw new DeadLetterFailedException("could not write to " + TOPIC, e);
    }

    log.warn(
        "set aside a record from {} partition {} offset {}: {} ({})",
        record.topic(),
        record.partition(),
        record.offset(),
        reason,
        detail);
  }

  private static byte[] bytes(String value) {
    return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
  }

  /** The dead-letter write failed, which makes the original record retryable rather than lost. */
  public static class DeadLetterFailedException extends RuntimeException {
    public DeadLetterFailedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
