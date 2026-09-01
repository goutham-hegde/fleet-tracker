package com.fleettracking.gateway.normalize;

import com.fleettracking.events.EventJson;
import com.fleettracking.events.GeoPoint;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.RawPayload;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.gateway.identity.Identity;
import com.fleettracking.gateway.identity.IdentityResolver;
import java.time.Instant;
import java.util.Optional;
import tools.jackson.core.JacksonException;

/**
 * Reads the in-cab telematics feed: nested, imperial JSON, keyed by vehicle.
 *
 * <p>This is the best-behaved of the four feeds and therefore the easiest to get quietly wrong.
 * Nothing about it fails loudly. Every number it sends is plausible in the wrong unit — 55 is a
 * believable speed in both miles per hour and kilometres per hour, and an odometer that is 38% too
 * small looks like a newer truck rather than like a bug. Four separate conversions happen here and
 * none of them would announce itself if it were missing.
 *
 * <h2>What has to change on the way through</h2>
 *
 * <ul>
 *   <li><b>Shape.</b> The payload nests position, odometer and engine data in their own objects,
 *       because that is how a device vendor models a device. The canonical envelope is flat and
 *       models a shipment.
 *   <li><b>Units.</b> Miles per hour to km/h, miles to kilometres. The engine block's Fahrenheit
 *       readings are dropped rather than converted — coolant temperature is a maintenance concern
 *       and this platform tracks freight. It survives in the raw payload if that ever changes.
 *   <li><b>Accuracy.</b> The unit reports HDOP, a unitless figure describing how favourably the
 *       satellites were arranged, not a distance. The envelope wants metres. Multiplying by the
 *       receiver's baseline error is the standard approximation and it is genuinely approximate —
 *       but publishing {@code 0.9} into a field labelled metres would tell geofencing the fix was
 *       accurate to under a metre, and it would act on that.
 *   <li><b>Identity.</b> The feed names a vehicle and no shipment, because the box is bolted to a
 *       tractor and knows nothing about loads. Without reference data there is no partition key and
 *       no event.
 * </ul>
 */
public class TelematicsNormalizer implements Normalizer {

  /**
   * Metres of horizontal error a receiver achieves with HDOP of 1 — the user equivalent range
   * error. Around 5 m for consumer and fleet-grade GPS without differential correction. Multiplying
   * HDOP by it turns "how good was the satellite geometry" into "roughly how far out is this
   * likely to be", which is the question a geofence needs answered.
   */
  static final double GPS_BASE_ERROR_METERS = 5.0;

  private static final double KM_PER_MILE = 1.609344;

  private final IdentityResolver identities;

  public TelematicsNormalizer(IdentityResolver identities) {
    this.identities = identities;
  }

  @Override
  public SourceSystem source() {
    return SourceSystem.TELEMATICS;
  }

  @Override
  public NormalizationResult normalize(InboundMessage message) {
    Payload payload;
    try {
      payload = EventJson.mapper().readValue(message.body(), Payload.class);
    } catch (JacksonException e) {
      // Everything unparseable lands here: truncated bodies, an unbalanced quote, a proxy's HTML
      // error page, and NaN -- which Jackson rejects by default, and which the fault fixtures
      // contain because a GPS unit with no fix really does emit it.
      return NormalizationResult.rejected(
          RejectionReason.MALFORMED_PAYLOAD, firstLine(e.getOriginalMessage()));
    }
    if (payload == null) {
      return NormalizationResult.rejected(RejectionReason.MALFORMED_PAYLOAD, "empty body");
    }

    Gps gps = payload.gps();
    if (gps == null) {
      return NormalizationResult.rejected(RejectionReason.MISSING_FIELD, "gps");
    }
    if (gps.lat() == null || gps.lon() == null) {
      return NormalizationResult.rejected(RejectionReason.MISSING_FIELD, "gps.lat/gps.lon");
    }
    // fixTime is when the receiver took the fix; sentAt is when the box got round to transmitting
    // it. The envelope orders by the former, and the latter stays in the raw payload where a
    // question about device backlog can still reach it.
    Instant occurredAt = gps.fixTime() != null ? gps.fixTime() : payload.sentAt();
    if (occurredAt == null) {
      return NormalizationResult.rejected(RejectionReason.MISSING_FIELD, "gps.fixTime/sentAt");
    }

    String vehicleId = payload.vehicle() == null ? null : payload.vehicle().id();
    if (vehicleId == null || vehicleId.isBlank()) {
      return NormalizationResult.rejected(RejectionReason.MISSING_FIELD, "vehicle.id");
    }
    Optional<Identity> identity = identities.byVehicle(vehicleId, occurredAt);
    if (identity.isEmpty()) {
      return NormalizationResult.rejected(
          RejectionReason.UNRESOLVED_IDENTITY, "no load assigned to vehicle " + vehicleId);
    }

    Double odometerKm;
    try {
      odometerKm = odometerKm(payload.odometer());
    } catch (IllegalArgumentException e) {
      return NormalizationResult.rejected(RejectionReason.INVALID_VALUE, e.getMessage());
    }

    PositionEvent event =
        new PositionEvent(
            EventIds.of(SourceSystem.TELEMATICS, payload.deviceId(), occurredAt),
            identity.get().shipmentId(),
            identity.get().vehicleId(),
            payload.deviceId(),
            occurredAt,
            message.receivedAt(),
            new GeoPoint(gps.lat(), gps.lon()),
            mphToKph(gps.speedMph()),
            heading(gps.headingDeg()),
            odometerKm,
            accuracyMeters(gps.hdop()),
            new RawPayload(SourceSystem.TELEMATICS, message.contentType(), message.body()));

    return NormalizationResult.of(event);
  }

