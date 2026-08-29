package com.fleettracking.simulator.route;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fleettracking.events.GeoPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The geodesic maths, checked against values that are true by definition rather than by lookup.
 *
 * <p>One degree of latitude is exactly {@code R * π / 180} metres on a sphere, so most of these
 * assertions can be derived on paper. That matters: a test that only compares against numbers a
 * previous run produced would happily lock in a sign error forever.
 */
class GeoTest {

  /** One degree of arc at the earth's mean radius, in metres: 6 371 008.8 * π / 180. */
  private static final double ONE_DEGREE_METERS = Geo.EARTH_RADIUS_METERS * Math.PI / 180.0;

  private static final GeoPoint ORIGIN = new GeoPoint(0, 0);

  @Nested
  @DisplayName("distance")
  class Distance {

    @Test
    @DisplayName("one degree of latitude is one degree of arc")
    void oneDegreeOfLatitude() {
      double meters = Geo.distanceMeters(ORIGIN, new GeoPoint(1, 0));
      assertThat(meters).isCloseTo(ONE_DEGREE_METERS, within(0.001));
    }

    @Test
    @DisplayName("one degree of longitude at the equator is the same arc")
    void oneDegreeOfLongitudeAtEquator() {
      double meters = Geo.distanceMeters(ORIGIN, new GeoPoint(0, 1));
      assertThat(meters).isCloseTo(ONE_DEGREE_METERS, within(0.001));
    }

    @Test
    @DisplayName("a degree of longitude shrinks with the cosine of latitude")
    void longitudeConvergesTowardThePoles() {
      // Meridians meet at the poles, so the same 1 degree of longitude spans less ground the
      // further north you are - by a factor of cos(latitude), which at 60 degrees is a half.
      //
      // The tolerance is a metre rather than a millimetre because cos(latitude) describes the
      // distance along the *parallel*, and a parallel is not a great circle. The shortest path
      // between two points at the same latitude bulges toward the nearer pole and is therefore
      // slightly shorter than following the line of latitude - about 0.5 m over this degree.
      // Measuring it the other way would be a real error, so the assertion is directional too.
      double alongTheParallel = ONE_DEGREE_METERS * 0.5;
      double atSixty = Geo.distanceMeters(new GeoPoint(60, 0), new GeoPoint(60, 1));
      assertThat(atSixty).isCloseTo(alongTheParallel, within(1.0)).isLessThan(alongTheParallel);
    }

    @Test
    @DisplayName("is symmetric")
    void isSymmetric() {
      GeoPoint chicago = new GeoPoint(41.8781, -87.6298);
      GeoPoint dallas = new GeoPoint(32.7767, -96.7970);
      assertThat(Geo.distanceMeters(chicago, dallas))
          .isCloseTo(Geo.distanceMeters(dallas, chicago), within(1e-6));
    }

    @Test
    @DisplayName("a point is zero metres from itself")
    void zeroToSelf() {
      assertThat(Geo.distanceMeters(new GeoPoint(41.8781, -87.6298), new GeoPoint(41.8781, -87.6298)))
          .isZero();
    }

    @Test
    @DisplayName("resolves distances of a single metre")
    void resolvesShortDistances() {
      // This is the case the spherical law of cosines gets catastrophically wrong, returning 0.
      // A geofence with a 50 m radius is built entirely out of distances at this scale.
      GeoPoint a = new GeoPoint(41.8781, -87.6298);
      GeoPoint b = Geo.destination(a, 45, 1.0);
      assertThat(Geo.distanceMeters(a, b)).isCloseTo(1.0, within(0.001));
    }

    @Test
    @DisplayName("gives a plausible answer for a real transcontinental lane")
    void realWorldSanityCheck() {
      // Los Angeles to New York, great circle. Widely quoted as roughly 3 940 - 3 980 km; the
      // tolerance here is deliberately loose because the point is to catch an order-of-magnitude
      // or unit error, not to pin a reference implementation.
      GeoPoint la = new GeoPoint(34.0522, -118.2437);
      GeoPoint ny = new GeoPoint(40.7128, -74.0060);
      assertThat(Geo.distanceMeters(la, ny) / 1000).isCloseTo(3936, within(30.0));
    }
  }

  @Nested
  @DisplayName("bearing")
  class Bearing {

    @ParameterizedTest(name = "toward ({0}, {1}) is {2} degrees")
    @CsvSource({
      " 1,  0,   0", // due north
      " 0,  1,  90", // due east
      "-1,  0, 180", // due south
      " 0, -1, 270", // due west
    })
    void cardinalDirectionsFromTheOrigin(double lat, double lon, double expected) {
      assertThat(Geo.initialBearingDegrees(ORIGIN, new GeoPoint(lat, lon)))
          .isCloseTo(expected, within(1e-9));
    }

