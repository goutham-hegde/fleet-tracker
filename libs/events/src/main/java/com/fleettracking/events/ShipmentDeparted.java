package com.fleettracking.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;

/**
 * The platform concluded that a vehicle left a stop for good.
 *
 * <p>The mirror of {@link ShipmentArrived}, and subject to the same noise problem in reverse: a
 * fix that strays outside the geofence and comes straight back is not a departure. The dwell
 * threshold is applied outside the boundary before this is raised.
 *
 * @param dwell how long the vehicle was at the stop, {@code occurredAt} minus the matching
 *     arrival. Computed once, here, rather than left for every consumer to reconstruct by joining
 *     two events — detention time is a billable quantity and it should have exactly one definition.
 *     Serializes as an ISO-8601 duration, {@code PT47M}, not as a number of unspecified units.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShipmentDeparted(
    @NotBlank String eventId,
    @NotBlank String shipmentId,
    @NotNull Instant occurredAt,
    @NotBlank String causedBy,
    @NotBlank String stopId,
    @NotNull @Valid GeoPoint position,
    @NotNull Duration dwell)
    implements DerivedEvent {}
