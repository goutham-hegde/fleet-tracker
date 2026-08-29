package com.fleettracking.simulator.route;

import com.fleettracking.events.GeoPoint;
import java.time.Duration;
import java.util.List;

/**
 * The routes the simulator ships with — four real US freight lanes.
 *
 * <p>Held as code rather than as a config file on purpose, for now: these are fixtures the tests
 * assert against, and a compile error when one is malformed beats a startup failure. S5 may move
 * them to a resource once there is a reason to vary them without rebuilding.
 *
 * <p>Coordinates are city centres, not real dock addresses, and the legs are straight lines rather
 * than the interstates they are named after. Neither matters for what these are for: what has to be
 * realistic is the <em>shape</em> of the work — leg lengths in the right order of magnitude, dwell
 * times that reflect what actually happens at a stop, and geofences sized like the real facilities.
 *
 * <p>The four differ deliberately, because a fleet where every route is the same shape would let a
 * downstream bug hide. One is a long-haul with few stops, one is a dense multi-stop LTL run, one
 * crosses time zones and mountains, and one is a short high-frequency shuttle.
 */
public final class Lanes {

  private Lanes() {}

  /**
   * Retail DC replenishment down I-55 and I-44: Chicago to Dallas via St. Louis and Memphis.
   * Long legs, few stops, big yards. The lane most likely to expose ETA drift over hours.
   */
  public static final Route CHICAGO_DALLAS =
      Route.of(
          "chi-dal-i55",
          "Chicago to Dallas via St. Louis and Memphis",
          List.of(
              yard("chi-dc", "Chicago DC", "Chicago", "IL", 41.8781, -87.6298, Duration.ofMinutes(75), Stop.StopKind.PICKUP),
              yard("stl-xd", "St. Louis crossdock", "St. Louis", "MO", 38.6270, -90.1994, Duration.ofMinutes(50), Stop.StopKind.DELIVERY),
              yard("mem-hub", "Memphis hub", "Memphis", "TN", 35.1495, -90.0490, Duration.ofMinutes(65), Stop.StopKind.DELIVERY),
              yard("dal-dc", "Dallas DC", "Dallas", "TX", 32.7767, -96.7970, Duration.ofMinutes(90), Stop.StopKind.DELIVERY)));

  /**
   * Pharma cold chain west: Los Angeles to Denver via Phoenix. Mountain grades, long empty
   * stretches, and the lane whose reefer readings M4's cold-chain SLA rules will be graded on.
   */
  public static final Route LA_DENVER =
      Route.of(
          "lax-den-cold",
          "Los Angeles to Denver via Phoenix, refrigerated",
          List.of(
              yard("lax-pharma", "LA pharma depot", "Los Angeles", "CA", 34.0522, -118.2437, Duration.ofMinutes(100), Stop.StopKind.PICKUP),
              dock("phx-clinic", "Phoenix clinic dock", "Phoenix", "AZ", 33.4484, -112.0740, Duration.ofMinutes(35), Stop.StopKind.DELIVERY),
              dock("den-hosp", "Denver hospital dock", "Denver", "CO", 39.7392, -104.9903, Duration.ofMinutes(40), Stop.StopKind.DELIVERY)));

  /**
   * Less-than-truckload through the south-east: Atlanta to Columbus with three intermediate stops.
   * Short legs and frequent dwells, so a truck on this lane spends much of its day stationary —
   * which is exactly the pattern that breaks a naive "moving means driving" geofence.
   */
  public static final Route ATLANTA_COLUMBUS =
      Route.of(
          "atl-cmh-ltl",
          "Atlanta to Columbus, multi-stop LTL",
          List.of(
              yard("atl-term", "Atlanta terminal", "Atlanta", "GA", 33.7490, -84.3880, Duration.ofMinutes(60), Stop.StopKind.PICKUP),
              dock("cha-cust", "Chattanooga customer", "Chattanooga", "TN", 35.0456, -85.3097, Duration.ofMinutes(25), Stop.StopKind.DELIVERY),
              dock("nsh-cust", "Nashville customer", "Nashville", "TN", 36.1627, -86.7816, Duration.ofMinutes(30), Stop.StopKind.DELIVERY),
              dock("lou-cust", "Louisville customer", "Louisville", "KY", 38.2527, -85.7585, Duration.ofMinutes(25), Stop.StopKind.DELIVERY),
              yard("cmh-term", "Columbus terminal", "Columbus", "OH", 39.9612, -82.9988, Duration.ofMinutes(55), Stop.StopKind.DELIVERY)));

  /**
   * Cross-border drayage: Houston to Laredo. A short, repeatable shuttle — the lane to point a
   * high event rate at when M9 wants throughput numbers without waiting hours for a route to end.
   */
  public static final Route HOUSTON_LAREDO =
      Route.of(
          "hou-lrd-dray",
          "Houston to Laredo border drayage",
          List.of(
              yard("hou-port", "Port of Houston", "Houston", "TX", 29.7604, -95.3698, Duration.ofMinutes(45), Stop.StopKind.PICKUP),
              yard("sat-fuel", "San Antonio fuel stop", "San Antonio", "TX", 29.4241, -98.4936, Duration.ofMinutes(20), Stop.StopKind.WAYPOINT),
              yard("lrd-cross", "Laredo border crossing", "Laredo", "TX", 27.5306, -99.4803, Duration.ofMinutes(120), Stop.StopKind.DELIVERY)));

  /** Every lane the simulator knows, in a stable order. */
  public static final List<Route> ALL =
      List.of(CHICAGO_DALLAS, LA_DENVER, ATLANTA_COLUMBUS, HOUSTON_LAREDO);

  /** Looks a lane up by id. */
  public static Route byId(String id) {
    return ALL.stream()
        .filter(r -> r.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no such lane: " + id));
  }

  /** A large facility — a distribution centre or terminal, where a truck can park well off centre. */
  private static Stop yard(
      String id, String name, String city, String state, double lat, double lon, Duration dwell, Stop.StopKind kind) {
    return new Stop(id, name, city, state, new GeoPoint(lat, lon), 400, dwell, kind);
  }

  /** A single customer dock, where the geofence has to be tight enough to exclude passing traffic. */
  private static Stop dock(
      String id, String name, String city, String state, double lat, double lon, Duration dwell, Stop.StopKind kind) {
    return new Stop(id, name, city, state, new GeoPoint(lat, lon), 120, dwell, kind);
  }
}
