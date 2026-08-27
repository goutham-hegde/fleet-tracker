package com.fleettracking.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

/**
 * The module's reason to exist: every event type survives a trip through JSON unchanged.
 *
 * <p>This is the M0 exit criterion. It matters because JSON is not a lossless format for Java
 * objects by default — an {@code Instant} can lose its nanoseconds, a {@code Duration} can arrive
 * as a number whose units nobody recorded, and a polymorphic type can come back as a
 * {@code LinkedHashMap}. Each of those is silent. Kafka is a durable log, so an event written
 * wrong today is still wrong when it is replayed in six months.
 *
 * <p>Round-tripping through the {@link Event} interface rather than the concrete class is the
 * point of the exercise. Any consumer reads bytes off a topic knowing only that they are some
 * event; if the discriminator does not survive, the consumer cannot reconstruct the type.
 */
class EventRoundTripTest {

  private final ObjectMapper mapper = EventJson.mapper();

  static Stream<Event> allEventTypes() {
    return EventFixtures.all().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allEventTypes")
  @DisplayName("serializes and deserializes back to an equal object")
  void roundTripsLosslessly(Event original) {
    String json = mapper.writeValueAsString(original);

    // Read back as the interface, exactly as a consumer would off a topic.
    Event restored = mapper.readValue(json, Event.class);

    assertThat(restored).isEqualTo(original).isExactlyInstanceOf(original.getClass());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allEventTypes")
  @DisplayName("survives a second round trip byte-identically")
  void isStableAcrossRepeatedRoundTrips(Event original) {
    String first = mapper.writeValueAsString(original);
    String second = mapper.writeValueAsString(mapper.readValue(first, Event.class));

    // Not implied by object equality: a field could deserialize into an equal-but-differently-
    // serialized value. If the bytes are stable, the format is a fixed point.
    assertThat(second).isEqualTo(first);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allEventTypes")
  @DisplayName("carries a type discriminator")
  void writesTypeDiscriminator(Event original) {
    assertThat(mapper.readTree(mapper.writeValueAsString(original)).get("type"))
        .as("every event must name its own type in the payload")
        .isNotNull();
  }

  @Test
  @DisplayName("keeps nanosecond precision on timestamps")
  void preservesInstantPrecision() {
    PositionEvent restored =
        (PositionEvent)
            mapper.readValue(mapper.writeValueAsString(EventFixtures.positionEvent()), Event.class);

    assertThat(restored.occurredAt()).isEqualTo(EventFixtures.OCCURRED);
    assertThat(restored.occurredAt().getNano()).isEqualTo(482913041);
  }

  @Test
  @DisplayName("keeps the raw EDI payload byte-for-byte, terminators and all")
  void preservesRawEdiPayload() {
    StatusEvent original = EventFixtures.statusEvent();

    StatusEvent restored =
        (StatusEvent) mapper.readValue(mapper.writeValueAsString(original), Event.class);

    assertThat(restored.raw().body())
        .isEqualTo(original.raw().body())
        .contains("~")
        .contains("AT7*X1*NS***20260827*1403*ET");
  }

  @Test
  @DisplayName("writes timestamps as ISO-8601 strings, not epoch numbers")
  void writesTimestampsAsStrings() {
    String json = mapper.writeValueAsString(EventFixtures.positionEvent());

    assertThat(mapper.readTree(json).get("occurredAt").isString()).isTrue();
    assertThat(json).contains("2026-08-27T14:03:11.482913041Z");
  }

  @Test
  @DisplayName("writes durations as ISO-8601, not as a bare number of unstated units")
  void writesDurationsAsIso8601() {
    String json = mapper.writeValueAsString(EventFixtures.shipmentDeparted());

    // 47m13s. As a number this would be 2833 - and nothing in the payload would say of what.
    assertThat(mapper.readTree(json).get("dwell").stringValue()).isEqualTo("PT47M13S");
  }

  @Test
  @DisplayName("omits null fields rather than writing them out")
  void omitsNulls() {
    // The EDI status event has no position and no deviceId.
    String json = mapper.writeValueAsString(EventFixtures.statusEvent());

    assertThat(mapper.readTree(json).has("position")).isFalse();
    assertThat(mapper.readTree(json).has("deviceId")).isFalse();
    assertThat(json).doesNotContain("null");
  }

  @Test
  @DisplayName("treats an absent optional field and an explicit null identically")
  void absentAndNullAreEquivalent() {
    ShipmentArrived withoutSchedule =
        new ShipmentArrived(
            "evt-1", "SHP-1", Instant.parse("2026-08-27T00:00:00Z"), "evt-0", "STOP-1",
            new GeoPoint(0, 0), null);

    String omitted = mapper.writeValueAsString(withoutSchedule);
    String explicit = omitted.replace("}", ",\"scheduledArrival\":null}");

    assertThat(mapper.readValue(explicit, Event.class)).isEqualTo(withoutSchedule);
  }

  @Test
  @DisplayName("ignores fields a newer producer added")
  void toleratesUnknownFields() {
    // A v1 consumer meeting a payload from a producer that has learned something new. It must not
    // throw, or no service in the platform can be deployed independently of the others.
    String json =
        mapper
            .writeValueAsString(EventFixtures.positionEvent())
            .replaceFirst("\\{", "{\"tirePressureKpa\":812,\"driverId\":\"DRV-9\",");

    assertThat(mapper.readValue(json, Event.class)).isEqualTo(EventFixtures.positionEvent());
  }
}
