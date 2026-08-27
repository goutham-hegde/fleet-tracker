package com.fleettracking.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * The platform concluded that a vehicle reached a stop and stayed there.
 *
 * <p>Not the same as a carrier reporting {@link StatusCode#ARRIVED_AT_STOP}. This is raised by
 * watching positions cross a geofence and remain inside it past a dwell threshold — the dwell is
 * what separates an arrival from a truck sitting at a red light outside the gate. Exactly one of
 * these must be raised per stop per shipment, no matter how many times a noisy GPS fix wobbles
 * across the boundary; the "exactly one" is the M3 exit criterion.
 *
 * @param occurredAt when the vehicle actually entered the geofence — not when the dwell threshold
 *     expired and we became sure. Those differ by the dwell period, and using the later one would
 *     make every arrival look minutes late against the schedule.
 * @param position the fix that carried it inside the boundary
 * @param scheduledArrival what the plan said, when the shipment has a schedule. Carried on the
 *     event so a consumer can judge lateness without going back to the database for the itinerary.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShipmentArrived(
    @NotBlank String eventId,
    @NotBlank String shipmentId,
    @NotNull Instant occurredAt,
    @NotBlank String causedBy,
    @NotBlank String stopId,
    @NotNull @Valid GeoPoint position,
    Instant scheduledArrival)
    implements DerivedEvent {}
