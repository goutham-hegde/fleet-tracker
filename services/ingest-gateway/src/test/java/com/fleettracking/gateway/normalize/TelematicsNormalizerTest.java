package com.fleettracking.gateway.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.SourceEvent;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.gateway.Fixtures;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelematicsNormalizerTest {

  private final TelematicsNormalizer normalizer = new TelematicsNormalizer(Fixtures.defaultFleet());

  private NormalizationResult normalize(String body) {
    return normalizer.normalize(
        new InboundMessage(
            SourceSystem.TELEMATICS, "application/json", body, Instant.parse("2026-08-31T10:00:00Z")));
  }

  private PositionEvent normalizeOne(String body) {
    NormalizationResult result = normalize(body);
    assertThat(result).isInstanceOf(NormalizationResult.Normalized.class);
    List<SourceEvent> events = ((NormalizationResult.Normalized) result).events();
    assertThat(events).hasSize(1);
    return (PositionEvent) events.getFirst();
  }

  // A payload written by hand rather than captured, because the committed capture was taken while
  // every truck was still on its pickup dock: real, but stationary, so it exercises the parse and
  // not one of the four unit conversions. Both kinds of test are needed.
  private static String moving() {
    return """
        {"deviceId":"TLM-0003","vehicle":{"id":"VEH-0003","unitNumber":"0003","make":"Mahindra"},
         "gps":{"lat":12.92014,"lon":77.65032,"speedMph":62.5,"headingDeg":134.0,
                "satellites":9,"hdop":1.2,"fixTime":"2026-08-31T14:05:00Z"},
         "odometer":{"value":100.0,"unit":"mi"},
         "engine":{"rpm":1500,"coolantTempF":195,"fuelLevelPct":61.2,"ignition":"ON"},
         "sentAt":"2026-08-31T14:05:02Z","schemaVersion":"2.3"}
        """;
  }

  @Test
  void normalizesEveryCapturedTelematicsMessage() {
    List<String> lines = Fixtures.lines("telematics.jsonl");
    assertThat(lines).hasSizeGreaterThan(100);

    for (String line : lines) {
      NormalizationResult result = normalize(line);
      assertThat(result)
          .as("fixture line should normalize: %s", line)
          .isInstanceOf(NormalizationResult.Normalized.class);
    }
  }

  @Test
  void mapsVehicleToItsShipmentAndKeepsTheReportingDevice() {
    PositionEvent event = normalizeOne(moving());

    // The feed named a vehicle and nothing else; the shipment came from reference data.
    assertThat(event.vehicleId()).isEqualTo("VEH-0003");
    assertThat(event.shipmentId()).isEqualTo("SHP-BLR-0003");
    // The device id is whatever reported, not something looked up: the telematics unit on this
    // truck is TLM-0003 while the reefer probe on the same trailer is DEV-0003.
    assertThat(event.deviceId()).isEqualTo("TLM-0003");
  }

  @Test
  void convertsImperialUnitsToMetric() {
    PositionEvent event = normalizeOne(moving());

    assertThat(event.speedKph()).isCloseTo(100.584, org.assertj.core.data.Offset.offset(0.001));
    assertThat(event.odometerKm()).isCloseTo(160.9344, org.assertj.core.data.Offset.offset(0.0001));
  }

  @Test
  void convertsHdopToMetresRatherThanCopyingIt() {
    PositionEvent event = normalizeOne(moving());

    // 1.2 HDOP is good satellite geometry, not a fix accurate to 1.2 metres. Copying the number
    // across would tell geofencing this position is twenty times better than it is.
    assertThat(event.accuracyMeters()).isEqualTo(6.0);
  }

  @Test
  void takesOccurredAtFromTheFixNotFromTheTransmission() {
    PositionEvent event = normalizeOne(moving());

    assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-31T14:05:00Z"));
    assertThat(event.receivedAt()).isEqualTo(Instant.parse("2026-08-31T10:00:00Z"));
  }

  @Test
  void keepsTheOriginalPayloadByteForByte() {
    String body = moving();
    PositionEvent event = normalizeOne(body);

    assertThat(event.raw().source()).isEqualTo(SourceSystem.TELEMATICS);
    assertThat(event.raw().contentType()).isEqualTo("application/json");
    assertThat(event.raw().body()).isEqualTo(body);
  }

  @Test
  void givesTheSameEventIdToADuplicateDelivery() {
    // Same payload, arriving twice, twenty minutes apart -- a retry after a lost acknowledgement.
    PositionEvent first = normalizeOne(moving());
    NormalizationResult again =
        normalizer.normalize(
            new InboundMessage(
                SourceSystem.TELEMATICS,
                "application/json",
                moving(),
                Instant.parse("2026-08-31T10:20:00Z")));
    PositionEvent second =
        (PositionEvent) ((NormalizationResult.Normalized) again).events().getFirst();

    assertThat(second.eventId()).isEqualTo(first.eventId());
    // The arrival time still differs, so nothing about lag has been falsified to achieve it.
    assertThat(second.receivedAt()).isNotEqualTo(first.receivedAt());
  }

  @Test
  void wrapsAHeadingOfThreeHundredAndSixtyRoundToZero() {
    // A device reporting 359.97 and rounding to one decimal place emits exactly 360.0, which the
    // canonical envelope forbids because it is the same bearing as 0.
    String body = moving().replace("\"headingDeg\":134.0", "\"headingDeg\":360.0");

    assertThat(normalizeOne(body).headingDegrees()).isEqualTo(0.0);
  }

  @Test
  void honoursTheOdometerUnitTheDeviceStated() {
    String metric = moving().replace("\"value\":100.0,\"unit\":\"mi\"", "\"value\":100.0,\"unit\":\"km\"");

    assertThat(normalizeOne(metric).odometerKm()).isEqualTo(100.0);
  }

  @Test
  void rejectsAnOdometerUnitItDoesNotUnderstand() {
    String body = moving().replace("\"unit\":\"mi\"", "\"unit\":\"furlongs\"");

    assertThat(normalize(body))
        .isEqualTo(new NormalizationResult.Rejected(
            RejectionReason.INVALID_VALUE, "unknown odometer unit: furlongs"));
  }

  @Test
  void rejectsAWellFormedPayloadWithNoPosition() {
    String body =
        """
        {"deviceId":"TLM-0003","vehicle":{"id":"VEH-0003"},
         "odometer":{"value":100.0,"unit":"mi"},"sentAt":"2026-08-31T14:05:02Z"}
        """;

    NormalizationResult result = normalize(body);

    assertThat(result).isInstanceOf(NormalizationResult.Rejected.class);
    assertThat(((NormalizationResult.Rejected) result).reason())
        .isEqualTo(RejectionReason.MISSING_FIELD);
  }

  @Test
  void rejectsAVehicleNoLoadIsAssignedTo() {
    String body = moving().replace("VEH-0003", "VEH-9999");

    NormalizationResult result = normalize(body);

    assertThat(result).isInstanceOf(NormalizationResult.Rejected.class);
    NormalizationResult.Rejected rejected = (NormalizationResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo(RejectionReason.UNRESOLVED_IDENTITY);
    assertThat(rejected.detail()).contains("VEH-9999");
  }

  @Test
  void rejectsRatherThanThrowsOnEveryCorruptedFixture() {
    List<String> lines = Fixtures.lines("faults/telematics.jsonl");
    assertThat(lines).isNotEmpty();

    List<NormalizationResult> results = lines.stream().map(this::normalize).toList();
    List<NormalizationResult.Rejected> rejected =
        results.stream()
            .filter(NormalizationResult.Rejected.class::isInstance)
            .map(NormalizationResult.Rejected.class::cast)
            .toList();

    // The chaos capture corrupts a fraction of messages, so the file holds both kinds. What
    // matters is that the bad ones become rejections rather than exceptions, and that the good
    // ones still get through -- a normalizer that rejected the whole file would pass a weaker
    // version of this test while being useless.
    assertThat(rejected).as("corrupted messages in the chaos capture").isNotEmpty();
    assertThat(results).hasSizeGreaterThan(rejected.size());
    assertThat(rejected)
        .allSatisfy(r -> assertThat(r.reason()).isEqualTo(RejectionReason.MALFORMED_PAYLOAD));
  }

  @Test
  void rejectsAnUpstreamErrorPageRatherThanTreatingItAsAFeed() {
    assertThat(normalize("<html><head><title>502 Bad Gateway</title></head></html>"))
        .isInstanceOf(NormalizationResult.Rejected.class);
  }

  @Test
  void rejectsAnEmptyBody() {
    assertThat(normalize("")).isInstanceOf(NormalizationResult.Rejected.class);
  }
}
