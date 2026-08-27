package com.fleettracking.events;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/**
 * A refrigerated trailer's measured and target temperature, in Celsius.
 *
 * <p>Both numbers are needed, not just the measurement. A pharma cold-chain load at 2&deg;C and a
 * frozen load at -18&deg;C are both perfectly healthy; what makes either an SLA breach is drifting
 * away from its own setpoint. An excursion rule that hard-codes a threshold gets the frozen load
 * wrong. Carrying the setpoint on the reading means the rule is a comparison rather than a table
 * of magic numbers.
 *
 * @param celsius what the probe measured
 * @param setpointCelsius what the unit was told to hold, when the sensor reports it
 */
public record TemperatureReading(
    @DecimalMin("-60.0") @DecimalMax("60.0") Double celsius,
    @DecimalMin("-60.0") @DecimalMax("60.0") Double setpointCelsius) {

  /** How far the trailer has drifted from its target, or null if the setpoint is unknown. */
  @JsonIgnore
  public Double deviation() {
    if (celsius == null || setpointCelsius == null) {
      return null;
    }
    return celsius - setpointCelsius;
  }
}
