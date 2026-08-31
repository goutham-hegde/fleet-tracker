package com.fleettracking.gateway.normalize;

import com.fleettracking.events.SourceSystem;
import java.time.Instant;
import java.util.Objects;

/**
 * One message as it arrived at the gateway, before anything has looked inside it.
 *
 * <p>The body is the request's bytes decoded as a string and nothing more. It is deliberately not
 * a parsed object: if the controller bound the request to a typed payload, a malformed message
 * would fail inside Spring's request binding and come back to the sender as a {@code 400} that this
 * service never saw, so the one class of message the dead-letter topic exists for is the one class
 * that could never reach it. Parsing happens inside a normalizer, where a failure is a value that
 * can be routed rather than an exception thrown at the framework.
 *
 * @param source which feed it arrived on, decided by which endpoint was called
 * @param contentType the declared content type, kept because {@code RawPayload} records it and
 *     because a feed changing format should be visible in the archive rather than inferred later
 * @param body the payload exactly as received
 * @param receivedAt gateway wall-clock time on arrival. This is real time, not simulated time —
 *     the simulator's rule against {@code Instant.now()} is a rule about the simulation's own
 *     clock, and the moment this platform genuinely heard about an event is a fact about this
 *     platform
 */
public record InboundMessage(
    SourceSystem source, String contentType, String body, Instant receivedAt) {

  public InboundMessage {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(body, "body");
    Objects.requireNonNull(receivedAt, "receivedAt");
    contentType = contentType == null || contentType.isBlank() ? source.defaultContentType() : contentType;
  }
}
