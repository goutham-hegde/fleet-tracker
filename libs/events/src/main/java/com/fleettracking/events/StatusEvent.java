package com.fleettracking.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * "Something happened to this shipment." The second canonical envelope, and the one that has to
 * absorb everything that is not a coordinate.
 *
 * <p>It carries three mutually exclusive ways of saying where the event took place, and which one
 * is populated tells you which feed it came from:
 *
 * <ul>
 *   <li>{@link #position()} — a real fix. A phone reporting "delivered" knows where it is.
 *   <li>{@link #location()} — a city and state and nothing more. This is EDI 214, and it is not
 *       mappable until geocoded.
 *   <li>neither — a reefer temperature reading, which knows only its own temperature.
 * </ul>
 *
 * <h2>Why this is not a map of attributes</h2>
 *
 * <p>The obvious shape for "status plus whatever else the source said" is a
 * {@code Map<String, Object>}. It was rejected. A map is untyped at compile time, round-trips
 * lossily through JSON (a {@code Long} that fits in an {@code int} comes back an {@code Integer}),
 * and turns every consumer into a set of string literals and casts. The canonical envelope is
 * instead closed and typed: the fields below are the ones the platform reasons about, and anything
 * else a source sends is already preserved verbatim in {@link #raw()}. The escape hatch exists —
 * there is no need for a second, worse one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusEvent(
    @NotBlank String eventId,
    @NotBlank String shipmentId,
    @NotBlank String vehicleId,
    String deviceId,
    @NotNull Instant occurredAt,
    @NotNull Instant receivedAt,
    @NotNull StatusCode status,

    /** Where it happened, when the source actually knows. Null for EDI 214 and reefer sensors. */
    @Valid GeoPoint position,

    /** Where it happened in words. Populated by EDI 214; null once geocoding has filled in
     * {@link #position()}, or when the source gave coordinates in the first place. */
    @Valid LocationHint location,

    /** Present only on {@link StatusCode#TEMPERATURE_READING} events from a reefer probe. */
    @Valid TemperatureReading temperature,

    /** Which stop on the route this concerns, when the source names one. */
    String stopId,

    /**
     * The source's own explanation, kept as the source's own string — "CONSIGNEE CLOSED",
     * "WEATHER". Not an enum: every carrier has a different list, and mapping them into a shared
     * vocabulary is a normalizer's decision to make later, with the original still in
     * {@link #raw()} if it gets it wrong.
     */
    String reasonCode,
    @NotNull @Valid RawPayload raw)
    implements SourceEvent {}
