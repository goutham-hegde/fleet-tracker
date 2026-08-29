package com.fleettracking.simulator.route;

import com.fleettracking.events.GeoPoint;
import java.time.Duration;
import java.util.Objects;

/**
 * A scheduled place a truck stops: where it is, what happens there, and how long that takes.
 *
 * <p><b>{@code city} and {@code state} are not decoration.</b> EDI 214 carries no coordinates at
 * all — it names a place in text and leaves the reader to work out where that is. So when S5 emits
 * an EDI 214 message for an arrival at this stop, these two fields are the <em>only</em> location
 * information that goes on the wire, and the coordinates stay behind as the ground truth that the
 * gateway's geocoder will later be graded against. A stop whose city and state do not honestly
 * match its coordinates would make that test meaningless.
 *
 * <p>{@code geofenceRadiusMeters} likewise belongs to the stop rather than to a global setting: a
 * 400-acre distribution centre and a downtown loading dock are not the same size, and M3's
 * geofencing has to cope with both. A large yard needs a radius wide enough that a truck parked at
 * the far fence still counts as arrived; a kerbside dock needs one tight enough that traffic
 * passing on the street does not.
 *
 * @param id stable identifier, used as the stop reference in emitted events
 * @param name human-readable, e.g. "Chicago DC 4"
 * @param city the city as a carrier would file it in EDI — no abbreviations
 * @param state two-letter US state code
 * @param location ground truth position; the geofence is centred here
 * @param geofenceRadiusMeters how close counts as "at" this stop
 * @param dwell how long the truck stays once it arrives — loading, paperwork, a driver break
 * @param kind what the truck is there to do
 */
public record Stop(
    String id,
    String name,
    String city,
    String state,
    GeoPoint location,
    double geofenceRadiusMeters,
    Duration dwell,
    StopKind kind) {

  public Stop {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(city, "city");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(dwell, "dwell");
    Objects.requireNonNull(kind, "kind");
    if (geofenceRadiusMeters <= 0) {
      throw new IllegalArgumentException("geofenceRadiusMeters must be positive: " + geofenceRadiusMeters);
    }
    if (dwell.isNegative()) {
      throw new IllegalArgumentException("dwell must not be negative: " + dwell);
    }
  }

  /** What the truck is at this stop for. */
  public enum StopKind {
    /** Freight is loaded here. Usually the first stop on a route. */
    PICKUP,
    /** Freight comes off here. A multi-stop route has several. */
    DELIVERY,
    /** Neither — a fuel stop, a scale, or a mandated rest break. No freight event. */
    WAYPOINT
  }
}
