package com.fleettracking.simulator.route;

import com.fleettracking.events.GeoPoint;
import java.time.Duration;
import java.util.List;

/**
 * The routes the simulator ships with — four real Indian freight lanes.
 *
 * <p>Held as code rather than as a config file on purpose, for now: these are fixtures the tests
 * assert against, and a compile error when one is malformed beats a startup failure. S5 may move
 * them to a resource once there is a reason to vary them without rebuilding.
 *
 * <p>Coordinates are city and industrial-estate centres, not real dock addresses, and the legs are
 * straight lines rather than the national highways they are named after. Neither matters for what
 * these are for: what has to be realistic is the <em>shape</em> of the work — leg lengths in the
 * right order of magnitude, dwell times that reflect what actually happens at a stop, and geofences
 * sized like the real facilities.
 *
 * <p>The four differ deliberately, because a fleet where every route is the same shape would let a
 * downstream bug hide. One is a multi-day long-haul with few stops, one is a temperature-controlled
 * pharma run, one is a dense multi-stop part-truckload circuit, and one is a short port shuttle that
 * repeats several times a day.
 *
 * <p><b>The first three characters of a lane id become the shipment id prefix</b> — {@code
 * del-bom-nh48} produces {@code SHP-DEL-0001}. The seeding scripts derive their reference data from
 * the same rule, so renaming a lane changes shipment ids everywhere and means re-running both seeds.
 */
public final class Lanes {

  private Lanes() {}

  /**
   * Retail DC replenishment down NH-48: Delhi to Mumbai via Jaipur and Ahmedabad. Roughly 1,200 km
   * of straight line and a day and a half of driving. Long legs, few stops, large yards at both
   * ends. The lane most likely to expose ETA drift over many hours.
   */
  public static final Route DELHI_MUMBAI =
      Route.of(
          "del-bom-nh48",
          "Delhi to Mumbai via Jaipur and Ahmedabad",
          List.of(
              yard("del-okhla", "Okhla DC", "Delhi", "DL", 28.5355, 77.2730, Duration.ofMinutes(75), Stop.StopKind.PICKUP),
              yard("jai-vki", "Jaipur VKI depot", "Jaipur", "RJ", 26.9124, 75.7873, Duration.ofMinutes(50), Stop.StopKind.DELIVERY),
              yard("amd-aslali", "Aslali crossdock", "Ahmedabad", "GJ", 23.0225, 72.5714, Duration.ofMinutes(65), Stop.StopKind.DELIVERY),
              yard("bom-bhiwandi", "Bhiwandi DC", "Bhiwandi", "MH", 19.2813, 73.0483, Duration.ofMinutes(90), Stop.StopKind.DELIVERY)));

  /**
   * Pharma cold chain south: Genome Valley outside Hyderabad to Bengaluru via Kurnool. India's
   * largest bulk-drug cluster feeding hospital pharmacies, so the freight is temperature-controlled
   * end to end — and this is the lane whose reefer readings M4's cold-chain SLA rules are graded on.
   * Both delivery points are kerbside hospital docks rather than yards.
   */
  public static final Route HYDERABAD_BENGALURU =
      Route.of(
          "hyd-blr-cold",
          "Hyderabad to Bengaluru via Kurnool, refrigerated",
          List.of(
              yard("hyd-genome", "Genome Valley depot", "Hyderabad", "TG", 17.6100, 78.5800, Duration.ofMinutes(100), Stop.StopKind.PICKUP),
              dock("knl-clinic", "Kurnool clinic dock", "Kurnool", "AP", 15.8281, 78.0373, Duration.ofMinutes(35), Stop.StopKind.DELIVERY),
              dock("blr-hosp", "Bengaluru hospital dock", "Bengaluru", "KA", 12.9716, 77.5946, Duration.ofMinutes(40), Stop.StopKind.DELIVERY)));

  /**
   * Part-truckload through the south: Bengaluru to Chennai with three intermediate customers. Short
   * legs and frequent dwells, so a truck on this lane spends much of its day stationary — which is
   * exactly the pattern that breaks a naive "moving means driving" geofence.
   */
  public static final Route BENGALURU_CHENNAI =
      Route.of(
          "blr-maa-ltl",
          "Bengaluru to Chennai, multi-stop part-truckload",
          List.of(
              yard("blr-peenya", "Peenya terminal", "Bengaluru", "KA", 13.0287, 77.5200, Duration.ofMinutes(60), Stop.StopKind.PICKUP),
              dock("hsr-cust", "Hosur customer", "Hosur", "TN", 12.7409, 77.8253, Duration.ofMinutes(25), Stop.StopKind.DELIVERY),
              dock("vlr-cust", "Vellore customer", "Vellore", "TN", 12.9165, 79.1325, Duration.ofMinutes(30), Stop.StopKind.DELIVERY),
              dock("spr-cust", "Sriperumbudur customer", "Sriperumbudur", "TN", 12.9675, 79.9430, Duration.ofMinutes(25), Stop.StopKind.DELIVERY),
              yard("maa-madhavaram", "Madhavaram terminal", "Chennai", "TN", 13.1477, 80.2350, Duration.ofMinutes(55), Stop.StopKind.DELIVERY)));

  /**
   * Port drayage inland: Nhava Sheva (JNPT) to the Chakan industrial belt outside Pune. A short,
   * repeatable shuttle — the lane to point a high event rate at when M9 wants throughput numbers
   * without waiting a day and a half for a route to end. The two-hour dwell at the port gate is
   * customs clearance and container handover, not loading.
   */
  public static final Route NHAVA_SHEVA_PUNE =
      Route.of(
          "bom-pnq-dray",
          "Nhava Sheva to Pune port drayage",
          List.of(
              yard("bom-jnpt", "Nhava Sheva port gate", "Navi Mumbai", "MH", 18.9490, 72.9490, Duration.ofMinutes(120), Stop.StopKind.PICKUP),
              yard("pnv-fuel", "Panvel fuel stop", "Panvel", "MH", 18.9894, 73.1175, Duration.ofMinutes(20), Stop.StopKind.WAYPOINT),
              yard("pnq-chakan", "Chakan bonded warehouse", "Chakan", "MH", 18.7606, 73.8636, Duration.ofMinutes(60), Stop.StopKind.DELIVERY)));

  /** Every lane the simulator knows, in a stable order. */
  public static final List<Route> ALL =
      List.of(DELHI_MUMBAI, HYDERABAD_BENGALURU, BENGALURU_CHENNAI, NHAVA_SHEVA_PUNE);

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
