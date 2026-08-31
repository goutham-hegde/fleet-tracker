package com.fleettracking.gateway.normalize;

import com.fleettracking.events.SourceEvent;
import java.util.List;

/**
 * What a normalizer concluded about one inbound message.
 *
 * <p>A result type rather than an exception, because rejection is not exceptional here. A feed with
 * a broken producer can reject thousands of messages an hour and the gateway is working correctly
 * the whole time. Exceptions would make the normal path and the rejection path look different to
 * every caller, invite a {@code catch} that swallows one, and cost a stack trace fill-in per bad
 * message. As a value, a rejection is something the caller has to deal with because the compiler
 * says so.
 *
 * <p>Sealed for the same reason {@code Event} is: a {@code switch} over it is checked for
 * exhaustiveness, so adding an outcome later is a build failure rather than a message that
 * silently goes nowhere.
 *
 * <h2>Why success carries a list</h2>
 *
 * <p>Three of the four feeds send one event per message and would be happier with a single value.
 * The fourth does not: an EDI 214 interchange is a batch, and one file routinely reports status for
 * a dozen different shipments — which is also why it has no partition key of its own until it is
 * split. Making success plural from the start means S7 adds the EDI normalizer without changing
 * this type or anything that consumes it.
 */
public sealed interface NormalizationResult {

  /** One or more canonical events, ready to be validated and published. */
  record Normalized(List<SourceEvent> events) implements NormalizationResult {

    public Normalized {
      events = List.copyOf(events);
      if (events.isEmpty()) {
        throw new IllegalArgumentException("a normalized result must carry at least one event");
      }
    }
  }

  /**
   * The message could not become an event, and why.
   *
   * @param reason the category, for grouping
   * @param detail what specifically was wrong, for a human reading one dead-letter message. Kept
   *     short and free of the payload itself — the payload travels alongside it in full, and
   *     duplicating it here would double the size of every dead-letter message for no gain
   */
  record Rejected(RejectionReason reason, String detail) implements NormalizationResult {}

  /** The common case: exactly one event came out. */
  static NormalizationResult of(SourceEvent event) {
    return new Normalized(List.of(event));
  }

  static NormalizationResult rejected(RejectionReason reason, String detail) {
    return new Rejected(reason, detail);
  }
}
