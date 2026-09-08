package com.fleettracking.dashboard.read;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything about one shipment: what M5's detail panel opens when a marker is clicked.
 *
 * <h2>The summary is embedded rather than flattened</h2>
 *
 * <p>So the fields a marker shows and the fields a panel shows are provably the same values, out of
 * the same assembly. The alternative — a detail record repeating twenty fields — is two statements
 * of one truth, and the failure mode is a panel that disagrees with the marker it was opened from
 * about where the truck is.
 *
 * @param manifest what is in the truck, or absent for a load nobody filed paperwork for. Absence is
 *     not an error here: as of M4 the simulator produces no manifests, so they exist only where the
 *     seed script or a person put one
 * @param stops the plan, with what actually happened at each stop folded in
 * @param exceptions every incident against this load, open and cleared, newest first
 * @param track recent positions, oldest first, for drawing the trail behind the marker
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShipmentDetail(
    ShipmentSummary summary,
    ManifestDetail manifest,
    List<PlannedStop> stops,
    List<ShipmentSummary.IncidentSummary> exceptions,
    List<TrackPoint> track) {

  /**
   * The manifest, passed through untouched.
   *
   * <p>{@code body} is a raw map and stays one all the way to the browser. This is M4's argument
   * arriving at a screen: a pharma cold-chain manifest, a retail replenishment, an LTL bill and a
   * parcel share no fields at all, and one endpoint renders all four because nothing between the
   * customer's order system and the panel has an opinion about what is inside. A typed detail record
   * here would have to be the union of four customers' contracts, and would need a redeploy to
   * onboard a fifth — which is exactly what S12 removed.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ManifestDetail(
      String customerId,
      String mode,
      String schemaVersion,
      Instant createdAt,
      Map<String, Object> body) {}

  /**
   * One stop in the plan, and what happened there.
   *
   * @param status where this stop has got to. {@code PENDING} until the truck arrives, {@code AT}
   *     while it is there, {@code DEPARTED} once it has left
   * @param arrivedAt the instant the vehicle crossed into the geofence, not the instant the dwell
   *     threshold made the platform sure of it. Those differ by three minutes, and reporting the
   *     later one would make every arrival look late against its schedule
   * @param dwellSeconds how long the truck stayed, once it has left
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PlannedStop(
      String stopId,
      int seq,
      String name,
      String city,
      String state,
      double latitude,
      double longitude,
      double radiusMeters,
      String kind,
      String status,
      Instant arrivedAt,
      Instant departedAt,
      Long dwellSeconds) {

    /** Not reached yet. */
    public static final String PENDING = "PENDING";

    /** Arrived at, not left. */
    public static final String AT = "AT";

    /** Arrived at and left. Terminal, as the geofencer treats it. */
    public static final String DEPARTED = "DEPARTED";
  }

  /**
   * One stored measurement, thinned to what a line on a map needs.
   *
   * <p>The trail comes from the time-series collection rather than from the live stream, because a
   * browser that has just connected has no history at all: the stream begins at the moment it
   * subscribed. Loading a snapshot and then following the stream is the whole shape of this API.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TrackPoint(
      Instant ts, double latitude, double longitude, Double speedKph, String source) {}
}
