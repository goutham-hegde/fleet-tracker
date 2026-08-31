package com.fleettracking.gateway.publish;

import com.fleettracking.events.SourceSystem;
import com.fleettracking.gateway.normalize.RejectionReason;
import java.time.Instant;

/**
 * A message the gateway could not turn into an event, with enough context to work out why.
 *
 * <p>The point of a dead-letter topic is not to record that something failed — a log line does that
 * more cheaply. It is that the message itself survives, so a normalizer bug is recoverable: fix the
 * parser, replay the topic, and the events that should have existed come into being late rather
 * than never. That only works if the body here is the original bytes and not a summary of them.
 *
 * <p>{@code reason} and {@code source} are also written as Kafka headers on the record, so that
 * counting yesterday's rejections by category does not require deserializing every message.
 *
 * @param source which feed it arrived on
 * @param contentType what it claimed to be
 * @param reason the category of failure
 * @param detail what specifically was wrong
 * @param routingKey the best identifier that could be read out of the message before it failed, or
 *     null when nothing could be. Frequently null, because a message that will not parse has no
 *     readable fields at all
 * @param receivedAt when the gateway got it
 * @param body the original payload, byte for byte
 */
public record DeadLetter(
    SourceSystem source,
    String contentType,
    RejectionReason reason,
    String detail,
    String routingKey,
    Instant receivedAt,
    String body) {}
