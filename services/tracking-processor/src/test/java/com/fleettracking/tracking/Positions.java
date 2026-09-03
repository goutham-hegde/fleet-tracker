package com.fleettracking.tracking;

import com.fleettracking.events.GeoPoint;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.RawPayload;
import com.fleettracking.events.SourceSystem;
import java.time.Duration;
import java.time.Instant;

/**
 * Position events for tests, built rather than captured.
 *
 * <p>The gateway's tests read committed fixtures from {@code docs/samples}, because what they are
 * testing is agreement with a real carrier's wire format. This service never sees a wire format —
 * it consumes canonical envelopes that the gateway has already validated — so what its tests need
 * is control over timestamps and identifiers, not fidelity to a vendor. Building them here is the
 * honest tool for that job.
 */
public final class Positions {

  /** Well before the committed fixtures, so a test's instants never collide with real capture. */
  public static final Instant T0 = Instant.parse("2026-09-01T08:00:00Z");

  private Positions() {}

  /** A position for one shipment at an offset from {@link #T0}. */
  public static PositionEvent at(String shipmentId, Duration afterT0) {
    return at(shipmentId, afterT0, 41.8781, -87.6298);
  }

  /** A position for one shipment at an offset from {@link #T0}, at a stated point. */
  public static PositionEvent at(
      String shipmentId, Duration afterT0, double latitude, double longitude) {
    Instant occurredAt = T0.plus(afterT0);
    return new PositionEvent(
        // Mirrors the gateway's rule that an event id is derived from what the source said, never
        // random: the same shipment and the same instant must produce the same id here too, or the
        // duplicate-suppression tests would be testing nothing.
        "evt-" + shipmentId + "-" + occurredAt.toEpochMilli(),
        shipmentId,
        "VEH-" + shipmentId,
        "TLM-" + shipmentId,
        occurredAt,
        occurredAt.plusSeconds(2),
        new GeoPoint(latitude, longitude),
        88.5,
        271.0,
        123456.0,
        6.0,
        RawPayload.of(SourceSystem.TELEMATICS, "{\"probe\":\"" + shipmentId + "\"}"));
  }
}
