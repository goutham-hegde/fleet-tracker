package com.fleettracking.tracking.geofence;

import com.fleettracking.events.PositionEvent;
import com.fleettracking.tracking.itinerary.Itinerary;
import com.fleettracking.tracking.itinerary.ItineraryStore;
import com.fleettracking.tracking.itinerary.ScheduledStop;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies one stored position to every stop on its shipment's plan.
 *
 * <h2>The order of the three steps, and why it is that order</h2>
 *
 * <p>For each stop that changed, this publishes the derived event first and records the new state
 * second. There is no transaction spanning Kafka and MongoDB, so one of the two orders has to be
 * chosen and its failure mode accepted.
 *
 * <ul>
 *   <li><b>Record first, publish second.</b> A crash in between leaves a stop marked as announced
 *       whose arrival was never published. It is lost permanently and silently: nothing will ever
 *       revisit that stop, because the state says the job is done.
 *   <li><b>Publish first, record second.</b> A crash in between means the position is redelivered
 *       and the arrival is published again — and because the event id is derived from the shipment,
 *       the stop and the crossing instant, the second copy is byte-identical to the first.
 * </ul>
 *
 * <p>The second is chosen because a duplicate that every consumer already de-duplicates is a much
 * smaller problem than an arrival that silently never happened. "Exactly one arrival" survives as a
 * statement about distinct event ids, which is the only form of it that can outlive a power cut.
 *
 * <h2>Every stop, every fix</h2>
 *
 * <p>Rather than only the next unvisited stop. It costs a few lines of arithmetic per fix, and it
 * means the geofencer never has to be right about which stop a truck is heading for — a question
 * that is genuinely hard when a route is being run out of order or a driver diverts. A stop that
 * has been arrived at and departed from returns immediately, so a long itinerary does not get more
 * expensive as it is worked through.
 */
public class GeofenceService {

  private static final Logger log = LoggerFactory.getLogger(GeofenceService.class);

  private final ItineraryStore itineraries;
  private final GeofenceStateStore states;
  private final Geofencer geofencer;
  private final DerivedEventPublisher publisher;

  private final AtomicLong arrivals = new AtomicLong();
  private final AtomicLong departures = new AtomicLong();
  private final AtomicLong unplanned = new AtomicLong();

  public GeofenceService(
      ItineraryStore itineraries,
      GeofenceStateStore states,
      Geofencer geofencer,
      DerivedEventPublisher publisher) {
    this.itineraries = itineraries;
    this.states = states;
    this.geofencer = geofencer;
    this.publisher = publisher;
  }

  /**
   * Evaluates a position against its shipment's stops, publishing anything it confirms.
   *
   * <p>Called after the position has been stored, never before. The history is the record of what
   * was measured and must not depend on the platform having an opinion about it; if this throws,
   * the position is retried and re-evaluated, which is safe precisely because the derived ids are
   * derived.
   */
  public void apply(PositionEvent event) {
    Optional<Itinerary> plan = itineraries.forShipment(event.shipmentId());
    if (plan.isEmpty()) {
      // A load nobody planned. Not an error: the position is stored, there is simply nothing to
      // compare it against. Logged once per position would be far too loud on an unseeded database,
      // so it is counted and reported by the heartbeat instead.
      unplanned.incrementAndGet();
      return;
    }

    Map<String, GeofenceState> stored = states.forShipment(event.shipmentId());

    for (ScheduledStop stop : plan.get().stops()) {
      GeofenceState current =
          stored.getOrDefault(
              stop.stopId(), GeofenceState.initial(event.shipmentId(), stop.stopId()));

      GeofenceDecision decision = geofencer.evaluate(current, stop, event);
      if (!decision.changed()) {
        continue;
      }

      // Publish before recording. See the class note: this order can repeat an announcement, and
      // the other can lose one.
      decision.arrivalEvent().ifPresent(arrival -> {
        publisher.publish(arrival);
        arrivals.incrementAndGet();
      });
      decision.departureEvent().ifPresent(departure -> {
        publisher.publish(departure);
        departures.incrementAndGet();
      });

      states.save(decision.state());
    }
  }

  /** Arrivals announced by this process since it started. */
  public long arrivalCount() {
    return arrivals.get();
  }

  /** Departures announced by this process since it started. */
  public long departureCount() {
    return departures.get();
  }

  /** Positions seen for a shipment with no itinerary. A steadily rising count means unseeded data. */
  public long unplannedCount() {
    return unplanned.get();
  }

  /** A one-line summary, logged periodically by the heartbeat. */
  public String summary() {
    return "arrivals=" + arrivals.get()
        + " departures=" + departures.get()
        + " unplanned=" + unplanned.get();
  }
}
