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
 * split.
 *
 * <h2>Why there is a third outcome</h2>
 *
 * <p>A batch is also the only thing that can be <em>partly</em> wrong, and S7 discovered that the
 * two-outcome version forces a bad choice. A truncated interchange typically carries several
 * complete, self-consistent shipment statuses followed by one that was cut off mid-segment. With
 * only success and failure available, the whole batch is either published — losing the knowledge
 * that something was damaged — or rejected, throwing away a dozen real freight events because the
 * thirteenth was truncated. Neither is acceptable when the carrier's back office has already sent
 * the batch and will not send it again.
 *
 * <p>{@link Partial} says both things at once: here are the events that survived, and here is what
 * was wrong. The gateway publishes the survivors and dead-letters the original bytes in full, so
 * nothing is lost in either direction. That is only safe because event ids are derived rather than
 * random — replaying the dead-letter entry regenerates byte-identical ids for the statuses already
 * published, and downstream de-duplication absorbs them. A random id would make replay a
 * double-count and would force the all-or-nothing choice back on us.
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
   * Some of the message became events and some of it did not.
   *
   * <p>Only a batch feed can produce this. A single-message feed either parses or does not.
   *
   * @param events the parts that survived, which are published normally
   * @param reason the category of what went wrong with the rest
   * @param detail which parts failed and how — this is the only record of <em>where</em> in a batch
   *     the damage was, since the dead-letter message carries the interchange whole
   */
  record Partial(List<SourceEvent> events, RejectionReason reason, String detail)
      implements NormalizationResult {

    public Partial {
      events = List.copyOf(events);
      if (events.isEmpty()) {
        // Nothing survived, so this is simply a rejection. Allowing an empty partial would give
        // the same situation two representations and let a caller handle one and miss the other.
        throw new IllegalArgumentException("a partial result must carry at least one event");
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

  /**
   * Success if everything came through, a partial if some of it did not, and a rejection if none of
   * it did — so a batch parser can report what it found without deciding which of the three shapes
   * that amounts to.
   */
  static NormalizationResult of(List<SourceEvent> events, RejectionReason reason, String detail) {
    if (reason == null) {
      return new Normalized(events);
    }
    return events.isEmpty() ? new Rejected(reason, detail) : new Partial(events, reason, detail);
  }
}
