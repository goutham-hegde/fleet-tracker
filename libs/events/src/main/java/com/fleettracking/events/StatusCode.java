package com.fleettracking.events;

/**
 * What a {@link StatusEvent} is asserting about a shipment.
 *
 * <p>Deliberately source-agnostic. EDI 214 has its own status vocabulary — {@code AF} for departed
 * pickup, {@code X1} for arrived at delivery, and so on — and it is tempting to record those codes
 * here so the mapping is visible in one place. That would be a mistake: the canonical model would
 * then know about one of its four sources, and the next feed with a different vocabulary either
 * distorts this enum or does not fit it. The EDI code table belongs in the EDI normalizer, whose
 * whole job is to know that dialect. This enum is the language the rest of the platform speaks.
 *
 * <p>Note that {@link #ARRIVED_AT_STOP} and {@link #DEPARTED_STOP} here are <em>reported</em> by a
 * source and are not the same thing as the {@link ShipmentArrived} and {@link ShipmentDeparted}
 * events, which this platform derives by watching geofences. A carrier claiming arrival and our
 * own observation of it are different assertions, and the difference is sometimes the SLA case.
 */
public enum StatusCode {

  /** Load assigned and released to the carrier; nothing has moved yet. */
  DISPATCHED,

  /** Freight physically loaded at origin. */
  PICKED_UP,

  /** Moving between stops. Most feeds send this as a heartbeat rather than a transition. */
  IN_TRANSIT,

  /** The source claims the vehicle reached a stop. */
  ARRIVED_AT_STOP,

  /** The source claims the vehicle left a stop. */
  DEPARTED_STOP,

  /** Freight handed over at the final stop. Terminal for a normal shipment. */
  DELIVERED,

  /** Someone tried to deliver and could not — closed dock, no one to sign, refused load. */
  DELIVERY_ATTEMPT_FAILED,

  /** The carrier is telling us it will be late, before we would have worked it out ourselves. */
  DELAY_REPORTED,

  /** A reefer probe reading. Carries a {@link TemperatureReading} and no position. */
  TEMPERATURE_READING,

  /** Device is alive and has nothing to say. Useful precisely because silence is ambiguous. */
  DEVICE_HEARTBEAT
}
