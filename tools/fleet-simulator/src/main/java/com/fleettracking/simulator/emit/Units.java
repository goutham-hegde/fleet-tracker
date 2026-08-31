package com.fleettracking.simulator.emit;

/**
 * Unit conversions and rounding for the wire formats.
 *
 * <p>The simulation is metric throughout — km/h, kilometres, Celsius — because the canonical event
 * model is. The conversions here exist to make the payloads <em>wrong</em> on purpose: a US
 * telematics unit reports miles per hour and Fahrenheit, and if the simulator quietly emitted
 * metric then M2's normalizers would be tested against data that never needed converting. A unit
 * conversion applied twice, or not at all, is one of the most common integration bugs there is, and
 * it is silent: 60 mph and 60 km/h are both entirely plausible speeds for a truck.
 *
 * <p>Rounding matters for the same reason. Real devices report limited precision — six decimal
 * places of latitude is roughly a tenth of a metre, and no consumer-grade GPS is that good. Emitting
 * a full {@code double} would hand downstream code more precision than the real feed has.
 */
final class Units {

  private static final double KM_PER_MILE = 1.609344;

  private Units() {}

  /** Kilometres per hour to miles per hour. */
  static double kphToMph(double kph) {
    return kph / KM_PER_MILE;
  }

  /** Kilometres to miles. */
  static double kmToMiles(double km) {
    return km / KM_PER_MILE;
  }

  /** Metres per second, which is what a phone's location API reports speed in. */
  static double kphToMps(double kph) {
    return kph / 3.6;
  }

  /** Celsius to Fahrenheit. */
  static double celsiusToFahrenheit(double celsius) {
    return celsius * 9.0 / 5.0 + 32.0;
  }

  /** Rounds to the given number of decimal places. */
  static double round(double value, int decimals) {
    double factor = Math.pow(10, decimals);
    return Math.round(value * factor) / factor;
  }
}
