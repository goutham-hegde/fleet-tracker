package com.fleettracking.tracking.geofence;

import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.ShipmentArrived;
import com.fleettracking.events.ShipmentDeparted;
import com.fleettracking.tracking.itinerary.ScheduledStop;
import java.time.Duration;
import java.time.Instant;

/**
 * Decides, from noisy positions alone, that a vehicle has arrived at a stop and later left it.
 *
 * <h2>What makes this hard</h2>
 *
 * <p>Not the geometry. Distance from a point to a point is arithmetic. The difficulty is that the
 * input lies a little, all the time, and the output must be exactly right: one arrival per stop and
 * one departure, no matter how the fixes wobble.
 *
 * <p>GPS noise is on by default at six metres and is not a fault — it is what GPS does. A truck
 * parked with its cab on the fence line produces fixes that fall inside, outside, inside, outside,
 * for as long as it sits there. A geofence that simply asks "is the distance less than the radius"
 * would announce an arrival and a departure for each wobble, and would produce dozens of both for a
 * single genuine stop.
 *
 * <h2>Three defences, none of which is sufficient alone</h2>
 *
 * <ol>
 *   <li><b>Hysteresis.</b> Coming in requires crossing the radius; going out requires crossing a
 *       wider one. Between the two lies a band that no amount of noise can toggle, because entering
 *       and leaving are no longer the same threshold. On a 400 m yard the band is 100 m wide,
 *       against noise measured in metres. This is the defence that does most of the work, and it
 *       costs nothing at all.
 *   <li><b>Dwell.</b> Crossing in is not arriving. The vehicle must still be inside some minutes
 *       later before the arrival is believed, which is what separates a truck that has parked from
 *       one that drove past the gate or queued at the light outside it. The same threshold is
 *       applied on the way out, so a fix that strays beyond the outer radius and comes straight
 *       back is not a departure.
 *   <li><b>An accuracy gate.</b> Every fix states how good it thinks it is. One whose own error bar
 *       is a large fraction of the geofence cannot answer the question being asked of it, so it is
 *       not consulted — neither to enter nor to leave. This is why {@code accuracyMeters} is
 *       carried all the way through the canonical event into the stored measurement.
 * </ol>
 *
 * <h2>Time is event time, and it only moves forwards</h2>
 *
 * <p>Every threshold here is measured against the instants the fixes carry, never against the
 * clock. That is what lets the whole thing run under a simulator at sixty times speed and behave
 * identically, and it is what makes a replay of the topic produce the same arrivals as the live run
 * did.
 *
 * <p>It also means out-of-order fixes have to be refused rather than absorbed. The mobile app
 * buffers through a dead zone and then dumps its backlog in whatever order it comes out, so a fix
 * twenty minutes old will arrive after a current one. Feeding that to a state machine walks it
 * backwards: the vehicle un-arrives. Anything not newer than the newest fix already applied to this
 * stop is therefore ignored. The measurement is still stored — the history keeps every fix — but it
 * does not get a vote on where the truck is now.
 */
public class Geofencer {

  /**
   * How much further out the vehicle must be to count as having left, as a multiple of the radius.
   *
   * <p>1.25 rather than something larger because the band has a cost: a truck that leaves is not
   * seen to leave until it is a quarter of a radius beyond the fence, which on a small dock is
   * thirty metres and on a large yard a hundred. That is a few seconds of driving, and the
   * departure is stamped with the instant it crossed out rather than the instant we noticed.
   */
  public static final double EXIT_RATIO = 1.25;

  /**
   * The worst reported accuracy a fix may have and still be consulted, as a fraction of the radius.
   *
   * <p>A quarter. On a 120 m dock that rejects anything claiming worse than 30 m, which is a fix
   * that genuinely cannot tell whether it is at the dock or on the road outside it.
   */
  public static final double ACCURACY_RATIO = 0.25;

  private final Duration dwellThreshold;

  public Geofencer(Duration dwellThreshold) {
    if (dwellThreshold == null || dwellThreshold.isNegative()) {
      throw new IllegalArgumentException("dwellThreshold must not be negative: " + dwellThreshold);
    }
    this.dwellThreshold = dwellThreshold;
  }

