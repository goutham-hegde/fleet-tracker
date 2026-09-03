package com.fleettracking.tracking.itinerary;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;

/**
 * Reads a shipment's scheduled stops.
 *
 * <h2>One lookup per position event, and no cache</h2>
 *
 * <p>This is the hottest read in the platform: every position event asks it. A cache is the obvious
 * thought, and it is the same thought S8 had about identity resolution, where it was rejected for
 * reasons that apply again here. The lookup is by primary key against a database on the same
 * cluster, which is about as cheap as a database read gets; the service already performs one or two
 * <em>writes</em> per event, so this is a small addition to a cost that is already dominated by
 * something else. Caching would buy a staleness window — a period in which the geofencer knowingly
 * uses a plan that has been re-routed — to save a cost that is not the bottleneck.
 *
 * <p>Kept behind this class rather than called inline, so that if profiling ever disagrees, a
 * caching implementation is a new class and a changed bean rather than a change to the geofencer.
 *
 * <h2>A shipment with no itinerary is not an error</h2>
 *
 * <p>It means the platform has positions for a load nobody planned — a truck the simulator
 * provisioned beyond the seeded fleet size, or, in a real deployment, a load whose plan has not
 * arrived from the TMS yet. The position is still recorded; there is simply nothing to geofence it
 * against. Treating that as a failure would stall a partition over reference data that may turn up
 * a minute later, which is a much worse outcome than not yet knowing where a truck is meant to go.
 */
public class ItineraryStore {

  private static final Logger log = LoggerFactory.getLogger(ItineraryStore.class);

  private final MongoOperations mongo;

  public ItineraryStore(MongoOperations mongo) {
    this.mongo = mongo;
  }

  /** The stops this shipment is scheduled to visit, if anything has planned it. */
  public Optional<Itinerary> forShipment(String shipmentId) {
    if (shipmentId == null || shipmentId.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(mongo.findById(shipmentId, Itinerary.class));
  }

  /** How many shipments have a plan. Logged at startup, for the same reason S8 logs its count. */
  public long count() {
    return mongo.getCollection(Itinerary.COLLECTION).countDocuments();
  }
}
