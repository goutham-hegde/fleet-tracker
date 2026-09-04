package com.fleettracking.gateway.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.SourceSystem;
import com.fleettracking.events.StatusCode;
import com.fleettracking.events.StatusEvent;
import com.fleettracking.gateway.Fixtures;
import java.time.Instant;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

class ReeferNormalizerTest {

  private final ReeferNormalizer normalizer = new ReeferNormalizer(Fixtures.defaultFleet());

  private static final Instant ARRIVED = Instant.parse("2026-08-31T09:43:00Z");

  private NormalizationResult normalize(String body) {
    return normalizer.normalize(
        new InboundMessage(SourceSystem.REEFER_SENSOR, "application/json", body, ARRIVED));
  }

  private StatusEvent normalizeOne(String body) {
    NormalizationResult result = normalize(body);
    assertThat(result)
        .as("expected a normalized result, got %s", result)
        .isInstanceOf(NormalizationResult.Normalized.class);
    List<com.fleettracking.events.SourceEvent> events =
        ((NormalizationResult.Normalized) result).events();
    assertThat(events).hasSize(1);
    return (StatusEvent) events.getFirst();
  }

  private static String reading() {
    return "{\"probe\":\"DEV-0002\",\"model\":\"ThermoKing-CX7\","
        + "\"readingUtc\":\"2026-08-31T09:42:39.744319200Z\",\"tempC\":4.18,\"setpointC\":4.0,"
        + "\"returnAirC\":4.93,\"supplyAirC\":3.02,\"door\":\"OPEN\",\"batteryV\":12.64}";
  }

  @Test
  void normalizesEveryCapturedReading() {
    List<String> lines = Fixtures.lines("reefer-sensor.jsonl");
    assertThat(lines).hasSizeGreaterThan(100);

    for (String line : lines) {
      assertThat(normalize(line))
          .as("fixture line should normalize: %s", line)
          .isInstanceOf(NormalizationResult.Normalized.class);
    }
  }

  @Test
  void findsTheShipmentAndVehicleFromNothingButADeviceId() {
    // The whole reason identity resolution exists. The payload names DEV-0002 and stops; every
    // canonical event needs a shipment id because that is the Kafka key, so without reference data
    // this reading has nowhere at all to go.
    StatusEvent event = normalizeOne(reading());

    assertThat(event.deviceId()).isEqualTo("DEV-0002");
    assertThat(event.shipmentId()).isEqualTo("SHP-HYD-0002");
    assertThat(event.vehicleId()).isEqualTo("VEH-0002");
  }

  @Test
  void becomesAStatusEventWithNoPositionAtAll() {
    StatusEvent event = normalizeOne(reading());

    assertThat(event.status()).isEqualTo(StatusCode.TEMPERATURE_READING);
    // There is no GPS in a probe bolted inside an insulated metal box, and it would not get a fix
    // if there were. A position event full of nulls with one real field would invite a consumer to
    // read the nulls as data.
    assertThat(event.position()).isNull();
    assertThat(event.location()).isNull();
  }

  @Test
  void carriesBothTemperaturesSoConsumersDeriveTheDeviationTheSameWay() {
    StatusEvent event = normalizeOne(reading());

    assertThat(event.temperature().celsius()).isEqualTo(4.18);
    assertThat(event.temperature().setpointCelsius()).isEqualTo(4.0);
    // Computed from the two numbers the device sent rather than stored as a third that this service
    // calculated once and could never correct.
    assertThat(event.temperature().deviation()).isCloseTo(0.18, Offset.offset(0.0001));
  }

  @Test
  void passesTheUnitsOwnAlarmThroughWithoutRejudgingIt() {
    String warm = reading().replace("\"tempC\":4.18", "\"tempC\":8.4") + "";
    String alarmed = warm.replace("\"batteryV\":12.64}", "\"batteryV\":12.64,\"alarm\":\"TEMP_DEVIATION\"}");

    // Whether a deviation is an exception worth acting on is M4's judgement, made against the
    // customer's contract -- not the trailer unit's factory threshold. So the alarm travels as the
    // device's own words.
    assertThat(normalizeOne(alarmed).reasonCode()).isEqualTo("TEMP_DEVIATION");
    assertThat(normalizeOne(reading()).reasonCode()).isNull();
  }