    @Test
    @DisplayName("changes along a long great circle, which is why it is only the *initial* bearing")
    void greatCirclesDoNotHoldAConstantHeading() {
      GeoPoint la = new GeoPoint(34.0522, -118.2437);
      GeoPoint ny = new GeoPoint(40.7128, -74.0060);

      double atStart = Geo.initialBearingDegrees(la, ny);
      // Stand two thirds of the way along the path and re-aim at the same destination.
      double total = Geo.distanceMeters(la, ny);
      GeoPoint twoThirds = Geo.destination(la, atStart, total * 2 / 3);
      double laterOn = Geo.initialBearingDegrees(twoThirds, ny);

      // Heading north of east at the start, and it swings further clockwise as the truck goes.
      assertThat(atStart).isBetween(60.0, 80.0);
      assertThat(laterOn).isGreaterThan(atStart + 5);
    }

    @Test
    @DisplayName("is never 360")
    void neverReturnsThreeSixty() {
      // Approaching due north from the west would give -0.0000001 degrees before normalization,
      // and a naive wrap rounds that to exactly 360.0 - which PositionEvent rejects.
      GeoPoint justWestOfNorth = new GeoPoint(1, -1e-12);
      assertThat(Geo.initialBearingDegrees(ORIGIN, justWestOfNorth)).isLessThan(360.0);
    }
  }

  @Nested
  @DisplayName("destination")
  class Destination {

    @Test
    @DisplayName("travelling one degree of arc due north lands one degree north")
    void dueNorth() {
      GeoPoint result = Geo.destination(ORIGIN, 0, ONE_DEGREE_METERS);
      assertThat(result.latitude()).isCloseTo(1.0, within(1e-9));
      assertThat(result.longitude()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    @DisplayName("travelling zero metres does not move")
    void zeroDistance() {
      GeoPoint start = new GeoPoint(41.8781, -87.6298);
      GeoPoint result = Geo.destination(start, 137, 0);
      // Nanometres. Not exactly zero because the round trip through trigonometry and back never
      // is - which is the reason nothing in this codebase compares two positions with ==.
      assertThat(Geo.distanceMeters(start, result)).isCloseTo(0.0, within(1e-6));
    }

    @Test
    @DisplayName("round-trips: aim at a point, drive the distance, arrive at it")
    void roundTripsWithBearingAndDistance() {
      // The property the whole tick loop depends on. If this holds, a truck stepping along a leg
      // converges on its stop rather than drifting past it or spiralling.
      GeoPoint chicago = new GeoPoint(41.8781, -87.6298);
      GeoPoint dallas = new GeoPoint(32.7767, -96.7970);

      double bearing = Geo.initialBearingDegrees(chicago, dallas);
      double distance = Geo.distanceMeters(chicago, dallas);
      GeoPoint arrived = Geo.destination(chicago, bearing, distance);

      assertThat(Geo.distanceMeters(arrived, dallas)).isLessThan(0.01);
    }

    @Test
    @DisplayName("many small steps land in the same place as one large one")
    void stepwiseTravelMatchesDirectTravel() {
      // This is exactly what the simulator does: it never jumps to a stop, it takes hundreds of
      // one-second steps. Accumulated floating-point drift over those steps must stay negligible.
      GeoPoint chicago = new GeoPoint(41.8781, -87.6298);
      GeoPoint dallas = new GeoPoint(32.7767, -96.7970);
      double total = Geo.distanceMeters(chicago, dallas);

      GeoPoint direct = Geo.destination(chicago, Geo.initialBearingDegrees(chicago, dallas), total);

      GeoPoint stepped = chicago;
      int steps = 500;
      for (int i = 0; i < steps; i++) {
        // Re-aim every step, the way a truck does - this is what keeps it on the great circle.
        double bearing = Geo.initialBearingDegrees(stepped, dallas);
        stepped = Geo.destination(stepped, bearing, total / steps);
      }

      assertThat(Geo.distanceMeters(stepped, direct)).isLessThan(1.0);
    }

    @Test
    @DisplayName("crossing the antimeridian wraps the longitude instead of running off the scale")
    void crossesTheAntimeridian() {
      // Due east from just west of the date line. Longitude must come back near -179, not 181,
      // which GeoPoint's @DecimalMax("180.0") would reject outright.
      GeoPoint nearDateLine = new GeoPoint(0, 179.99);
      GeoPoint result = Geo.destination(nearDateLine, 90, ONE_DEGREE_METERS);
      assertThat(result.longitude()).isCloseTo(-179.01, within(0.01));
    }
  }

  @Nested
  @DisplayName("normalization")
  class Normalization {

    @ParameterizedTest(name = "{0} degrees normalizes to {1}")
    @CsvSource({
      "   0,   0",
      "  90,  90",
      " 360,   0",
      " 450,  90",
      " -90, 270",
      "-450, 270",
      " 720,   0",
    })
    void bearingsWrapInto0To360(double input, double expected) {
      assertThat(Geo.normalizeBearing(input)).isCloseTo(expected, within(1e-9));
    }

    @ParameterizedTest(name = "{0} degrees normalizes to {1}")
    @CsvSource({
      "   0,    0",
      " 179,  179",
      " 181, -179",
      "-181,  179",
      " 360,    0",
    })
    void longitudesWrapIntoMinus180To180(double input, double expected) {
      assertThat(Geo.normalizeLongitude(input)).isCloseTo(expected, within(1e-9));
    }
  }
}
