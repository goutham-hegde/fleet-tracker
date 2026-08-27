package com.fleettracking.events;

/**
 * How much an exception should interrupt someone.
 *
 * <p>Kept separate from {@link ExceptionType} because the same kind of breach is not always the
 * same size of problem: a temperature excursion on a retail load of dry goods is a note, and the
 * identical excursion on a pharma load is a destroyed consignment. Severity is a property of the
 * incident, decided by the rule with the shipment in hand — not a property of the rule.
 */
public enum Severity {

  /** Worth recording, not worth waking anyone. */
  INFO,

  /** Someone should look before the end of the shift. */
  WARNING,

  /** Acting late costs money or freight. Surfaces immediately on the dashboard. */
  CRITICAL
}
