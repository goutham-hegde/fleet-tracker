package com.fleettracking.tracking.geofence;

import com.fleettracking.events.ShipmentArrived;
import com.fleettracking.events.ShipmentDeparted;
import java.util.Optional;

/**
 * What one position fix meant for one stop.
 *
 * <p>Returned rather than acted on, for the same reason the gateway's normalizers return a result
 * instead of throwing: deciding and doing are separate jobs, and only the first of them can be
 * tested without a database and a broker. Everything interesting about geofencing — hysteresis,
 * dwell, out-of-order fixes, whether this arrival has already been announced — lives in the
 * decision, so all of it is covered by unit tests that run in milliseconds.
 *
 * @param state what the state becomes. Never null: even a fix that changes nothing returns the
 *     state it was given, so a caller never has to reason about which field to read
 * @param changed whether {@code state} differs from what went in. The caller writes to the database
 *     only when this is true, which is what keeps a truck driving down the interstate from
 *     generating a write per stop per fix
 * @param arrival the arrival to publish, if this fix is the one that confirmed it
 * @param departure the departure to publish, if this fix is the one that confirmed it
 */
public record GeofenceDecision(
    GeofenceState state, boolean changed, ShipmentArrived arrival, ShipmentDeparted departure) {

  /** Nothing happened: no state change, nothing to publish. */
  public static GeofenceDecision unchanged(GeofenceState state) {
    return new GeofenceDecision(state, false, null, null);
  }

  /** The state moved on, but nothing is confirmed yet. */
  public static GeofenceDecision updated(GeofenceState state) {
    return new GeofenceDecision(state, true, null, null);
  }

  public Optional<ShipmentArrived> arrivalEvent() {
    return Optional.ofNullable(arrival);
  }

  public Optional<ShipmentDeparted> departureEvent() {
    return Optional.ofNullable(departure);
  }

  /** Whether this decision has something to put on the derived topic. */
  public boolean hasEvent() {
    return arrival != null || departure != null;
  }
}