  /**
   * Applies one position fix to one stop.
   *
   * @param state what is currently believed about this shipment at this stop
   * @param stop the scheduled stop, carrying its own geofence radius
   * @param event a position event that has already been validated and stored
   * @return the new state, whether it changed, and anything now confirmed
   */
  public GeofenceDecision evaluate(GeofenceState state, ScheduledStop stop, PositionEvent event) {
    Instant at = event.occurredAt();

    // A stop that has been arrived at and departed from is finished. Returning early also means a
    // completed stop costs one comparison per fix for the rest of the run rather than a distance
    // calculation and a write.
    if (state.isComplete()) {
      return GeofenceDecision.unchanged(state);
    }

    // Out of order, or a repeat of the newest fix. See the note on event time above.
    if (state.lastFixAt() != null && !at.isAfter(state.lastFixAt())) {
      return GeofenceDecision.unchanged(state);
    }

    // The fix does not know where it is well enough to be asked about this fence. Note that
    // lastFixAt is deliberately not advanced: an untrusted fix leaves no trace at all, so a later
    // trustworthy fix bearing an earlier instant is still considered.
    if (event.accuracyMeters() != null
        && event.accuracyMeters() > stop.radiusMeters() * ACCURACY_RATIO) {
      return GeofenceDecision.unchanged(state);
    }

    double distance = Distance.metersBetween(event.position(), stop.location());

    // The hysteresis. Which threshold applies depends on which side we are currently on, and that
    // asymmetry is the entire defence against a fix wobbling across a single line.
    boolean nowInside =
        state.inside()
            ? distance <= stop.radiusMeters() * EXIT_RATIO
            : distance < stop.radiusMeters();

    // Far from this fence, and with no arrival outstanding: there is nothing to remember. Note
    // that this returns the state untouched rather than merely recording the fix, which is the
    // difference between a truck on an eight-hour leg writing nothing and it writing once per stop
    // per fix. lastFixAt is not advanced either, and does not need to be — the out-of-order guard
    // protects a state machine that is mid-visit, and this one has not started.
    if (!nowInside && !state.inside() && !state.arrivalAnnounced()) {
      return GeofenceDecision.unchanged(state);
    }

    boolean entering = nowInside && !state.inside();
    boolean leaving = !nowInside && state.inside();

    Instant enteredAt = entering ? at : state.enteredAt();
    Instant leftAt = leaving ? at : state.leftAt();

    ShipmentArrived arrival = null;
    ShipmentDeparted departure = null;
    boolean arrivalAnnounced = state.arrivalAnnounced();
    boolean departureAnnounced = state.departureAnnounced();
    Instant arrivalOccurredAt = state.arrivalOccurredAt();

    if (nowInside && !arrivalAnnounced && enteredAt != null && dwelledSince(enteredAt, at)) {
      // Stamped with when it crossed in, not with now. Those differ by the dwell threshold, and
      // using now would make every arrival on the platform look minutes late against its schedule.
      arrival = arrivalAt(event, stop, enteredAt);
      arrivalAnnounced = true;
      arrivalOccurredAt = enteredAt;
    }

    if (!nowInside
        && arrivalAnnounced
        && !departureAnnounced
        && leftAt != null
        && dwelledSince(leftAt, at)) {
      departure = departureAt(event, stop, leftAt, arrivalOccurredAt);
      departureAnnounced = true;
    }

    GeofenceState next =
        new GeofenceState(
            state.id(),
            state.shipmentId(),
            state.stopId(),
            nowInside,
            enteredAt,
            leftAt,
            arrivalAnnounced,
            arrivalOccurredAt,
            departureAnnounced,
            at);

    return new GeofenceDecision(next, !next.equals(state), arrival, departure);
  }

  /** Whether enough event time has passed since a boundary crossing to believe it. */
  private boolean dwelledSince(Instant crossing, Instant now) {
    return !Duration.between(crossing, now).minus(dwellThreshold).isNegative();
  }

  private ShipmentArrived arrivalAt(PositionEvent event, ScheduledStop stop, Instant enteredAt) {
    return new ShipmentArrived(
        DerivedEventIds.arrival(event.shipmentId(), stop.stopId(), enteredAt),
        event.shipmentId(),
        enteredAt,
        // What made us conclude it: the fix that confirmed the dwell. Not the fix that crossed the
        // boundary, which is minutes earlier and may not be the one anybody still has to hand.
        event.eventId(),
        stop.stopId(),
        stop.location(),
        // The plan does not carry scheduled times yet, so lateness cannot be stated here. Null
        // rather than a guess: the field is optional precisely so that a platform which does not
        // know the schedule says so instead of inventing one.
        null);
  }

  private ShipmentDeparted departureAt(
      PositionEvent event, ScheduledStop stop, Instant leftAt, Instant arrivalOccurredAt) {
    return new ShipmentDeparted(
        DerivedEventIds.departure(event.shipmentId(), stop.stopId(), leftAt),
        event.shipmentId(),
        leftAt,
        event.eventId(),
        stop.stopId(),
        stop.location(),
        // Detention time, computed once here rather than left for every consumer to reconstruct by
        // joining two events. It is a billable quantity and deserves one definition.
        Duration.between(arrivalOccurredAt, leftAt));
  }
}
