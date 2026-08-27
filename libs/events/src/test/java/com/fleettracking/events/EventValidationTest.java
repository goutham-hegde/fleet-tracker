package com.fleettracking.events;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.Set;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The constraints on the envelopes are the ingest gateway's contract with itself: an event that
 * fails these is rejected to the dead-letter topic rather than published. These tests prove the
 * constraints actually fire, since an annotation that is never exercised is a comment.
 *
 * <p>Validation is declared here but not performed here. {@code libs/events} depends only on
 * {@code jakarta.validation-api} — the interfaces — so it stays a plain library that imposes no
 * validation implementation on whoever uses it. Hibernate Validator appears only at test scope.
 */
class EventValidationTest {

  private static Validator validator;

  @BeforeAll
  static void setUp() {
    // Hibernate Validator's default message interpolator needs a Jakarta Expression Language
    // implementation, which is neither a transitive dependency nor managed by the Spring Boot BOM.
    // ParameterMessageInterpolator handles the {min}/{max} substitution these constraints use and
    // needs no EL engine, so the module gains no dependency for the sake of a test.
    validator =
        Validation.byDefaultProvider()
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory()
            .getValidator();
  }

  @Test
  @DisplayName("a well-formed event of every type is valid")
  void fixturesAreValid() {
    assertThat(EventFixtures.all())
        .allSatisfy(event -> assertThat(validator.validate(event)).isEmpty());
  }

  @Test
  @DisplayName("rejects an event with no shipment id")
  void requiresShipmentId() {
    // The shipment id is the Kafka message key. Without it the broker picks a partition at random
    // and per-shipment ordering - the platform's one ordering guarantee - is gone.
    PositionEvent noShipment = withShipmentId(EventFixtures.positionEvent(), "  ");

    assertThat(pathsOf(validator.validate(noShipment))).containsExactly("shipmentId");
  }

  @Test
  @DisplayName("rejects coordinates outside the earth")
  void requiresCoordinatesInRange() {
    PositionEvent original = EventFixtures.positionEvent();
    PositionEvent offWorld =
        new PositionEvent(
            original.eventId(),
            original.shipmentId(),
            original.vehicleId(),
            original.deviceId(),
            original.occurredAt(),
            original.receivedAt(),
            new GeoPoint(91.0, -181.0),
            original.speedKph(),
            original.headingDegrees(),
            original.odometerKm(),
            original.accuracyMeters(),
            original.raw());

    // Nested constraints are reached only because the component is annotated @Valid; without it
    // the GeoPoint would be treated as an opaque value and never inspected.
    assertThat(pathsOf(validator.validate(offWorld)))
        .containsExactlyInAnyOrder("position.latitude", "position.longitude");
  }

  @Test
  @DisplayName("treats 360 degrees as out of range but 0 as fine")
  void headingIsHalfOpen() {
    // Compass headings wrap: 360 and 0 are the same direction, so allowing both would let two
    // different numbers mean one bearing and quietly break any comparison over heading.
    assertThat(pathsOf(validator.validate(withHeading(360.0)))).containsExactly("headingDegrees");
    assertThat(validator.validate(withHeading(0.0))).isEmpty();
    assertThat(validator.validate(withHeading(359.9))).isEmpty();
  }

  @Test
  @DisplayName("rejects a derived event that does not say what caused it")
  void derivedEventsMustNameTheirCause() {
    ShipmentArrived unexplained =
        new ShipmentArrived(
            "evt-1",
            "SHP-1",
            Instant.parse("2026-08-27T00:00:00Z"),
            null,
            "STOP-1",
            new GeoPoint(0, 0),
            null);

    assertThat(pathsOf(validator.validate(unexplained))).containsExactly("causedBy");
  }

  @Test
  @DisplayName("rejects an event that has lost its raw payload")
  void sourceEventsMustKeepTheirRawPayload() {
    StatusEvent original = EventFixtures.statusEvent();
    StatusEvent stripped =
        new StatusEvent(
            original.eventId(),
            original.shipmentId(),
            original.vehicleId(),
            original.deviceId(),
            original.occurredAt(),
            original.receivedAt(),
            original.status(),
            original.position(),
            original.location(),
            original.temperature(),
            original.stopId(),
            original.reasonCode(),
            null);

    assertThat(pathsOf(validator.validate(stripped))).containsExactly("raw");
  }

  private static Set<String> pathsOf(Set<? extends ConstraintViolation<?>> violations) {
    return violations.stream().map(v -> v.getPropertyPath().toString()).collect(java.util.stream.Collectors.toSet());
  }

  private static PositionEvent withShipmentId(PositionEvent e, String shipmentId) {
    return new PositionEvent(
        e.eventId(), shipmentId, e.vehicleId(), e.deviceId(), e.occurredAt(), e.receivedAt(),
        e.position(), e.speedKph(), e.headingDegrees(), e.odometerKm(), e.accuracyMeters(), e.raw());
  }

  private static PositionEvent withHeading(Double heading) {
    PositionEvent e = EventFixtures.positionEvent();
    return new PositionEvent(
        e.eventId(), e.shipmentId(), e.vehicleId(), e.deviceId(), e.occurredAt(), e.receivedAt(),
        e.position(), e.speedKph(), heading, e.odometerKm(), e.accuracyMeters(), e.raw());
  }
}
