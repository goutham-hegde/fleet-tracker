package com.fleettracking.events;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One fully-populated instance of every event type.
 *
 * <p>Fully populated on purpose. A round-trip test over an event with half its optional fields
 * left null passes whether or not those fields serialize correctly, which is the least useful kind
 * of green test. Every nullable field is set here so that a mapping mistake on any of them fails
 * something.
 *
 * <p>The raw payloads are shaped like the real feeds rather than being {@code "{}"} — in
 * particular the EDI 214 sample is genuine X12 layout, because "the raw field survives verbatim"
 * is a claim about awkward text, not about well-behaved JSON.
 */
final class EventFixtures {

  /**
   * Deliberately sub-millisecond. Instants are the field most likely to lose precision silently,
   * and a timestamp ending in round zeros would hide truncation rather than catch it.
   */
  static final Instant OCCURRED = Instant.parse("2026-08-27T14:03:11.482913041Z");

  static final Instant RECEIVED = Instant.parse("2026-08-27T14:03:12.004000777Z");

  private EventFixtures() {}

  /** A telematics fix: nested JSON, imperial units in the raw, metric on the envelope. */
  static PositionEvent positionEvent() {
    String raw =
        """
        {"unit":{"id":"TLM-88213","fw":"4.2.1"},\
        "gps":{"lat":35.1495,"lon":-90.0490,"hdop":0.8},\
        "motion":{"speed_mph":58.3,"heading":271.5,"odometer_mi":184203.4},\
        "ts":"2026-08-27T14:03:11.482913041Z"}""";
    return new PositionEvent(
        "evt-01J9Z4K8M2P7QR3T5V6W8X9YA0",
        "SHP-2026-0088412",
        "VEH-TRK-4471",
        "TLM-88213",
        OCCURRED,
        RECEIVED,
        new GeoPoint(35.1495, -90.0490),
        93.8, // 58.3 mph converted
        271.5,
        296446.7, // 184203.4 mi converted
        8.0,
        RawPayload.of(SourceSystem.TELEMATICS, raw));
  }

  /**
   * An EDI 214 status: no coordinates at all, a city and state instead, and a raw body that is
   * segment-terminated text rather than JSON.
   */
  static StatusEvent statusEvent() {
    String raw =
        "ISA*00*          *00*          *02*CARRIER01     *ZZ*SHIPPER0001   *260827*1403*U*00401*000000001*0*P*>~\n"
            + "GS*QM*CARRIER01*SHIPPER0001*20260827*1403*1*X*004010~\n"
            + "ST*214*0001~\n"
            + "B10*4471*SHP-2026-0088412*CARRIER01~\n"
            + "AT7*X1*NS***20260827*1403*ET~\n"
            + "MS1*MEMPHIS*TN*US~\n"
            + "SE*6*0001~";
    return new StatusEvent(
        "evt-01J9Z4K8M2P7QR3T5V6W8X9YB1",
        "SHP-2026-0088412",
        "VEH-TRK-4471",
        null, // EDI is filed by a back-office system, not by a device
        OCCURRED,
        RECEIVED,
        StatusCode.ARRIVED_AT_STOP,
        null, // no coordinates, ever
        new LocationHint("MEMPHIS", "TN", "38103", "US"),
        new TemperatureReading(-18.4, -20.0),
        "STOP-3",
        "CONSIGNEE CLOSED",
        new RawPayload(SourceSystem.EDI_214, "application/edi-x12", raw));
  }

  static ShipmentArrived shipmentArrived() {
    return new ShipmentArrived(
        "evt-01J9Z4K8M2P7QR3T5V6W8X9YC2",
        "SHP-2026-0088412",
        OCCURRED,
        "evt-01J9Z4K8M2P7QR3T5V6W8X9YA0",
        "STOP-3",
        new GeoPoint(35.1495, -90.0490),
        Instant.parse("2026-08-27T13:30:00Z"));
  }

  static ShipmentDeparted shipmentDeparted() {
    return new ShipmentDeparted(
        "evt-01J9Z4K8M2P7QR3T5V6W8X9YD3",
        "SHP-2026-0088412",
        OCCURRED,
        "evt-01J9Z4K8M2P7QR3T5V6W8X9YA0",
        "STOP-3",
        new GeoPoint(35.1501, -90.0488),
        Duration.ofMinutes(47).plusSeconds(13));
  }

  static EtaUpdated etaUpdated() {
    return new EtaUpdated(
        "evt-01J9Z4K8M2P7QR3T5V6W8X9YE4",
        "SHP-2026-0088412",
        OCCURRED,
        "evt-01J9Z4K8M2P7QR3T5V6W8X9YA0",
        "STOP-4",
        Instant.parse("2026-08-27T18:45:00Z"),
        Instant.parse("2026-08-27T18:20:00Z"),
        412.6,
        0.82);
  }

  static ExceptionRaised exceptionRaised() {
    return new ExceptionRaised(
        "evt-01J9Z4K8M2P7QR3T5V6W8X9YF5",
        "SHP-2026-0088412",
        OCCURRED,
        "evt-01J9Z4K8M2P7QR3T5V6W8X9YB1",
        "exc-01J9Z4K8M2P7QR3T5V6W8X9YZZ",
        ExceptionType.TEMPERATURE_EXCURSION,
        Severity.CRITICAL,
        "1.6C above setpoint for 22 minutes",
        "STOP-3",
        -18.4,
        -20.0);
  }

  static ExceptionCleared exceptionCleared() {
    return new ExceptionCleared(
        "evt-01J9Z4K8M2P7QR3T5V6W8X9YG6",
        "SHP-2026-0088412",
        OCCURRED,
        "evt-01J9Z4K8M2P7QR3T5V6W8X9YB1",
        "exc-01J9Z4K8M2P7QR3T5V6W8X9YZZ",
        ExceptionType.TEMPERATURE_EXCURSION,
        Instant.parse("2026-08-27T13:41:11.482913041Z"),
        Duration.ofMinutes(22),
        "reefer recovered to setpoint");
  }

  /** Every fixture. Kept in sync with the sealed hierarchy by {@code EventCoverageTest}. */
  static List<Event> all() {
    return List.of(
        positionEvent(),
        statusEvent(),
        shipmentArrived(),
        shipmentDeparted(),
        etaUpdated(),
        exceptionRaised(),
        exceptionCleared());
  }
}
