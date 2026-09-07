package com.fleettracking.exceptions.manifest;

import java.time.Duration;
import java.time.Instant;

/**
 * The slot a consignee booked for a load.
 *
 * <p>Two instants, and only one of them is currently enforced. Arriving after {@link #closesAt()}
 * is late, which is what {@link com.fleettracking.events.ExceptionType#LATE_ARRIVAL} names. Arriving
 * before {@link #opensAt()} is <em>also</em> a violation for a distribution centre — a truck at the
 * gate two hours early occupies a dock somebody else booked, and is turned away — but it is not
 * lateness, and this platform has no exception type that means it.
 *
 * <p>That gap is left open deliberately rather than papered over. Raising an early arrival as
 * {@code LATE_ARRIVAL} would put a sentence in front of a dispatcher that contradicts its own
 * label. Adding a sixth type is a change every consumer of the exception topic has to absorb, and
 * S11 is the standing reminder of what that costs: a fourth event shape on the derived topic broke
 * tests that had nothing to do with the feature adding it. It belongs to a session that can spend
 * that budget properly.
 *
 * @param opensAt when the dock will start accepting the load. May be null: a customer can commit to
 *     a deadline without committing to an opening time
 * @param closesAt the deadline. Never null — a window with no close commits to nothing that can be
 *     breached, and {@link SlaTerms} discards one
 */
public record DeliveryWindow(Instant opensAt, Instant closesAt) {

  /** Whether an arrival at this instant is past the deadline. */
  public boolean isLate(Instant arrival) {
    return arrival.isAfter(closesAt);
  }

  /** How far past the deadline an arrival is. Zero or negative means it was in time. */
  public Duration lateness(Instant arrival) {
    return Duration.between(closesAt, arrival);
  }
}
