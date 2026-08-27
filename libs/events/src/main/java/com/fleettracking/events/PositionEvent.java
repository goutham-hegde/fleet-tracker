package com.fleettracking.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;

/**
 * "This shipment was at this point at this time." One of the two canonical envelopes.
 *
 * <p>Two of the four feeds produce these — telematics constantly, the mobile app when it has
 * signal. EDI 214 never does, because it has no coordinates, and reefer sensors never do, because
 * they have no idea where they are.
 *
 * <p>Everything except identity, time and the point itself is optional, because feeds genuinely
 * differ in what they measure. A phone reports accuracy and no odometer; a telematics unit reports
 * odometer and rarely accuracy. Making those fields required would force normalizers to invent
 * numbers, and an invented zero is worse than an honest null: a consumer can skip a null, but it
 * will happily average a zero.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PositionEvent(
    @NotBlank String eventId,
    @NotBlank String shipmentId,
    @NotBlank String vehicleId,
    String deviceId,
    @NotNull Instant occurredAt,
    @NotNull Instant receivedAt,
    @NotNull @Valid GeoPoint position,

    /** Ground speed, km/h. Telematics reports mph; the normalizer converts, not the consumer. */
    @PositiveOrZero @DecimalMax("250.0") Double speedKph,

    /** Compass heading, degrees clockwise from true north. 360 is not a valid value; 0 is. */
    @DecimalMin("0.0") @DecimalMax(value = "360.0", inclusive = false) Double headingDegrees,

    /** Lifetime distance on the vehicle, km. Monotonic, and useful for detecting a swapped unit. */
    @PositiveOrZero Double odometerKm,

    /**
     * Reported horizontal accuracy in metres — the radius the true position is probably within.
     * Load-bearing for geofencing: a fix accurate to 500 m must not be allowed to trigger an
     * arrival at a stop 200 m away.
     */
    @PositiveOrZero Double accuracyMeters,
    @NotNull @Valid RawPayload raw)
    implements SourceEvent {}
