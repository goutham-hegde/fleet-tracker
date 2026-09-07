package com.fleettracking.exceptions.rule;

import com.fleettracking.exceptions.incident.Finding;
import java.util.Optional;

/**
 * What one rule concluded from one event: the state it now holds, and what should happen.
 *
 * <p>The same shape the geofencer returns, for the same reason. A rule is a pure function from
 * (state, event) to (state, opinion) — it touches no database and no broker, so every one of the
 * five is testable by calling it and asserting on what comes back. Everything that has to happen in
 * the world is done by the service that applies this.
 *
 * <p>Three outcomes are possible and all three are ordinary:
 *
 * <ul>
 *   <li><b>Nothing.</b> By far the most common. A healthy truck reporting every ten seconds produces
 *       thousands of these per journey.
 *   <li><b>Raise.</b> The condition has now held long enough to be worth telling someone.
 *       Produced on <em>every</em> event while it holds, not only the first — the rule does not know
 *       whether it has fired before, and deduplicating is the incident service's job.
 *   <li><b>Clear.</b> The condition no longer holds. Also produced unconditionally, so a rule does
 *       not have to remember whether there is anything to clear.
 * </ul>
 *
 * @param state the rule's state after the event, which is the <em>same instance</em> as before when
 *     nothing happened. That identity is asserted by the tests: a fix that changes nothing must
 *     write nothing, or a truck on an eight-hour leg costs a database write per rule per fix.
 *     Null for the late-arrival rule, which keeps no state at all: whether a projected arrival is
 *     past a deadline is decided entirely by the event in hand and the window on the manifest, and
 *     when it first went past is already recorded as the incident's own onset
 * @param finding what to raise, or null
 * @param clearance what to clear, or null
 */
public record RuleOutcome(ConditionState state, Finding finding, Clearance clearance) {

  /** The rule looked and had no opinion. */
  public static RuleOutcome nothing(ConditionState state) {
    return new RuleOutcome(state, null, null);
  }

  /** The condition has held long enough. */
  public static RuleOutcome raise(ConditionState state, Finding finding) {
    return new RuleOutcome(state, finding, null);
  }

  /** The condition no longer holds. */
  public static RuleOutcome clear(ConditionState state, Clearance clearance) {
    return new RuleOutcome(state, null, clearance);
  }

  /**
   * Both, in that order: open an incident and immediately close it.
   *
   * <p>Only one situation produces this, and it is a real one rather than a convenience. A shipment
   * that arrives after its booked window has breached its SLA and the breach is over in the same
   * instant — there is nothing left to watch. Publishing only the clear would leave a consumer that
   * joined late with a closing event for an incident it never saw open; publishing only the raise
   * would leave the dashboard permanently red for a delivery that has already happened. The pair is
   * the honest record: it went wrong, here is how badly, and here is where it ended.
   */
  public static RuleOutcome raiseThenClear(
      ConditionState state, Finding finding, Clearance clearance) {
    return new RuleOutcome(state, finding, clearance);
  }

  public Optional<Finding> raised() {
    return Optional.ofNullable(finding);
  }

  public Optional<Clearance> cleared() {
    return Optional.ofNullable(clearance);
  }
}
