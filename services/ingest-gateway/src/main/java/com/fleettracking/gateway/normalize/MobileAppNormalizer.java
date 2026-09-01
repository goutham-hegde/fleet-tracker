package com.fleettracking.gateway.normalize;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fleettracking.events.EventJson;
import com.fleettracking.events.GeoPoint;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.RawPayload;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.events.StatusCode;
import com.fleettracking.events.StatusEvent;
import com.fleettracking.gateway.identity.Identity;
import com.fleettracking.gateway.identity.IdentityResolver;
import java.time.Instant;
import java.util.Optional;
import tools.jackson.core.JacksonException;

/**
 * Reads the driver's phone app: terse keys, epoch milliseconds, metres per second.
 *
 * <p>Telematics was awkwardly shaped and honest. This feed is neither. It disappears into dead
 * zones, comes back and dumps its backlog out of order, and resends anything whose acknowledgement
 * it never saw — so the same message arrives twice, byte for byte, as a matter of routine rather
 * than as a fault.
 *
 * <h2>What that costs this class, and what it does not</h2>
 *
 * <p>Almost nothing, and that is the interesting part. There is no dedup table here, no ordering
 * buffer, no memory of what came before. The normalizer's whole answer to duplicates is to derive
 * the event id from what the phone said — the shipment, the instant, and the kind of event — so a
 * resent message produces a byte-identical id that a consumer can discard on sight. Holding state
 * here instead would put the memory in the one place that gets restarted, replicated and scaled
 * horizontally, and three gateway instances behind a load balancer would each dedupe against their
 * own private idea of what they had already seen.
 *
 * <p>Out-of-order arrival gets the same treatment: {@code occurredAt} is what the phone stamped,
 * {@code receivedAt} is when the message actually turned up, and a burst from a reconnecting phone
 * carries a wide gap between the two. Nothing here tries to reorder them. Kafka preserves the order
 * of arrival within a partition and a shipment's messages all land on one partition, so the
 * consumer in M3 sees the burst exactly as it happened and can decide for itself what to do with a
 * fix that is twenty minutes old. Sorting here would mean buffering, and buffering would mean
 * choosing how long to wait for a phone that may be off for an hour.
 *
 * <h2>Three differences that each cost a decision</h2>
 *
 * <ul>
 *   <li><b>Time is a number.</b> {@code ts} is epoch milliseconds, not the ISO-8601 string
 *       telematics sends. Same instant, different spelling.
 *   <li><b>Speed is metres per second</b>, because that is what a phone's location API reports. Not
 *       km/h and not the miles per hour telematics uses — a normalizer that got telematics right is
 *       still wrong here, and wrong by a factor of 3.6 rather than by something obvious.
 *   <li><b>It names a shipment and no vehicle</b>, the exact inverse of telematics, because a driver
 *       signs in against a load rather than against a tractor. Reference data is consulted in the
 *       opposite direction to fill in the vehicle.
 * </ul>
 *
 * <p>It also reports {@code acc}, a genuine accuracy radius in metres — no conversion needed, in
 * contrast to the HDOP telematics sends. Phone fixes are coarse and the numbers say so, running to
 * 30 m and beyond where a truck-mounted unit claims 5. M3 has to refuse to trigger a geofence on a
 * fix wider than the fence.
 */
public class MobileAppNormalizer implements Normalizer {

  private static final double MPS_TO_KPH = 3.6;

  private final IdentityResolver identities;

  public MobileAppNormalizer(IdentityResolver identities) {
    this.identities = identities;
  }

  @Override
  public SourceSystem source() {
    return SourceSystem.MOBILE_APP;
  }

