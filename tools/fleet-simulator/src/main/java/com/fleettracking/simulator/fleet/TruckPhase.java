package com.fleettracking.simulator.fleet;

/**
 * What a truck is doing right now.
 *
 * <p>Only three states, and the constraint that keeps them honest is that a truck is <em>always</em>
 * in exactly one: there is no "arriving" or "about to leave". Arrival and departure are instants,
 * not phases, and they surface as {@link TruckTransition}s rather than as states — which is the
 * same shape M3's geofencing has to produce downstream, where the hard requirement is exactly one
 * arrival and exactly one departure per stop.
 */
public enum TruckPhase {

  /** Moving along the current leg toward the next stop. */
  DRIVING,

  /** Stationary at a stop, working through its dwell time — loading, paperwork, a rest break. */
  DWELLING,

  /** Finished the last stop's dwell. The truck stops emitting movement and never restarts. */
  COMPLETED
}
