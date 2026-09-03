package com.fleettracking.tracking.itinerary;

import com.fleettracking.events.GeoPoint;

/**
 * One place a shipment is scheduled to visit, and how close counts as being there.
 *
 * <h2>Why the radius belongs to the stop</h2>
 *
 * <p>A distribution yard and a kerbside dock are not the same size. The yards on these lanes carry
 * a 400 m geofence and the docks 120 m, and both numbers are load-bearing in opposite directions: a
 * radius tight enough for the dock would leave a truck parked at the far fence of the yard looking
 * as though it had never arrived, while a radius wide enough for the yard would catch traffic
 * passing the dock on the street and announce an arrival for a truck that never stopped.
 *
 * <p>So this is reference data about the facility, not a setting of the geofencer. What the
 * geofencer contributes is judgement — how long a truck must stay before the arrival is believed,
 * and how far outside it must go before the departure is — and none of that is here.
 *
 * @param stopId stable identifier, and what goes on the derived event
 * @param seq position in the itinerary, from zero. Carried explicitly rather than left implicit in
 *     the list's order, so that a single stop read on its own still knows where it came in the plan
 * @param location the centre of the geofence
 * @param radiusMeters how close counts as at this stop
 * @param kind pickup, delivery or waypoint. Not used by the geofencer, which announces an arrival
 *     wherever the plan says a truck should stop; carried because an exception rule in M4 will care
 *     that a missed delivery is worse than a missed fuel stop
 */
public record ScheduledStop(
    String stopId,
    int seq,
    String name,
    String city,
    String state,
    double latitude,
    double longitude,
    double radiusMeters,
    String kind) {

  /** The stop's position, in the same shape the position events carry. */
  public GeoPoint location() {
    return new GeoPoint(latitude, longitude);
  }
}