  private static Double mphToKph(Double mph) {
    return mph == null ? null : mph * KM_PER_MILE;
  }

  /**
   * The odometer states its own unit, so honour it rather than assuming. A vendor shipping a
   * European build of the same firmware sends kilometres, and converting those as if they were
   * miles would shrink every distance by 38% without erroring anywhere.
   */
  private static Double odometerKm(Odometer odometer) {
    if (odometer == null || odometer.value() == null) {
      return null;
    }
    String unit = odometer.unit() == null ? "" : odometer.unit().trim().toLowerCase(java.util.Locale.ROOT);
    return switch (unit) {
      case "mi", "mile", "miles" -> odometer.value() * KM_PER_MILE;
      case "km", "kilometre", "kilometres", "kilometer", "kilometers" -> odometer.value();
      default -> throw new IllegalArgumentException("unknown odometer unit: " + odometer.unit());
    };
  }

  /**
   * Wraps heading into {@code [0, 360)}. The envelope excludes 360 because 360 and 0 are the same
   * bearing and allowing both would let two consumers disagree about whether a truck heading due
   * north is at the start or the end of the range. A device rounding 359.97 to one decimal place
   * emits exactly the value the constraint forbids, so this is a real case rather than a defensive
   * one.
   */
  private static Double heading(Double degrees) {
    if (degrees == null) {
      return null;
    }
    double wrapped = degrees % 360.0;
    return wrapped < 0 ? wrapped + 360.0 : wrapped;
  }

  private static Double accuracyMeters(Double hdop) {
    return hdop == null ? null : hdop * GPS_BASE_ERROR_METERS;
  }

  /** Jackson's parse messages carry the source location on following lines; one line is enough. */
  private static String firstLine(String message) {
    if (message == null) {
      return "unparseable payload";
    }
    int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }

  // ---------------------------------------------------------------------------------------------
  // The wire shape.
  //
  // Declared here rather than shared with the simulator that produces it: the gateway's contract is
  // with a telematics vendor, not with this repository's test tooling, and a shared class would
  // mean a normalizer that can only parse payloads written by code it already agrees with. The
  // fixtures in docs/samples are the contract; these records are this service's reading of it.
  //
  // Every field is boxed. A primitive double would silently read a missing latitude as 0.0, which
  // is a real coordinate in the Atlantic and would place a truck there rather than reject it.
  // ---------------------------------------------------------------------------------------------

  record Payload(
      String deviceId,
      Vehicle vehicle,
      Gps gps,
      Odometer odometer,
      Instant sentAt,
      String schemaVersion) {}

  record Vehicle(String id, String unitNumber, String make) {}

  record Gps(
      Double lat,
      Double lon,
      Double speedMph,
      Double headingDeg,
      Integer satellites,
      Double hdop,
      Instant fixTime) {}

  record Odometer(Double value, String unit) {}
}
