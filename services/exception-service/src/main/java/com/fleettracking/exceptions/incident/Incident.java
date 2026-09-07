package com.fleettracking.exceptions.incident;

import com.fleettracking.events.ExceptionType;
import com.fleettracking.events.Severity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One SLA breach, from the moment it began to the moment it resolved.
 *
 * <h2>Why this is stored at all</h2>
 *
 * <p>The events on {@code exceptions.v1} are the output; this collection is what makes the output
 * possible. A clear has to find the raise it belongs to, and "which exceptions are currently open
 * for this shipment" cannot be answered by reading a topic — a consumer would have to replay the
 * whole of it and fold the pairs together on every question. It is also what survives a restart: a
 * process that came up with no memory of what is open would either re-raise everything already
 * raised or clear nothing it had not seen begin.
 *
 * <h2>The document id is the incident id</h2>
 *
 * <p>Not a generated key beside it. The incident id is derived from the type, the shipment, the
 * stop and the onset, so writing under it makes the same incident write to the same document no
 * matter how many times the work is redone — which is what lets this service publish before it
 * records without inventing a second incident when a record is redelivered.
 *
 * <h2>Closed incidents are kept</h2>
 *
 * <p>A cleared incident stays in the collection with its resolution and its end instant rather than
 * being deleted. The record of what went wrong and for how long is the interesting half of an
 * exception system: "this lane breaks its cold chain on the Kurnool leg every third run" is a
 * question about closed incidents, and deleting them to keep the open set tidy answers it with
 * silence. The open set is a filtered query, not a separate collection.
 *
 * @param exceptionId the incident's identity, and this document's id. Both events carry it
 * @param onsetAt when the condition began — the first out-of-band reading, the instant the truck
 *     stopped moving. This is what the id is derived from, so it must never be revised
 * @param raisedAt when the rule became sure and published. Later than the onset by the rule's
 *     tolerance, and carried separately so the delay between the two is visible rather than lost
 * @param clearedAt when it resolved, or null while it is still open
 * @param state {@link #OPEN} or {@link #CLEARED}. Stored as a string rather than a boolean because
 *     it is what the partial index filters on, and because a third state is easy to imagine —
 *     acknowledged, suppressed — and a boolean would have to be migrated to admit one
 * @param lastSeenAt the newest event time this incident was still true at. Updated while it is
 *     open, so an operator can tell an incident that is still being confirmed from one whose
 *     shipment went quiet
 */
@Document(collection = Incident.COLLECTION)
public record Incident(
    @Id String exceptionId,
    String shipmentId,
    ExceptionType type,
    String stopId,
    Severity severity,
    String state,
    Instant onsetAt,
    Instant raisedAt,
    Instant clearedAt,
    Instant lastSeenAt,
    String detail,
    Double observedValue,
    Double thresholdValue,
    String resolution) {

  /** One collection for all five rules, for the same reason manifests share one. */
  public static final String COLLECTION = "exceptions";

  /** The condition is still true, as far as this service knows. */
  public static final String OPEN = "OPEN";

  /** The condition resolved, or the shipment stopped being able to breach it. */
  public static final String CLEARED = "CLEARED";

  /** Whether this incident is still open. */
  public boolean isOpen() {
    return OPEN.equals(state);
  }

  /**
   * The same incident, still open, confirmed again at a later instant.
   *
   * <p>Severity may go up and never comes down. An incident that got worse is still the same
   * incident — a projected late delivery that actually happened, a warm box that a customer's band
   * turned out to cover — and reopening it under a new id to record that would break the pairing
   * the whole model rests on. Letting it fall again would be worse: the record would then describe
   * the breach at whatever intensity it happened to end at, rather than at its worst.
   */
  public Incident stillTrue(Instant at, Severity worstSeverity, String updatedDetail, Double observed) {
    Severity escalated =
        worstSeverity != null && worstSeverity.ordinal() > severity.ordinal()
            ? worstSeverity
            : severity;
    return new Incident(
        exceptionId,
        shipmentId,
        type,
        stopId,
        escalated,
        state,
        onsetAt,
        raisedAt,
        clearedAt,
        at,
        updatedDetail == null ? detail : updatedDetail,
        observed == null ? observedValue : observed,
        thresholdValue,
        resolution);
  }

  /** The same incident, closed. */
  public Incident closed(Instant at, String how) {
    return new Incident(
        exceptionId,
        shipmentId,
        type,
        stopId,
        severity,
        CLEARED,
        onsetAt,
        raisedAt,
        at,
        at,
        detail,
        observedValue,
        thresholdValue,
        how);
  }
}
