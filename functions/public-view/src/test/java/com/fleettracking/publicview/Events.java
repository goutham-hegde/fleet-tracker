package com.fleettracking.publicview;

import com.fleettracking.events.EtaUpdated;
import com.fleettracking.events.ExceptionCleared;
import com.fleettracking.events.ExceptionRaised;
import com.fleettracking.events.ExceptionType;
import com.fleettracking.events.GeoPoint;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.RawPayload;
import com.fleettracking.events.Severity;
import com.fleettracking.events.ShipmentArrived;
import com.fleettracking.events.ShipmentDeparted;
import com.fleettracking.events.SourceSystem;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Canonical events for tests, built with the platform's own records. */
public final class Events {

  public static final Instant T0 = Instant.parse("2026-09-11T06:00:00Z");

  private Events() {}

  public static Instant at(int minutes) {
    return T0.plus(Duration.ofMinutes(minutes));
  }

  public static PositionEvent position(String shipmentId, int minute, double lat, double lon, Double kph) {
    return new PositionEvent(
        UUID.randomUUID().toString(),
        shipmentId,
        "TRK-" + shipmentId.substring(shipmentId.length() - 2),
        "DEV-1",
        at(minute),
        at(minute).plusSeconds(2),
        new GeoPoint(lat, lon),
        kph,
        90.0,
        null,
        6.0,
        RawPayload.of(SourceSystem.TELEMATICS, "{}"));
  }

  public static ShipmentArrived arrived(String shipmentId, String stopId, int minute) {
    return new ShipmentArrived(
        "arr-" + shipmentId + "-" + stopId + "-" + minute,
        shipmentId,
        at(minute),
        "cause",
        stopId,
        new GeoPoint(17.0, 78.0),
        null);
  }

  public static ShipmentDeparted departed(String shipmentId, String stopId, int arrivedMinute, int minute) {
    return new ShipmentDeparted(
        "dep-" + shipmentId + "-" + stopId + "-" + minute,
        shipmentId,
        at(minute),
        "cause",
        stopId,
        new GeoPoint(17.0, 78.0),
        Duration.ofMinutes(minute - arrivedMinute));
  }

  public static ExceptionRaised raised(String shipmentId, String exceptionId, Severity severity, int onset) {
    return new ExceptionRaised(
        "raise-" + exceptionId,
        shipmentId,
        at(onset),
        "cause",
        exceptionId,
        ExceptionType.UNPLANNED_STOP,
        severity,
        "Stationary for 40 minutes",
        null,
        40.0,
        20.0);
  }

  public static ExceptionCleared cleared(String shipmentId, String exceptionId, int onset, int minute) {
    return new ExceptionCleared(
        "clear-" + exceptionId,
        shipmentId,
        at(minute),
        "cause",
        exceptionId,
        ExceptionType.UNPLANNED_STOP,
        at(onset),
        Duration.ofMinutes(minute - onset),
        "recovered");
  }

  public static EtaUpdated estimate(String shipmentId, String stopId, int minute) {
    return new EtaUpdated(
        "eta-" + minute, shipmentId, at(minute), "cause", stopId, at(minute + 90), null, 100.0, 0.8);
  }
}