  @Test
  void takesTheInstantFromTheProbeNotFromArrival() {
    StatusEvent event = normalizeOne(reading());

    assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-31T09:42:39.744319200Z"));
    assertThat(event.receivedAt()).isEqualTo(ARRIVED);
  }

  @Test
  void givesTheSameEventIdToADuplicateDelivery() {
    StatusEvent first = normalizeOne(reading());
    StatusEvent second =
        (StatusEvent)
            ((NormalizationResult.Normalized)
                    normalizer.normalize(
                        new InboundMessage(
                            SourceSystem.REEFER_SENSOR,
                            "application/json",
                            reading(),
                            Instant.parse("2026-08-31T09:55:00Z"))))
                .events()
                .getFirst();

    assertThat(second.eventId()).isEqualTo(first.eventId());
  }

  @Test
  void givesTwoProbesReportingTheSameInstantDifferentIds() {
    // Two boxes reporting the same second are two genuine events, which is why the reporting device
    // is part of the derived id rather than only the shipment.
    String other = reading().replace("DEV-0002", "DEV-0006");

    assertThat(normalizeOne(other).eventId()).isNotEqualTo(normalizeOne(reading()).eventId());
  }

  @Test
  void deadLettersAProbeNoLoadIsAssignedTo() {
    // A trailer swapped onto a different load before the TMS was updated. This is the rejection
    // most likely to be transient and the one most worth replaying later.
    String unknown = reading().replace("DEV-0002", "DEV-9999");

    NormalizationResult result = normalize(unknown);

    assertThat(result).isInstanceOf(NormalizationResult.Rejected.class);
    NormalizationResult.Rejected rejected = (NormalizationResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo(RejectionReason.UNRESOLVED_IDENTITY);
    assertThat(rejected.detail()).contains("DEV-9999");
  }

  @Test
  void neverAttributesAReadingToAShipmentItCannotProve() {
    // Worth stating as its own test because the cost is asymmetric. A pharmaceutical load recorded
    // two degrees warm can void the freight and trigger a contractual penalty, so pinning that
    // reading on the wrong shipment is materially worse than losing it.
    String unknown = reading().replace("DEV-0002", "DEV-9999");

    assertThat(normalize(unknown)).isNotInstanceOf(NormalizationResult.Normalized.class);
  }

  @Test
  void rejectsAReadingWithNoTemperatureInIt() {
    String body = reading().replace("\"tempC\":4.18,", "");

    assertThat(normalize(body))
        .isEqualTo(new NormalizationResult.Rejected(RejectionReason.MISSING_FIELD, "tempC"));
  }

  @Test
  void rejectsAReadingWithNoDeviceId() {
    String body = reading().replace("\"probe\":\"DEV-0002\",", "");

    assertThat(normalize(body))
        .isEqualTo(new NormalizationResult.Rejected(RejectionReason.MISSING_FIELD, "probe"));
  }

  @Test
  void rejectsRatherThanThrowsOnEveryCorruptedFixture() {
    List<String> lines = Fixtures.lines("faults/reefer-sensor.jsonl");
    assertThat(lines).isNotEmpty();

    List<NormalizationResult> results = lines.stream().map(this::normalize).toList();
    List<NormalizationResult.Rejected> rejected =
        results.stream()
            .filter(NormalizationResult.Rejected.class::isInstance)
            .map(NormalizationResult.Rejected.class::cast)
            .toList();

    assertThat(rejected).as("corrupted messages in the chaos capture").isNotEmpty();
    assertThat(results).hasSizeGreaterThan(rejected.size());
    assertThat(rejected)
        .allSatisfy(r -> assertThat(r.reason()).isEqualTo(RejectionReason.MALFORMED_PAYLOAD));
  }

  @Test
  void rejectsAnUpstreamErrorPageRatherThanTreatingItAsAFeed() {
    assertThat(normalize("<html><body>502 Bad Gateway</body></html>"))
        .isInstanceOf(NormalizationResult.Rejected.class);
  }
}
