package com.fleettracking.tracking.geofence;

import com.fleettracking.tracking.itinerary.Itinerary;
import com.fleettracking.tracking.itinerary.ScheduledStop;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * How far through its plan a shipment is, as of the fix just applied.
 *
 * <h2>Why this is handed on rather than looked up again</h2>
 *
 * <p>Estimating an arrival needs two things geofencing has just finished working out: which stop
 * the vehicle is heading for, and whether it is currently sitting inside a fence. Both are already
 * in hand at the end of {@link GeofenceService#apply} — the itinerary was read, every stop was
 * evaluated, and the resulting states are the answer. Asking the database for them a second time,
 * on the busiest path in the platform, would be paying twice for a fact that was true a microsecond
 * ago.
 *
 * <p>It carries the states <em>after</em> the fix was applied, not before. That matters at the
 * moment of a departure: the fix that concludes a truck has left a stop is also the first fix of
 * the leg to the next one, and an ETA built from the pre-fix view would spend one more event
 * pointing at the stop the truck has already left.
 *
 * @param itinerary the stops this shipment is scheduled to visit
 * @param states what is believed about each of those stops, keyed by stop id. A stop the vehicle
 *     has never been near is simply absent
 */
public record ShipmentProgress(Itinerary itinerary, Map<String, GeofenceState> states) {

  /**
   * The next stop the shipment has not yet been announced as arriving at.
   *
   * <p>By plan order rather than by distance. A truck can be closer to a stop it has already served
   * than to the one it is driving to — the lane through Jaipur passes within a few kilometres of
   * places it left hours ago — so "nearest" is the wrong question. Empty once every stop has been
   * arrived at, which is a finished shipment with nothing left to estimate.
   */
  public Optional<ScheduledStop> nextStop() {
    return itinerary.stops().stream()
        .sorted(Comparator.comparingInt(ScheduledStop::seq))
        .filter(stop -> !announcedArrival(stop.stopId()))
        .findFirst();
  }

  /**
   * Whether the last trusted fix put the vehicle inside any of its stops' geofences.
   *
   * <p>Used to suppress the estimate entirely while a truck is at a stop. Nothing in this platform
   * knows how long a load takes to work — the seeded plan carries no service times — so an ETA to
   * the next stop published while the truck is still on a dock would be the sum of a real driving
   * time and an invented wait. The estimate resumes at the departure, which is the first moment the
   * question has an honest answer.
   */
  public boolean atAStop() {
    return states.values().stream().anyMatch(GeofenceState::inside);
  }

  private boolean announcedArrival(String stopId) {
    GeofenceState state = states.get(stopId);
    return state != null && state.arrivalAnnounced();
  }
}
