package com.fleettracking.gateway.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.SourceEvent;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.events.StatusCode;
import com.fleettracking.events.StatusEvent;
import com.fleettracking.gateway.Fixtures;
import java.time.Instant;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

class MobileAppNormalizerTest {

  private final MobileAppNormalizer normalizer = new MobileAppNormalizer(Fixtures.defaultFleet());

  private static final Instant ARRIVED = Instant.parse("2026-08-31T13:40:03Z");

  private NormalizationResult normalize(String body) {
    return normalize(body, ARRIVED);
  }

  private NormalizationResult normalize(String body, Instant receivedAt) {
    return normalizer.normalize(
        new InboundMessage(SourceSystem.MOBILE_APP, "application/json", body, receivedAt));
  }

  private SourceEvent normalizeOne(String body) {
    NormalizationResult result = normalize(body);
    assertThat(result).as("expected a normalized result, got %s", result)
        .isInstanceOf(NormalizationResult.Normalized.class);
    List<SourceEvent> events = ((NormalizationResult.Normalized) result).events();
    assertThat(events).hasSize(1);
    return events.getFirst();
  }

  // Hand-written rather than captured, for the same reason the telematics test writes one: the
  // committed capture was taken while every truck was still stationary on its pickup dock, so every
  // speed in it is 0.0 and the metres-per-second conversion is untested by it. 1788183600000 is
  // 2026-08-31T13:40:00Z; the message is handled three seconds later, which is what an ordinary
  // ping with signal looks like.
  private static String ping() {
    return "{\"sid\":\"SHP-BLR-0003\",\"ts\":1788183600000,\"lat\":13.02869,\"lng\":77.51999,"
        + "\"acc\":18.5,\"spd\":27.5,\"hdg\":135,\"bat\":84,\"evt\":\"ping\",\"seq\":42,"
        + "\"app\":\"3.4.1\"}";
  }

  @Test
  void normalizesEveryCapturedMobileMessage() {
    List<String> lines = Fixtures.lines("mobile-app.jsonl");
    assertThat(lines).hasSizeGreaterThan(100);

    for (String line : lines) {
      assertThat(normalize(line))
          .as("fixture line should normalize: %s", line)
          .isInstanceOf(NormalizationResult.Normalized.class);
    }
  }

  @Test
  void mapsShipmentToItsVehicleTheOppositeWayRoundToTelematics() {
    SourceEvent event = normalizeOne(ping());

    // The app named the shipment; the vehicle came from reference data. Telematics knows the
    // vehicle and looks up the shipment, so the two feeds consult the same table in opposite
    // directions and neither could publish without it.
    assertThat(event.shipmentId()).isEqualTo("SHP-BLR-0003");
    assertThat(event.vehicleId()).isEqualTo("VEH-0003");
    // A phone is not a fitted device. Naming one would put a piece of hardware on the map that
    // does not exist.
    assertThat(event.deviceId()).isNull();
  }

  @Test
  void convertsMetresPerSecondToKilometresPerHour() {
    PositionEvent event = (PositionEvent) normalizeOne(ping());

    // 27.5 m/s is 99 km/h. Read as mph -- the unit the other JSON feed uses -- it would be 44.3,
    // and a truck on an interstate would look like a truck in a depot.
    assertThat(event.speedKph()).isCloseTo(99.0, Offset.offset(0.0001));
  }

  @Test
  void takesTheAccuracyRadiusAsGivenBecauseItIsAlreadyInMetres() {
    PositionEvent event = (PositionEvent) normalizeOne(ping());

    // Unlike telematics, which reports HDOP and needs converting. A phone fix really is this
    // coarse: 18.5 m against the 5 m a truck-mounted unit claims.
    assertThat(event.accuracyMeters()).isEqualTo(18.5);
  }

  @Test
  void leavesTheOdometerNullBecauseAPhoneHasNone() {
    PositionEvent event = (PositionEvent) normalizeOne(ping());

    // Null, not zero. Zero is a claim that this truck has never moved.
    assertThat(event.odometerKm()).isNull();
  }

  @Test
  void readsEpochMillisecondsAsTheInstantThePhoneStamped() {
    PositionEvent event = (PositionEvent) normalizeOne(ping());

    assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-31T13:40:00Z"));
    assertThat(event.receivedAt()).isEqualTo(ARRIVED);
  }

  @Test
  void keepsABacklogBurstsRealLagRatherThanRewritingIt() {
    // A fix taken at 13:40 that reached the platform at 14:05, because the phone was in a dead
    // zone for twenty-five minutes. Nothing here tries to disguise that.
    Instant late = Instant.parse("2026-08-31T14:05:00Z");
    SourceEvent event =
        ((NormalizationResult.Normalized) normalize(ping(), late)).events().getFirst();

    assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-31T13:40:00Z"));
    assertThat(event.receivedAt()).isEqualTo(late);
    assertThat(event.occurredAt()).isBefore(event.receivedAt());
  }

  @Test
  void givesTheSameEventIdToAResentMessage() {
    // The defining property of this feed: it resends anything it did not see acknowledged, and the
    // resend is byte-identical. Only the arrival time differs.
    SourceEvent first = normalizeOne(ping());
    SourceEvent second =
        ((NormalizationResult.Normalized) normalize(ping(), Instant.parse("2026-08-31T13:52:00Z")))
            .events()
            .getFirst();

    assertThat(second.eventId()).isEqualTo(first.eventId());
    assertThat(second.receivedAt()).isNotEqualTo(first.receivedAt());
  }

