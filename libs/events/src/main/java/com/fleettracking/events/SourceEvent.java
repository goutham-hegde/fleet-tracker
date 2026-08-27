package com.fleettracking.events;

import java.time.Instant;

/**
 * An event normalized from one of the four external feeds.
 *
 * <p>By the time an event implements this interface it has already been through identity
 * resolution: {@code shipmentId} and {@code vehicleId} are populated even for feeds that never
 * mention them. A reefer sensor reports only its own device id, so the gateway walks
 * device &rarr; vehicle &rarr; load before constructing the envelope. An event whose identity
 * cannot be resolved is not representable here — it goes to the dead-letter topic instead. That is
 * why those fields are required rather than nullable: an event with no shipment id has no Kafka
 * key, and an event with no key cannot honour per-shipment ordering.
 */
public sealed interface SourceEvent extends Event permits PositionEvent, StatusEvent {

  /** The truck or trailer. Resolved during ingest; never null. */
  String vehicleId();

  /**
   * The reporting hardware or app installation, when there is one. Null for EDI 214, which is
   * filed by a carrier's back-office system rather than by a device.
   */
  String deviceId();

  /**
   * When the gateway received the event. Compare with {@link #occurredAt()} to measure feed lag —
   * a large gap is normal for the mobile app after a connectivity outage and normal for EDI 214,
   * which is batch by nature.
   */
  Instant receivedAt();

  /**
   * The original payload, untouched, exactly as the source sent it.
   *
   * <p>This is the escape hatch that lets the rest of the model stay closed. Anything a source
   * sends that the canonical envelope does not model is not lost — it is here, and a normalizer
   * can be fixed and the stream replayed without going back to the carrier for the data.
   */
  RawPayload raw();
}