  @Override
  public NormalizationResult normalize(InboundMessage message) {
    Payload payload;
    try {
      payload = EventJson.mapper().readValue(message.body(), Payload.class);
    } catch (JacksonException e) {
      return NormalizationResult.rejected(
          RejectionReason.MALFORMED_PAYLOAD, firstLine(e.getOriginalMessage()));
    }
    if (payload == null) {
      return NormalizationResult.rejected(RejectionReason.MALFORMED_PAYLOAD, "empty body");
    }

    String shipmentId = payload.shipmentId();
    if (shipmentId == null || shipmentId.isBlank()) {
      return NormalizationResult.rejected(RejectionReason.MISSING_FIELD, "sid");
    }
    if (payload.timestampMillis() == null) {
      return NormalizationResult.rejected(RejectionReason.MISSING_FIELD, "ts");
    }
    if (payload.latitude() == null || payload.longitude() == null) {
      return NormalizationResult.rejected(RejectionReason.MISSING_FIELD, "lat/lng");
    }

    // Resolved before it is used, because reference data is asked about a moment and this feed's
    // moment is buried in an epoch-millis field. It matters more here than anywhere: the app
    // buffers through a connectivity gap and dumps the backlog afterwards, so a message read now
    // routinely describes something that happened well before now.
    Instant occurredAt = Instant.ofEpochMilli(payload.timestampMillis());

    // The app names the shipment, so reference data is asked the opposite question to the one
    // telematics asks it. The vehicle is not decoration: the canonical envelopes require it, because
    // a downstream consumer joining position to a trailer's reefer probe has only the vehicle to
    // join on.
    Optional<Identity> identity = identities.byShipment(shipmentId, occurredAt);
    if (identity.isEmpty()) {
      return NormalizationResult.rejected(
          RejectionReason.UNRESOLVED_IDENTITY, "no vehicle assigned to shipment " + shipmentId);
    }

    Kind kind;
    try {
      kind = Kind.of(payload.event());
    } catch (IllegalArgumentException e) {
      return NormalizationResult.rejected(RejectionReason.INVALID_VALUE, e.getMessage());
    }

    // The event kind is part of the identity, not just the payload. A driver tapping "arrived" in
    // the same second the app sends its routine ping produces two genuinely different events with
    // one timestamp, and without this they would collapse into one id and one of them would vanish
    // into a consumer's dedup table.
    //
    // The obvious alternative -- the app's own seq counter -- is worse despite being unique. It
    // counts messages the installation has sent, so it restarts at 1 when the driver reinstalls,
    // and the same fix resent afterwards would carry a different seq and produce a different id.
    // That is the failure this whole scheme exists to prevent.
    String eventId = EventIds.of(SourceSystem.MOBILE_APP, shipmentId, occurredAt, kind.wireValue());

    RawPayload raw = new RawPayload(SourceSystem.MOBILE_APP, message.contentType(), message.body());
    GeoPoint position = new GeoPoint(payload.latitude(), payload.longitude());

    if (kind.status() == null) {
      return NormalizationResult.of(
          new PositionEvent(
              eventId,
              shipmentId,
              identity.get().vehicleId(),
              // No device id at all: the phone identifies the driver's session, not a fitted box,
              // and inventing one from the shipment would put a fictional device on the map.
              null,
              occurredAt,
              message.receivedAt(),
              position,
              speedKph(payload.speedMps()),
              heading(payload.headingDegrees()),
              // A phone has no odometer. Left null rather than zero -- zero is a claim that the
              // truck has never moved, and a consumer averaging odometer readings would believe it.
              null,
              payload.accuracyMeters(),
              raw));
    }

    return NormalizationResult.of(
        new StatusEvent(
            eventId,
            shipmentId,
            identity.get().vehicleId(),
            null,
            occurredAt,
            message.receivedAt(),
            kind.status(),
            // A status from this feed carries coordinates, which the EDI feed's never do. Keeping
            // the position on it means an arrival claimed by a driver can be checked against where
            // the phone actually was, rather than taken on trust.
            position,
            null,
            null,
            // Our own stop identifier, because the app is this platform's software and knows the
            // route it was given. The carrier's EDI feed emphatically does not.
            payload.stopId(),
            null,
            raw));
  }

  /**
   * What the driver's app was reporting.
   *
   * <p>A ping is a position and nothing more. The other three are things a person did, and they
   * become status events on a different topic — which is the point of the split: a consumer
   * watching for deliveries should not have to read every GPS fix in the fleet to find them.
   */
  private enum Kind {
    PING("ping", null),
    ARRIVE("arrive", StatusCode.ARRIVED_AT_STOP),
    DEPART("depart", StatusCode.DEPARTED_STOP),
    DELIVERED("delivered", StatusCode.DELIVERED);

    private final String wireValue;
    private final StatusCode status;

    Kind(String wireValue, StatusCode status) {
      this.wireValue = wireValue;
      this.status = status;
    }

    String wireValue() {
      return wireValue;
    }

    /** Null for a plain position report. */
    StatusCode status() {
      return status;
    }

    /**
     * An absent {@code evt} is a position report: the message carries a fix and nothing else, so
     * that is the only thing it can be saying. An <em>unknown</em> {@code evt} is rejected instead,
     * because the producer meant something specific by it and filing it as an ordinary position
     * would be inventing a meaning. A new app version reporting {@code "exception"} should show up
     * in the dead-letter topic as a thing to go and implement, not disappear into the position
     * stream looking normal.
     */
    static Kind of(String wireValue) {
      if (wireValue == null || wireValue.isBlank()) {
        return PING;
      }
      for (Kind kind : values()) {
        if (kind.wireValue.equalsIgnoreCase(wireValue.trim())) {
          return kind;
        }
      }
      throw new IllegalArgumentException("unknown evt: " + wireValue);
    }
  }

  private static Double speedKph(Double metresPerSecond) {
    return metresPerSecond == null ? null : metresPerSecond * MPS_TO_KPH;
  }

  /**
   * Wraps into {@code [0, 360)}, as the telematics normalizer does and for the same reason: the
   * envelope excludes 360 so that two consumers cannot disagree about where due north sits in the
   * range. The app sends a whole number, so 360 arrives exactly rather than as a rounding artefact.
   */
  private static Double heading(Integer degrees) {
    if (degrees == null) {
      return null;
    }
    int wrapped = degrees % 360;
    return (double) (wrapped < 0 ? wrapped + 360 : wrapped);
  }

  private static String firstLine(String message) {
    if (message == null) {
      return "unparseable payload";
    }
    int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }

  // ---------------------------------------------------------------------------------------------
  // The wire shape. Abbreviated because it was designed to be cheap over a mobile connection, and
  // declared here rather than shared with the simulator for the same reason telematics declares its
  // own: the contract is the captured fixtures, not a class both sides import and therefore always
  // agree with.
  //
  // Every field is boxed, including the timestamp. A primitive long would read a missing ts as 0
  // and stamp the event 1 January 1970, which is a real instant that sorts before everything and
  // would quietly poison any consumer ordering by time.
  // ---------------------------------------------------------------------------------------------

  record Payload(
      @JsonProperty("sid") String shipmentId,
      @JsonProperty("ts") Long timestampMillis,
      @JsonProperty("lat") Double latitude,
      @JsonProperty("lng") Double longitude,
      @JsonProperty("acc") Double accuracyMeters,
      @JsonProperty("spd") Double speedMps,
      @JsonProperty("hdg") Integer headingDegrees,
      @JsonProperty("bat") Integer batteryPercent,
      @JsonProperty("evt") String event,
      @JsonProperty("stop") String stopId,
      @JsonProperty("seq") Long sequence,
      @JsonProperty("app") String appVersion) {}
}
