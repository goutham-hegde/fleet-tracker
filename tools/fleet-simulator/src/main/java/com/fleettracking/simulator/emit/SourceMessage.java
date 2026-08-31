package com.fleettracking.simulator.emit;

import com.fleettracking.events.SourceSystem;
import java.time.Instant;
import java.util.Objects;

/**
 * One message as a source system would put it on the wire, before anything has normalized it.
 *
 * <p>The body is a {@code String} rather than a parsed object for the same reason
 * {@link com.fleettracking.events.RawPayload} holds one: a quarter of this platform's input is not
 * JSON. EDI 214 is {@code ~}-terminated segments of {@code *}-delimited text, and a type that could
 * only carry JSON could not carry it.
 *
 * <p>Deliberately <em>not</em> a canonical event. Nothing here is converted, enriched or corrected;
 * that is the gateway's job in M2, and it can only be shown to work if what arrives is genuinely as
 * dissimilar as the real feeds are.
 *
 * @param source which feed produced it
 * @param contentType how {@link #body()} should be parsed
 * @param routingKey the best identity <em>this source</em> happens to know — a vehicle id from
 *     telematics, a shipment id from the mobile app and from EDI, a bare device id from a reefer
 *     probe. It is emphatically <b>not</b> the canonical Kafka key: topics are keyed by
 *     {@code shipmentId}, and three of the four feeds cannot supply one. Identity resolution in S8
 *     is what turns this into a real key
 * @param occurredAt simulated time the underlying event actually happened
 * @param emittedAt simulated time the source got round to sending it. Equal to {@code occurredAt}
 *     for a live device; hours later for an EDI batch or a phone coming out of a dead zone. The gap
 *     between the two is the feed lag M2 measures, and it is a property of the source rather than
 *     of the platform
 * @param body the payload, exactly as it goes on the wire
 */
public record SourceMessage(
    SourceSystem source,
    String contentType,
    String routingKey,
    Instant occurredAt,
    Instant emittedAt,
    String body) {

  public SourceMessage {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(contentType, "contentType");
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(emittedAt, "emittedAt");
    Objects.requireNonNull(body, "body");
  }

  /** A message sent the moment it happened, in the feed's usual content type. */
  public static SourceMessage live(
      SourceSystem source, String routingKey, Instant at, String body) {
    return new SourceMessage(source, source.defaultContentType(), routingKey, at, at, body);
  }

  /** A message filed later than the event it describes — a batch drop, or a delayed retry. */
  public static SourceMessage delayed(
      SourceSystem source, String routingKey, Instant occurredAt, Instant emittedAt, String body) {
    return new SourceMessage(
        source, source.defaultContentType(), routingKey, occurredAt, emittedAt, body);
  }

  /** How far behind the event this message was sent. Never negative. */
  public java.time.Duration lag() {
    java.time.Duration lag = java.time.Duration.between(occurredAt, emittedAt);
    return lag.isNegative() ? java.time.Duration.ZERO : lag;
  }
}