  @Test
  void separatesAStatusFromAPingSentInTheSameSecond() {
    // A driver tapping "arrived" while the app sends its routine ping produces two real events with
    // one timestamp from one shipment. If the event kind were not part of the id they would
    // collapse into one and a consumer's dedup would silently discard the arrival.
    String tapped = ping().replace("\"evt\":\"ping\"", "\"evt\":\"arrive\",\"stop\":\"blr-peenya\"");

    assertThat(normalizeOne(tapped).eventId()).isNotEqualTo(normalizeOne(ping()).eventId());
  }

  @Test
  void turnsADriverTapIntoAStatusEventCarryingWhereThePhoneWas() {
    String tapped = ping().replace("\"evt\":\"ping\"", "\"evt\":\"depart\",\"stop\":\"blr-peenya\"");

    StatusEvent event = (StatusEvent) normalizeOne(tapped);

    assertThat(event.status()).isEqualTo(StatusCode.DEPARTED_STOP);
    // The stop id is this platform's own, because the app is this platform's software. The carrier
    // EDI feed reports the same kind of event and cannot name a stop at all.
    assertThat(event.stopId()).isEqualTo("blr-peenya");
    // Coordinates on a status event, which lets a claimed departure be checked against where the
    // phone actually was rather than taken on trust.
    assertThat(event.position().latitude()).isEqualTo(13.02869);
  }

  @Test
  void routesAPingToAPositionAndATapToAStatus() {
    assertThat(normalizeOne(ping())).isInstanceOf(PositionEvent.class);
    assertThat(normalizeOne(ping().replace("\"evt\":\"ping\"", "\"evt\":\"delivered\"")))
        .isInstanceOf(StatusEvent.class);
  }

  @Test
  void wrapsAHeadingOfThreeHundredAndSixtyRoundToZero() {
    String body = ping().replace("\"hdg\":135", "\"hdg\":360");

    assertThat(((PositionEvent) normalizeOne(body)).headingDegrees()).isEqualTo(0.0);
  }

  @Test
  void treatsAMessageWithNoEventTypeAsAPosition() {
    // It carries a fix and nothing else, so that is the only thing it can be saying.
    String body = ping().replace(",\"evt\":\"ping\"", "");

    assertThat(normalizeOne(body)).isInstanceOf(PositionEvent.class);
  }

  @Test
  void rejectsAnEventTypeItDoesNotRecognise() {
    // A newer app version reporting something this gateway has never heard of should surface as
    // work to do, not vanish into the position stream looking like an ordinary fix.
    String body = ping().replace("\"evt\":\"ping\"", "\"evt\":\"exception\"");

    assertThat(normalize(body))
        .isEqualTo(
            new NormalizationResult.Rejected(
                RejectionReason.INVALID_VALUE, "unknown evt: exception"));
  }

  @Test
  void rejectsAShipmentNoVehicleIsAssignedTo() {
    String body = ping().replace("SHP-BLR-0003", "SHP-XXX-9999");

    NormalizationResult result = normalize(body);

    assertThat(result).isInstanceOf(NormalizationResult.Rejected.class);
    NormalizationResult.Rejected rejected = (NormalizationResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo(RejectionReason.UNRESOLVED_IDENTITY);
    assertThat(rejected.detail()).contains("SHP-XXX-9999");
  }

  @Test
  void rejectsAMessageWithNoTimestampRatherThanStampingItNineteenSeventy() {
    // The wire field is boxed for exactly this: a primitive long would read the absence as 0 and
    // produce an event dated 1 January 1970, which sorts before everything and would poison any
    // consumer ordering by time.
    String body = ping().replace("\"ts\":1788183600000,", "");

    assertThat(normalize(body))
        .isEqualTo(new NormalizationResult.Rejected(RejectionReason.MISSING_FIELD, "ts"));
  }

  @Test
  void rejectsAMessageWithNoPosition() {
    String body = "{\"sid\":\"SHP-BLR-0003\",\"ts\":1788183600000,\"evt\":\"ping\",\"seq\":42}";

    assertThat(normalize(body))
        .isEqualTo(new NormalizationResult.Rejected(RejectionReason.MISSING_FIELD, "lat/lng"));
  }

  @Test
  void rejectsRatherThanThrowsOnEveryCorruptedFixture() {
    List<String> lines = Fixtures.lines("faults/mobile-app.jsonl");
    assertThat(lines).isNotEmpty();

    List<NormalizationResult> results = lines.stream().map(this::normalize).toList();
    List<NormalizationResult.Rejected> rejected =
        results.stream()
            .filter(NormalizationResult.Rejected.class::isInstance)
            .map(NormalizationResult.Rejected.class::cast)
            .toList();

    // Both kinds are in the file. The corrupted ones must become values rather than exceptions,
    // and the intact ones beside them must still get through -- rejecting the whole file would
    // pass a weaker version of this test while being useless.
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

  @Test
  void rejectsTheOtherFeedsFormatEvenThoughItIsValidJson() {
    // Telematics JSON posted to the mobile endpoint parses cleanly and has none of the fields that
    // matter, which is why every required field is checked rather than assumed present.
    String telematics =
        "{\"deviceId\":\"TLM-0003\",\"vehicle\":{\"id\":\"VEH-0003\"},"
            + "\"gps\":{\"lat\":35.1,\"lon\":-90.0,\"fixTime\":\"2026-08-31T14:05:00Z\"}}";

    assertThat(normalize(telematics))
        .isEqualTo(new NormalizationResult.Rejected(RejectionReason.MISSING_FIELD, "sid"));
  }
}
