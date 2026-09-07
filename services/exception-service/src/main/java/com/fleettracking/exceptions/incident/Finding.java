package com.fleettracking.exceptions.incident;

import com.fleettracking.events.ExceptionType;
import com.fleettracking.events.Severity;
import java.time.Instant;

/**
 * What a rule concluded, before anything has been published or stored.
 *
 * <p>Rules produce these and hand them on; nothing in a rule touches Kafka or MongoDB. That split
 * is what makes the five rules unit-testable without a container between them and an assertion —
 * each one is a function from some state and an event to an opinion, and the opinion is this.
 *
 * <h2>Two instants, and confusing them is the classic mistake</h2>
 *
 * <p>{@link #onsetAt()} is when the condition began: the first reading outside the band, the fix at
 * which the truck stopped moving. {@link #confirmedAt()} is when the rule became sure of it, which
 * is later by however long its tolerance is.
 *
 * <p>The published exception is stamped with the <em>onset</em>, and the incident separately
 * records when it was confirmed. Stamping it with the confirmation would report every temperature
 * excursion as starting half an hour after it did, and would make the duration on the eventual
 * clear wrong by the same amount. It is the same rule S10 settled for arrivals — an arrival is
 * stamped with the instant the vehicle crossed in, not the instant the dwell threshold expired —
 * and it matters here for the same reason: the tolerance is how long the platform waits before
 * believing something, not part of what happened.
 *
 * @param stopId which stop this concerns, or null for the rules that are not about a place
 * @param causedBy the event that led to this conclusion. Never blank: even the rule that fires on
 *     the <em>absence</em> of events names the last one it saw, because "nothing since this" is the
 *     honest statement of what it observed
 * @param detail a finished sentence with the numbers in it. Formatted by the rule, because the rule
 *     is the only thing that knows which numbers matter; a dashboard should not have to learn five
 *     phrasings
 * @param observedValue what was measured, and {@code thresholdValue} the limit it broke. Both null
 *     for a silent device, where the evidence is that nothing was measured at all
 */
public record Finding(
    ExceptionType type,
    String shipmentId,
    String stopId,
    Instant onsetAt,
    Instant confirmedAt,
    String causedBy,
    Severity severity,
    String detail,
    Double observedValue,
    Double thresholdValue) {}
