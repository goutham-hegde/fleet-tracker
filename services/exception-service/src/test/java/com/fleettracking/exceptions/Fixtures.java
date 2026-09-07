package com.fleettracking.exceptions;

import com.fleettracking.events.GeoPoint;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.RawPayload;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.events.StatusCode;
import com.fleettracking.events.StatusEvent;
import com.fleettracking.events.TemperatureReading;
import com.fleettracking.exceptions.manifest.SlaTerms;
import com.fleettracking.reference.Itinerary;
import com.fleettracking.reference.ScheduledStop;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Events, plans and manifests for the rule tests, built rather than captured.
 *
 * <p>The gateway's tests read committed fixtures from {@code docs/samples} because what they check
 * is agreement with a real carrier's wire format. Nothing here ever sees a wire format — these rules
 * consume canonical envelopes the gateway has already validated — so what the tests need is precise
 * control over instants, speeds and coordinates.
 *
 * <p>The one exception is the reserved paths, which <em>are</em> read from the committed schemas.
 * See {@code ReservedPathsTest}: a convention the rules depend on has to be checked against the real
 * contracts, not against a copy typed into a test.
 */
public final class Fixtures {

  /** Well before the committed fixtures, so a test's instants never collide with real capture. */
  public static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

  public static final String SHIPMENT = "SHP-HYD-0002";

  /** Two stops on the Hyderabad-Bengaluru lane, far enough apart to make a real corridor. */
  public static final ScheduledStop HYDERABAD =
      new ScheduledStop("hyd-genome", 0, "Genome Valley", "Hyderabad", "TG", 17.61, 78.58, 400, "PICKUP");

  public static final ScheduledStop KURNOOL =
      new ScheduledStop("knl-clinic", 1, "Kurnool clinic", "Kurnool", "AP", 15.8281, 78.0373, 120, "DELIVERY");

  public static final ScheduledStop BENGALURU =
      new ScheduledStop("blr-hosp", 2, "Bengaluru hospital", "Bengaluru", "KA", 12.9716, 77.5946, 120, "DELIVERY");

  private Fixtures() {}

  /** The three-stop cold-chain plan. */
  public static Itinerary plan() {
    return new Itinerary(SHIPMENT, "hyd-blr-cold", List.of(HYDERABAD, KURNOOL, BENGALURU));
  }

  /** A position at an offset from {@link #T0}, at a stated point and speed. */
  public static PositionEvent position(
      Duration afterT0, double latitude, double longitude, double speedKph) {
    return position(afterT0, latitude, longitude, speedKph, 6.0);
  }

  /** The same, with a stated reported accuracy — the input the accuracy gate reads. */
  public static PositionEvent position(
      Duration afterT0, double latitude, double longitude, double speedKph, double accuracy) {
    Instant occurredAt = T0.plus(afterT0);
    return new PositionEvent(
        "evt-pos-" + occurredAt.toEpochMilli(),
        SHIPMENT,
        "VEH-0002",
        "TLM-0002",
        occurredAt,
        occurredAt.plusSeconds(2),
        new GeoPoint(latitude, longitude),
        speedKph,
        180.0,
        123456.0,
        accuracy,
        RawPayload.of(SourceSystem.TELEMATICS, "{}"));
  }

  /** A reefer reading at an offset from {@link #T0}. */
  public static StatusEvent reading(Duration afterT0, double celsius, Double setpoint) {
    Instant occurredAt = T0.plus(afterT0);
    return new StatusEvent(
        "evt-tmp-" + occurredAt.toEpochMilli(),
        SHIPMENT,
        "VEH-0002",
        "RFR-0002",
        occurredAt,
        occurredAt.plusSeconds(2),
        StatusCode.TEMPERATURE_READING,
        null,
        null,
        new TemperatureReading(celsius, setpoint),
        null,
        null,
        RawPayload.of(SourceSystem.REEFER_SENSOR, "{}"));
  }

  /** A status event that is not a temperature reading, which the cold-chain rule must ignore. */
  public static StatusEvent heartbeat(Duration afterT0) {
    Instant occurredAt = T0.plus(afterT0);
    return new StatusEvent(
        "evt-hb-" + occurredAt.toEpochMilli(),
        SHIPMENT,
        "VEH-0002",
        "TLM-0002",
        occurredAt,
        occurredAt.plusSeconds(2),
        StatusCode.DEVICE_HEARTBEAT,
        null,
        null,
        null,
        null,
        null,
        RawPayload.of(SourceSystem.TELEMATICS, "{}"));
  }

  /** Terms read from a body shaped exactly as MediVault's committed schema requires. */
  public static SlaTerms coldChainTerms(double minC, double maxC, Integer toleranceMinutes) {
    Map<String, Object> temperature =
        toleranceMinutes == null
            ? Map.of("minC", minC, "maxC", maxC)
            : Map.of("minC", minC, "maxC", maxC, "excursionToleranceMinutes", toleranceMinutes);
    return SlaTerms.from(
        SHIPMENT, "MEDIVAULT", "PHARMA_COLD_CHAIN", Map.of("temperature", temperature));
  }

  /** Terms carrying a booked delivery window, shaped as VistaMart's committed schema requires. */
  public static SlaTerms windowTerms(Instant opensAt, Instant closesAt) {
    return SlaTerms.from(
        SHIPMENT,
        "VISTAMART",
        "RETAIL_REPLENISHMENT",
        Map.of(
            "deliveryWindow",
            Map.of("opensAt", opensAt.toString(), "closesAt", closesAt.toString())));
  }

  /** A manifest with no SLA terms at all — a parcel, or a load nobody committed anything about. */
  public static SlaTerms bareTerms() {
    return SlaTerms.from(SHIPMENT, "QUICKSHIP", "PARCEL", Map.of("weightKg", 2.4));
  }
}
