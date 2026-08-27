package com.fleettracking.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

/**
 * A revised estimate of when a shipment will reach a stop.
 *
 * <p>The highest-volume derived event, and the one most able to make the system look broken. An
 * ETA recomputed on every GPS fix flickers by minutes with each one and produces a dashboard that
 * nobody trusts. The rule in M3 is that this is emitted only when the estimate moves by more than
 * a threshold, which is why the previous value travels on the event: the size of the change is
 * part of what the event is saying, and a consumer should not have to remember the last one to
 * know whether this one matters.
 *
 * @param previousEstimate the estimate this replaces; null for the first estimate of a leg
 * @param remainingKm distance still to drive along the planned route, not straight-line
 * @param confidence 0 to 1. Falls with stale positions and poor accuracy, and is what lets the
 *     dashboard show an ETA as firm or provisional rather than implying false precision.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EtaUpdated(
    @NotBlank String eventId,
    @NotBlank String shipmentId,
    @NotNull Instant occurredAt,
    @NotBlank String causedBy,
    @NotBlank String stopId,
    @NotNull Instant estimatedArrival,
    Instant previousEstimate,
    @PositiveOrZero Double remainingKm,
    @DecimalMin("0.0") @DecimalMax("1.0") Double confidence)
    implements DerivedEvent {}
