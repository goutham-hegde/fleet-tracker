package com.fleettracking.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;

/**
 * The condition behind an {@link ExceptionRaised} no longer holds.
 *
 * <p>Clearing is easy to leave out and expensive to leave out. A system that only raises produces
 * a dashboard where every shipment is eventually red, at which point nobody reads it. "Exceptions
 * clear when the condition resolves — not just raise" is an explicit M4 exit criterion for that
 * reason.
 *
 * @param exceptionId the same id the {@link ExceptionRaised} carried. This is the join.
 * @param raisedAt when the incident opened, repeated here so a consumer that missed the raise —
 *     or joined the topic after it — can still render a complete incident.
 * @param exceptionType which rule fired. Named {@code exceptionType} rather than {@code type} for
 *     the reason set out on {@link ExceptionRaised}: {@link Event} already uses {@code type} as its
 *     polymorphic discriminator, and a component of the same name puts the key on the wire twice.
 * @param openFor {@code occurredAt} minus {@code raisedAt}, computed once so that every consumer
 *     agrees on how long the shipment was in breach.
 * @param resolution how it ended: the condition genuinely recovered, or the shipment was delivered
 *     and the question stopped mattering. Free text rather than an enum until the M4 rules show
 *     what the real categories are — an enum guessed now would be wrong and would need migrating.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExceptionCleared(
    @NotBlank String eventId,
    @NotBlank String shipmentId,
    @NotNull Instant occurredAt,
    @NotBlank String causedBy,
    @NotBlank String exceptionId,
    @NotNull ExceptionType exceptionType,
    @NotNull Instant raisedAt,
    @NotNull Duration openFor,
    String resolution)
    implements DerivedEvent {}
