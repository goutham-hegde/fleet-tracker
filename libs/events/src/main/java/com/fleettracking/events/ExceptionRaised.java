package com.fleettracking.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * An SLA rule fired: something about this shipment is now wrong.
 *
 * @param exceptionType which rule fired. Named {@code exceptionType} rather than {@code type},
 *     which is what it obviously wants to be called, because {@link Event} already uses
 *     {@code type} as its polymorphic discriminator — so a component of that name serializes the
 *     key twice, with the discriminator first and the rule second. That round-trips by accident:
 *     Jackson reads the type id from the first occurrence while streaming, so
 *     {@code readValue(json, ExceptionRaised.class)} works, and a consumer that parses to a tree
 *     first gets the <em>last</em> occurrence instead and fails with an invalid type id. Found in
 *     S14 by the first consumer this topic ever had.
 * @param exceptionId identity of the <em>incident</em>, not of this message. The matching
 *     {@link ExceptionCleared} repeats it, and that pairing is the whole reason exceptions are
 *     modelled as two events instead of one boolean flag. A rule that keeps firing while a
 *     condition persists must reuse the same {@code exceptionId} rather than mint a new one, or
 *     one late truck becomes forty alerts.
 * @param detail a human-readable sentence, already formatted, with the numbers in it — "3.2C above
 *     setpoint for 22 minutes". The dashboard should not have to know how to phrase each rule.
 * @param observedValue and {@code thresholdValue} — the measurement that broke the rule and the
 *     limit it broke, so the exception can be judged without re-running the rule. Null for
 *     {@link ExceptionType#SIGNAL_LOSS}, where the evidence is that nothing was measured at all.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExceptionRaised(
    @NotBlank String eventId,
    @NotBlank String shipmentId,
    @NotNull Instant occurredAt,
    @NotBlank String causedBy,
    @NotBlank String exceptionId,
    @NotNull ExceptionType exceptionType,
    @NotNull Severity severity,
    @NotBlank String detail,
    String stopId,
    Double observedValue,
    Double thresholdValue)
    implements DerivedEvent {}
