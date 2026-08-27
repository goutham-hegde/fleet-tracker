package com.fleettracking.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * An SLA rule fired: something about this shipment is now wrong.
 *
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
    @NotNull ExceptionType type,
    @NotNull Severity severity,
    @NotBlank String detail,
    String stopId,
    Double observedValue,
    Double thresholdValue)
    implements DerivedEvent {}
