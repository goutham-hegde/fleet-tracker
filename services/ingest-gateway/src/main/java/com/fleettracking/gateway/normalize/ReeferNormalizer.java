package com.fleettracking.gateway.normalize;

import com.fleettracking.events.EventJson;
import com.fleettracking.events.RawPayload;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.events.StatusCode;
import com.fleettracking.events.StatusEvent;
import com.fleettracking.events.TemperatureReading;
import com.fleettracking.gateway.identity.Identity;
import com.fleettracking.gateway.identity.IdentityResolver;
import java.time.Instant;
import java.util.Optional;
import tools.jackson.core.JacksonException;

/**
 * Reads the refrigerated trailer probe: a temperature, a device id, and nothing else.
 *
 * <p>The smallest of the four feeds and the one that is most dependent on something outside itself.
 * A probe bolted inside a trailer knows its own serial number and how cold it is in there. It does
 * not know which truck is pulling the trailer, it does not know what freight is inside, and it has
 * no idea where it is — there is no GPS in it, because a receiver inside an insulated metal box
 * would not get a fix anyway.
 *
 * <h2>Why this feed is the argument for identity resolution</h2>
 *
 * <p>The other three feeds each name something the platform can act on. Telematics names a vehicle,
 * the phone and the carrier name a shipment. This one names {@code DEV-0003} and stops. Every
 * canonical event must carry a shipment id, because that is the Kafka key and therefore the thing
 * that guarantees a shipment's events stay in order — so without reference data mapping the device
 * to a trailer to a load, a temperature reading has nowhere to go at all.
 *
 * <p>The resolution is a lookup rather than a guess, and the difference matters. A pharmaceutical
 * load running two degrees warm is a claim that can void the freight's value and trigger a
 * customer's contractual penalty. Attributing that reading to the wrong shipment is materially
 * worse than losing it, so an unresolvable device is dead-lettered and replayed later once the
 * reference data catches up.
 *
 * <h2>What the reading becomes</h2>
 *
 * <p>A status event with a temperature and no position. Not a position event with a temperature
 * bolted on: the probe made no statement about where anything is, and an envelope full of nulls
 * with one real field in it invites a consumer to treat the nulls as data.
 *
 * <p>The deviation from setpoint is deliberately not computed here. It is a method on the
 * temperature reading itself, so every consumer derives it the same way from the two numbers the
 * device actually sent, rather than trusting a third number this service calculated once and can
 * never correct.
 */
public class ReeferNormalizer implements Normalizer {

  private final IdentityResolver identities;

  public ReeferNormalizer(IdentityResolver identities) {
    this.identities = identities;
  }

  @Override
  public SourceSystem source() {
    return SourceSystem.REEFER_SENSOR;
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

    String deviceId = payload.probe();
    if (deviceId == null || deviceId.isBlank()) {
      return NormalizationResult.rejected(RejectionReason.MISSING_FIELD, "probe");
    }
    if (payload.readingUtc() == null) {
      return NormalizationResult.rejected(RejectionReason.MISSING_FIELD, "readingUtc");
    }
    if (payload.tempC() == null) {
      // A reading with no temperature in it is not a reading. Rejected rather than published as a
      // heartbeat, because a consumer counting how long a load has gone unmonitored needs the
      // absence to be visible somewhere it will be looked at.
      return NormalizationResult.rejected(RejectionReason.MISSING_FIELD, "tempC");
    }

    Optional<Identity> identity = identities.byDevice(deviceId);
    if (identity.isEmpty()) {
      return NormalizationResult.rejected(
          RejectionReason.UNRESOLVED_IDENTITY, "no shipment assigned to device " + deviceId);
    }

    return NormalizationResult.of(
        new StatusEvent(
            // The probe is the reporter, and it is the only feed where the device id is genuinely
            // the source's own name for itself rather than one of several identifiers it carries.
            EventIds.of(SourceSystem.REEFER_SENSOR, deviceId, payload.readingUtc()),
            identity.get().shipmentId(),
            identity.get().vehicleId(),
            deviceId,
            payload.readingUtc(),
            message.receivedAt(),
            StatusCode.TEMPERATURE_READING,
            // No position and no place name. The probe made no claim about either.
            null,
            null,
            new TemperatureReading(payload.tempC(), payload.setpointC()),
            null,
            // The unit raises its own alarm when it drifts far enough from setpoint. Carried
            // through as the carrier's own words rather than recomputed: whether a deviation is an
            // exception worth acting on is M4's judgement, made against the customer's contract,
            // not the trailer unit's factory threshold.
            payload.alarm(),
            new RawPayload(SourceSystem.REEFER_SENSOR, message.contentType(), message.body())));
  }

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
  // Return and supply air temperatures are read and then dropped: they describe how hard the
  // refrigeration unit is working, which is a maintenance question, where this platform tracks
  // freight. Both survive in the raw payload if that ever changes. Door state is dropped for now
  // for the same reason and is the most likely of the three to be promoted later -- a trailer door
  // open for forty minutes at a delivery is a real freight event.
  // ---------------------------------------------------------------------------------------------

  record Payload(
      String probe,
      String model,
      Instant readingUtc,
      Double tempC,
      Double setpointC,
      Double returnAirC,
      Double supplyAirC,
      String door,
      Double batteryV,
      String alarm) {}
}
