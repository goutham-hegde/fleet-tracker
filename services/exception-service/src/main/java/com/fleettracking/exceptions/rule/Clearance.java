package com.fleettracking.exceptions.rule;

import com.fleettracking.events.ExceptionType;
import java.time.Instant;

/**
 * A rule saying the condition behind an exception no longer holds.
 *
 * <p>It names the incident by its coordinates — type, shipment, stop — rather than by its id,
 * because the rule that clears may never have seen the raise. A different process may have raised
 * it, or this one may have restarted since. Looking it up by what it is about is what makes the two
 * halves of an incident survive being handled by different instances.
 *
 * @param at when the condition stopped holding, in event time. Becomes the cleared event's
 *     {@code occurredAt}, and with the incident's onset it is what {@code openFor} is computed from
 * @param causedBy the event that showed the condition had resolved. Traceable in the same way a
 *     raise is
 * @param resolution a couple of words on how it ended. The vocabulary is per rule and deliberately
 *     free text — {@code ExceptionCleared} explains that an enum guessed before the rules existed
 *     would be wrong and would need migrating. After S13 there are two shapes of ending worth
 *     distinguishing: the condition genuinely recovered, or the shipment moved past the point where
 *     the question could still be asked
 */
public record Clearance(
    ExceptionType type,
    String shipmentId,
    String stopId,
    Instant at,
    String causedBy,
    String resolution) {

  /** The condition genuinely went away: the reefer recovered, the truck moved off again. */
  public static final String RECOVERED = "RECOVERED";

  /** The truck started moving after an unscheduled halt. */
  public static final String RESUMED = "RESUMED";

  /** The vehicle came back inside the planned corridor. */
  public static final String BACK_ON_ROUTE = "BACK_ON_ROUTE";

  /** A device that had gone quiet started reporting again. */
  public static final String REPORTING_AGAIN = "REPORTING_AGAIN";

  /** A revised estimate brought the projected arrival back inside the booked window. */
  public static final String BACK_WITHIN_WINDOW = "BACK_WITHIN_WINDOW";

  /** The shipment reached the stop in time, so the projection stopped mattering. */
  public static final String ARRIVED_ON_TIME = "ARRIVED_ON_TIME";

  /**
   * The shipment reached the stop after the window closed.
   *
   * <p>A resolution rather than a permanently open incident, and the distinction is the point of
   * having free text here. Nothing about a late delivery ever "recovers" — but the incident is over,
   * because there is no longer a question about whether the truck will make it. Leaving it open
   * would mean a dashboard where every completed late delivery stays red for ever, which is the
   * failure mode the whole raise-and-clear model exists to avoid.
   */
  public static final String ARRIVED_LATE = "ARRIVED_LATE";

  /** The shipment finished its itinerary, so silence from its device is expected. */
  public static final String SHIPMENT_COMPLETED = "SHIPMENT_COMPLETED";
}
