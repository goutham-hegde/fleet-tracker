package com.fleettracking.gateway;

import com.fleettracking.events.SourceEvent;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.gateway.normalize.InboundMessage;
import com.fleettracking.gateway.normalize.NormalizationResult;
import com.fleettracking.gateway.normalize.Normalizer;
import com.fleettracking.gateway.normalize.RejectionReason;
import com.fleettracking.gateway.publish.DeadLetter;
import com.fleettracking.gateway.publish.EventPublisher;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The path every inbound message takes: normalize, validate, then publish or dead-letter.
 *
 * <p>Written as one place rather than four, because the steps around the parsing are identical for
 * every feed and the one that must never be skipped is validation. A normalizer that forgot to
 * range-check a latitude would put a truck in the Indian Ocean on the live map, and "each
 * normalizer remembers to validate" is not a property a code review reliably enforces. Here it is
 * structural: a normalizer's output cannot reach Kafka without passing through this method.
 *
 * <h2>Validation is not the same as parsing</h2>
 *
 * <p>The constraints live on the envelopes in {@code libs/events} — latitude within ±90, speed
 * under 250 km/h, heading below 360, nothing blank. They are declared there rather than here so
 * that every producer in the platform, present and future, is held to the same statement of what a
 * well-formed event is. This service is simply the first thing to enforce them, and the last
 * chance to do so before a bad event becomes every consumer's problem.
 */
public class IngestService {

  private static final Logger log = LoggerFactory.getLogger(IngestService.class);

  private final Map<SourceSystem, Normalizer> normalizers = new EnumMap<>(SourceSystem.class);
  private final Validator validator;
  private final EventPublisher publisher;

  public IngestService(List<Normalizer> normalizers, Validator validator, EventPublisher publisher) {
    for (Normalizer normalizer : normalizers) {
      Normalizer previous = this.normalizers.put(normalizer.source(), normalizer);
      if (previous != null) {
        throw new IllegalArgumentException("two normalizers claim " + normalizer.source());
      }
    }
    this.validator = validator;
    this.publisher = publisher;
  }

  /** Whether this gateway can currently read a feed. False for feeds whose normalizer is not built yet. */
  public boolean handles(SourceSystem source) {
    return normalizers.containsKey(source);
  }

  /**
   * Processes one message end to end.
   *
   * <p>Never throws for anything the producer did. It does throw {@link
   * EventPublisher.PublishFailedException} when the broker is unreachable, which is deliberately
   * different: that is not the producer's fault and the producer should retry.
   */
  public IngestOutcome accept(InboundMessage message) {
    Normalizer normalizer = normalizers.get(message.source());
    if (normalizer == null) {
      return deadLetter(
          message, RejectionReason.UNSUPPORTED_FEED, "no normalizer for " + message.source(), null);
    }

    NormalizationResult result = normalizer.normalize(message);
    return switch (result) {
      case NormalizationResult.Rejected rejected ->
          deadLetter(message, rejected.reason(), rejected.detail(), null);
      case NormalizationResult.Normalized normalized -> publishAll(message, normalized.events());
      case NormalizationResult.Partial partial -> publishAndDeadLetter(message, partial);
    };
  }

  /**
   * A batch that was partly readable: publish what survived, and dead-letter the original whole.
   *
   * <p>Both, not either. The events that parsed are real freight events and a carrier does not
   * resend a batch on request, so discarding them because a later part of the file was truncated
   * loses information the platform will never get again. The original bytes still go to the
   * dead-letter topic, because the alternative is publishing a partial batch and leaving no record
   * anywhere that anything was missing.
   *
   * <p>Replaying that dead-letter entry later is safe, and only because event ids are derived: the
   * transaction sets that already published regenerate byte-identical ids, and downstream
   * de-duplication drops them. With random ids the replay would double-count every one.
   */
  private IngestOutcome publishAndDeadLetter(
      InboundMessage message, NormalizationResult.Partial partial) {
    IngestOutcome published = publishAll(message, partial.events());
    deadLetter(message, partial.reason(), partial.detail(), null);
    // The partial's own reason is reported rather than any per-event validation failure, because it
    // describes the message as a whole -- which is what the producer needs to hear.
    return new IngestOutcome(
        published.published(), published.deadLettered() + 1, partial.reason(), partial.detail());
  }

  private IngestOutcome publishAll(InboundMessage message, List<SourceEvent> events) {
    int published = 0;
    int deadLettered = 0;
    RejectionReason firstReason = null;
    String firstDetail = null;

    for (SourceEvent event : events) {
      String violations = violations(event);
      if (violations != null) {
        deadLetter(message, RejectionReason.INVALID_VALUE, violations, event.shipmentId());
        deadLettered++;
        if (firstReason == null) {
          firstReason = RejectionReason.INVALID_VALUE;
          firstDetail = violations;
        }
        continue;
      }
      publisher.publish(event);
      published++;
    }

    if (deadLettered == 0) {
      return IngestOutcome.published(published);
    }
    return new IngestOutcome(published, deadLettered, firstReason, firstDetail);
  }

  /**
   * @return a description of everything wrong with the event, or null if it is valid
   */
  private String violations(SourceEvent event) {
    Set<ConstraintViolation<SourceEvent>> violations = validator.validate(event);
    if (violations.isEmpty()) {
      return null;
    }
    List<String> described = new ArrayList<>();
    for (ConstraintViolation<SourceEvent> violation : violations) {
      described.add(violation.getPropertyPath() + " " + violation.getMessage());
    }
    // Sorted so the same broken event produces the same detail string every time; violations come
    // back in an unspecified order, and an unstable message is one nothing can be grouped by.
    return described.stream().sorted().collect(Collectors.joining("; "));
  }

  private IngestOutcome deadLetter(
      InboundMessage message, RejectionReason reason, String detail, String routingKey) {
    publisher.publishDeadLetter(
        new DeadLetter(
            message.source(),
            message.contentType(),
            reason,
            detail,
            routingKey,
            message.receivedAt(),
            message.body()));
    log.debug("rejected {} message: {} {}", message.source(), reason, detail);
    return IngestOutcome.deadLettered(reason, detail);
  }
}
