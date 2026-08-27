package com.fleettracking.events;

/**
 * A conclusion this platform reached, rather than something a truck reported.
 *
 * <p>No source sends "arrived" — arrival is inferred by watching a position cross a geofence and
 * stay inside it. Derived events therefore have no {@link RawPayload}; what they have instead is
 * {@link #causedBy()}, naming the source event that triggered the conclusion.
 */
public sealed interface DerivedEvent extends Event
    permits ShipmentArrived, ShipmentDeparted, EtaUpdated, ExceptionRaised, ExceptionCleared {

  /**
   * The {@link Event#eventId()} of the event that caused this conclusion.
   *
   * <p>Two uses. It makes a derived event traceable back to the GPS fix that produced it, which is
   * the first thing anyone asks when an arrival looks wrong. And because a replayed source event
   * produces a derived event with the same {@code causedBy}, it gives consumers a natural
   * idempotency key when the processor restarts and re-reads part of the topic.
   */
  String causedBy();
}
