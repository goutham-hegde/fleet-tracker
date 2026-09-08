package com.fleettracking.dashboard.read;

import com.fleettracking.reference.Itinerary;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Every read this service makes, and the reason there are five of them rather than five per truck.
 *
 * <h2>The fleet view is five queries, not five times sixty-four</h2>
 *
 * <p>The obvious way to build a fleet view is a loop: for each shipment, fetch its position, its
 * geofence states, its estimate and its incidents. That is four round trips per truck, which for
 * sixty-four trucks is two hundred and fifty-six — every few seconds, for every connected browser.
 * It works perfectly in a test with three shipments and falls over exactly when somebody is
 * watching.
 *
 * <p>So each collection is read once, for every shipment at once, and the join happens in memory in
 * {@link FleetAssembler}. The cost is five queries regardless of fleet size. This is the one place
 * in the platform where that shape is right: everywhere else — identity resolution, the itinerary
 * lookup, the schema read — a service handles one event at a time and a per-key lookup is the
 * cheapest thing available. Here a single request genuinely is about the whole fleet.
 *
 * <h2>Why nothing here caches</h2>
 *
 * <p>Not for the reason the other stores give. {@code MongoIdentityResolver}, {@code ItineraryStore}
 * and {@code SchemaStore} refuse to cache because they read reference data somebody else maintains
 * and a cache would buy speed with staleness. This service reads data that changes several times a
 * second by design, and a viewer's whole reason for looking is to see it change. A cache here would
 * not be a trade-off, it would be the feature turned off.
 */
public class FleetReader {

  private final MongoOperations mongo;
  private final int fleetLimit;

  public FleetReader(MongoOperations mongo, int fleetLimit) {
    this.mongo = mongo;
    this.fleetLimit = fleetLimit;
  }

  /**
   * Every shipment the platform currently has a position for, newest news first.
   *
   * <p>Capped, because this endpoint is a screen rather than an export. A fleet larger than the cap
   * is a real possibility and the honest failure is to show the most recently heard-from trucks and
   * say how many there are, rather than to serialize ten thousand markers into a browser that will
   * draw none of them.
   */
  public List<Views.PositionView> positions() {
    Query query = new Query().with(Sort.by(Sort.Direction.DESC, "updatedAt")).limit(fleetLimit);
    return mongo.find(query, Views.PositionView.class);
  }

  /** One shipment's position, or null if the platform has never had one. */
  public Views.PositionView position(String shipmentId) {
    return mongo.findById(shipmentId, Views.PositionView.class);
  }

  /** How many shipments have a current position at all. */
  public long trackedCount() {
    return mongo.getCollection("shipment.position").countDocuments();
  }

  /**
   * The plans for these shipments, keyed by shipment.
   *
   * <p>Reads the {@code Itinerary} record from {@code libs/reference} rather than a projection of
   * its own: this is genuinely shared reference data with one definition, which is the difference
   * between it and the four documents in {@link Views}. A shipment with no plan is simply absent
   * from the map, and callers treat that as "unplanned" rather than as an error.
   */
  public Map<String, Itinerary> itineraries(Collection<String> shipmentIds) {
    if (shipmentIds.isEmpty()) {
      return Map.of();
    }
    Query query = new Query(Criteria.where("_id").in(shipmentIds));
    return mongo.find(query, Itinerary.class).stream()
        .collect(Collectors.toMap(Itinerary::shipmentId, Function.identity(), (a, b) -> a));
  }

  /**
   * What is believed about each of these shipments' stops, grouped by shipment.
   *
   * <p>One document per shipment-and-stop, so this returns rather more rows than there are trucks —
   * up to the length of an itinerary each. The query is on the indexed {@code shipmentId} field,
   * which is the field the tracking processor indexed for exactly this shape of read.
   */
  public Map<String, List<Views.GeofenceView>> geofenceStates(Collection<String> shipmentIds) {
    if (shipmentIds.isEmpty()) {
      return Map.of();
    }
    Query query = new Query(Criteria.where("shipmentId").in(shipmentIds));
    return mongo.find(query, Views.GeofenceView.class).stream()
        .collect(Collectors.groupingBy(Views.GeofenceView::shipmentId));
  }

  /** The last published estimate for each of these shipments. */
  public Map<String, Views.EtaView> estimates(Collection<String> shipmentIds) {
    if (shipmentIds.isEmpty()) {
      return Map.of();
    }
    Query query = new Query(Criteria.where("_id").in(shipmentIds));
    return mongo.find(query, Views.EtaView.class).stream()
        .collect(Collectors.toMap(Views.EtaView::shipmentId, Function.identity(), (a, b) -> a));
  }

  /**
   * Incidents for these shipments, grouped by shipment.
   *
   * @param openOnly whether to read only what is currently wrong. The fleet view asks for open
   *     incidents because a marker shows what needs attention now; the detail panel asks for
   *     everything, because what went wrong earlier and recovered is the more interesting half
   */
  public Map<String, List<Views.IncidentView>> incidents(
      Collection<String> shipmentIds, boolean openOnly) {
    if (shipmentIds.isEmpty()) {
      return Map.of();
    }
    Criteria criteria = Criteria.where("shipmentId").in(shipmentIds);
    if (openOnly) {
      criteria = criteria.and("state").is("OPEN");
    }
    return mongo.find(new Query(criteria), Views.IncidentView.class).stream()
        .collect(Collectors.groupingBy(Views.IncidentView::shipmentId));
  }

  /** Every incident on the platform, open first and newest first, capped. */
  public List<Views.IncidentView> allIncidents(boolean openOnly, int limit) {
    Query query = new Query();
    if (openOnly) {
      query.addCriteria(Criteria.where("state").is("OPEN"));
    }
    query.with(Sort.by(Sort.Direction.DESC, "onsetAt")).limit(limit);
    return mongo.find(query, Views.IncidentView.class);
  }

  /** How many incidents are open right now. */
  public long openIncidentCount() {
    return mongo.count(new Query(Criteria.where("state").is("OPEN")), Views.IncidentView.class);
  }

  /** The manifest for one shipment, or null when nobody filed paperwork for this load. */
  public Views.ManifestView manifest(String shipmentId) {
    return mongo.findById(shipmentId, Views.ManifestView.class);
  }

  /**
   * The most recent stored positions for one shipment, oldest first.
   *
   * <p>Queried newest-first with a limit and then reversed, which is the only way to ask for "the
   * last N" — a query sorted oldest-first with a limit returns the <em>first</em> N, which for a
   * shipment that has been running all day is where it started rather than where it has been.
   *
   * <p>This is the one read that touches the time-series collection, and it is the shape that
   * collection is built for: one series, identified by the {@code metaField}, sliced by the
   * {@code timeField}.
   */
  public List<Views.TrackPointView> track(String shipmentId, int limit) {
    Query query =
        new Query(Criteria.where("shipmentId").is(shipmentId))
            .with(Sort.by(Sort.Direction.DESC, "ts"))
            .limit(limit);
    List<Views.TrackPointView> newestFirst = mongo.find(query, Views.TrackPointView.class);
    return newestFirst.stream()
        .sorted(Comparator.comparing(Views.TrackPointView::ts))
        .toList();
  }
}
