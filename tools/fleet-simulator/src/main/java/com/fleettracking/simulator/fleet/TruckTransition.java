package com.fleettracking.simulator.fleet;

import com.fleettracking.simulator.route.Stop;
import java.time.Instant;

/**
 * Something discrete that happened to a truck during a tick, as opposed to the continuous business
 * of moving.
 *
 * <p>These are the simulator's <b>ground truth</b>, and that is their whole point. In S5 a
 * departure becomes an EDI 214 message filed hours late with no coordinates, and an arrival becomes
 * a status ping from a phone that may never reach the platform at all. M3 then has to rediscover
 * these same events from nothing but a stream of noisy positions. Keeping the truth in a typed,
 * timestamped form means the geofencing tests can assert against what really happened rather than
 * against what a previous run of the geofencer decided.
 *
 * <p>Sealed for the same reason the event model is: a consumer switching over these is checked for
 * exhaustiveness at compile time, so adding a transition later breaks the builds that ignore it.
 */
public sealed interface TruckTransition {

  /** When it happened, in simulated time. */
  Instant at();

  /** The truck reached a stop and came to rest inside its geofence. */
  record Arrived(Stop stop, Instant at) implements TruckTransition {}

  /** The truck finished dwelling and pulled away from a stop. */
  record Departed(Stop stop, Instant at) implements TruckTransition {}

  /**
   * The truck finished dwelling at the <em>final</em> stop and has nothing left to do. Distinct
   * from {@link Departed} because there is no next leg to depart onto.
   */
  record RouteCompleted(Stop finalStop, Instant at) implements TruckTransition {}
}
