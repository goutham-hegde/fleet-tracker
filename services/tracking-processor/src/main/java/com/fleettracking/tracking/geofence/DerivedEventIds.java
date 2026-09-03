package com.fleettracking.tracking.geofence;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds the {@code eventId} for an event this platform concluded, rather than received.
 *
 * <h2>The same rule as the gateway's, for a different reason</h2>
 *
 * <p>The gateway derives ids so that a message a carrier sent twice produces one event. Nothing
 * sends an arrival twice — this service invents it. What it is defending against is subtler and
 * more dangerous.
 *
 * <p>Publishing an arrival and recording that it was published are two separate operations against
 * two separate systems, and there is no transaction across them. Whichever order they go in, a
 * crash in between leaves them disagreeing. Publish first and the arrival can be announced twice.
 * Record first and it can be announced never — which is worse, because a lost arrival is invisible
 * while a repeated one is at least loud.
 *
 * <p>So this service publishes first, and makes the repeat harmless: the id is derived from the
 * shipment, the stop and the instant the vehicle crossed the boundary, none of which change when
 * the work is redone. A re-announced arrival is byte-identical to the first, so every consumer that
 * de-duplicates on id — which, after S9, is how this platform handles a repeat everywhere — sees
 * one arrival. "Exactly one arrival" is therefore a statement about distinct event ids, which is
 * the only version of it that can survive a machine losing power.
 *
 * <p>Nothing derived from wall-clock time may appear in the inputs, for exactly the reason the
 * gateway excludes arrival time: it would make every retry unique again and defeat the whole
 * mechanism.
 */
public final class DerivedEventIds {

  private DerivedEventIds() {}

  /** The id of the arrival concluded for a shipment at a stop it entered at a given instant. */
  public static String arrival(String shipmentId, String stopId, Instant enteredAt) {
    return of("ARRIVED", shipmentId, stopId, enteredAt);
  }

  /** The id of the departure concluded for a shipment leaving a stop at a given instant. */
  public static String departure(String shipmentId, String stopId, Instant leftAt) {
    return of("DEPARTED", shipmentId, stopId, leftAt);
  }

  /**
   * The kind is part of the name so that an arrival and a departure cannot collide. They otherwise
   * could: a stop a vehicle passed straight through would have the same shipment, the same stop and
   * very nearly the same instant for both.
   */
  private static String of(String kind, String shipmentId, String stopId, Instant at) {
    String name =
        String.join(
            "|",
            kind,
            shipmentId == null ? "" : shipmentId,
            stopId == null ? "" : stopId,
            at == null ? "" : at.toString());
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
  }
}
