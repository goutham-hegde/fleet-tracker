package com.fleettracking.events;

/**
 * The kinds of SLA breach this platform detects.
 *
 * <p>Five rules, chosen because each needs a different shape of evidence — together they exercise
 * every part of the pipeline rather than testing one mechanism five times.
 */
public enum ExceptionType {

  /** Projected or actual arrival is past the committed window. Needs an ETA and a schedule. */
  LATE_ARRIVAL,

  /** Reefer drifted off its setpoint beyond tolerance, for long enough to matter. Needs a
   * sustained condition, not a single reading — one bad sample is a sensor glitch. */
  TEMPERATURE_EXCURSION,

  /** Stationary away from any planned stop for longer than a threshold. Breakdown, or a driver
   * taking an unscheduled break. Needs geofence state, not just position. */
  UNPLANNED_STOP,

  /** Position is further from the planned route than tolerance allows. Needs the route, and is the
   * rule most easily fooled by a poor GPS fix. */
  ROUTE_DEVIATION,

  /** No event of any kind for longer than the feed's expected interval. The only rule triggered by
   * the <em>absence</em> of data, which is why it cannot be evaluated on the event stream alone
   * and needs a timer. */
  SIGNAL_LOSS
}
