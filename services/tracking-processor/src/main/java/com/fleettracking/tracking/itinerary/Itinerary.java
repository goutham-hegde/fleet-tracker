package com.fleettracking.tracking.itinerary;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The stops one shipment is scheduled to visit, in order.
 *
 * <p>Seeded by {@code scripts/seed-itinerary.sh} from the committed lane catalogue. In a real
 * deployment it would arrive from a transport management system; nothing about the collection or
 * the query would change, which is the point of seeding it rather than compiling it in.
 *
 * <p>The document id is the shipment id, so the only lookup this collection serves is answered by
 * the primary key. No index is declared anywhere for it, and that is deliberate rather than
 * forgotten.
 */
@Document(collection = Itinerary.COLLECTION)
public record Itinerary(@Id String shipmentId, String routeId, List<ScheduledStop> stops) {

  public static final String COLLECTION = "itinerary";
}
