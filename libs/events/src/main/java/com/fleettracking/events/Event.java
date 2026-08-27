package com.fleettracking.events;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;

/**
 * Everything that travels on a Kafka topic in this platform.
 *
 * <p>The hierarchy is {@code sealed}: the complete list of event types is fixed here and cannot be
 * extended from outside this module. That is deliberate — a {@code switch} over {@code Event} in a
 * consumer is checked by the compiler for exhaustiveness, so adding an event type without handling
 * it everywhere is a build failure rather than a runtime surprise.
 *
 * <p>Two branches:
 * <ul>
 *   <li>{@link SourceEvent} — normalized from an external feed. Carries the untouched original in
 *       {@link SourceEvent#raw()}.
 *   <li>{@link DerivedEvent} — concluded by this platform from source events. Carries no raw
 *       payload, but records which event caused it.
 * </ul>
 *
 * <p>Every event names a {@code shipmentId}, because that is the Kafka partition key. Per-shipment
 * ordering is what the platform guarantees, and it gets that guarantee for free from the key —
 * global ordering is never needed and would cost a single partition.
 *
 * <h2>Units</h2>
 * The canonical model is metric and unit-suffixed at the field name: {@code speedKph},
 * {@code odometerKm}, {@code accuracyMeters}, {@code celsius}. Sources that report otherwise
 * (telematics reports mph) are converted during normalization, never here.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = PositionEvent.class, name = "position"),
    @JsonSubTypes.Type(value = StatusEvent.class, name = "status"),
    @JsonSubTypes.Type(value = ShipmentArrived.class, name = "shipment.arrived"),
    @JsonSubTypes.Type(value = ShipmentDeparted.class, name = "shipment.departed"),
    @JsonSubTypes.Type(value = EtaUpdated.class, name = "eta.updated"),
    @JsonSubTypes.Type(value = ExceptionRaised.class, name = "exception.raised"),
    @JsonSubTypes.Type(value = ExceptionCleared.class, name = "exception.cleared")
})
public sealed interface Event permits SourceEvent, DerivedEvent {

  /** Unique id for this event. Consumers use it to detect replays and de-duplicate. */
  String eventId();

  /** The shipment this event concerns. Also the Kafka message key. Never null. */
  String shipmentId();

  /**
   * When the event happened in the real world — the reading on the truck, not the moment we heard
   * about it. The mobile app can deliver an hour-old backlog in one burst, so this is frequently
   * far behind {@link SourceEvent#receivedAt()} and is the only timestamp safe to order by.
   */
  Instant occurredAt();
}
