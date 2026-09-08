package com.fleettracking.dashboard.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.dashboard.read.ShipmentSummary.Movement;
import com.fleettracking.reference.Itinerary;
import com.fleettracking.reference.ScheduledStop;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;

/**
 * The reconciliations, tested by calling a method.
 *
 * <p>Every case here is a disagreement between two services' documents that a viewer must never be
 * shown: an estimate about a stop the truck has already left, a stationary truck that is stationary
 * because it is unloading, a distance recorded when an estimate was last worth publishing. None of
 * it needs a broker or a database, which is the point of keeping the judgement out of the reader.
 */
class FleetAssemblerTest {

  private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
  private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

  private final FleetAssembler assembler =
      new FleetAssembler(new RemainingDistance(1.30), FIXED);

  private static final ScheduledStop PICKUP = stop("S1", 0, 18.5204, 73.8567);
  private static final ScheduledStop MIDDLE = stop("S2", 1, 19.5000, 73.8567);
  private static final ScheduledStop DROP = stop("S3", 2, 21.0000, 73.8567);

  private static final Itinerary PLAN =
      new Itinerary("SHP-1", "LANE-1", List.of(PICKUP, MIDDLE, DROP));

  private static ScheduledStop stop(String id, int seq, double lat, double lon) {
    return new ScheduledStop(id, seq, "Stop " + id, "City" + id, "ST", lat, lon, 400.0, "DELIVERY");
  }

  private static Views.PositionView at(double lat, double lon, Double speedKph) {
    return new Views.PositionView(
        "SHP-1",
        "evt-1",
        "VEH-1",
        "DEV-1",
        NOW.minusSeconds(30),
        NOW.minusSeconds(20),
        NOW.minusSeconds(20),
        new GeoJsonPoint(lon, lat),
        speedKph,
        0.0,
        120000.0,
        6.0,
        "TELEMATICS");
  }

  private static Views.GeofenceView cleared(String stopId) {
    return new Views.GeofenceView(
        "SHP-1|" + stopId,
        "SHP-1",
        stopId,
        false,
        NOW.minusSeconds(7200),
        NOW.minusSeconds(3600),
        true,
        NOW.minusSeconds(7200),
        true,
        NOW.minusSeconds(3600));
  }

  private static Views.GeofenceView sittingAt(String stopId) {
    return new Views.GeofenceView(
        "SHP-1|" + stopId,
        "SHP-1",
        stopId,
        true,
        NOW.minusSeconds(1800),
        null,
        true,
        NOW.minusSeconds(1800),
        false,
        NOW.minusSeconds(60));
  }

  private static Views.EtaView estimateFor(String stopId, Instant arrival) {
    return new Views.EtaView("SHP-1", stopId, arrival, 0.82, NOW.minusSeconds(30), NOW.minusSeconds(30));
  }

  private static Views.IncidentView incident(String id, String type, String severity, String state) {
    return new Views.IncidentView(
        id,
        "SHP-1",
        type,
        null,
        severity,
        state,
        NOW.minusSeconds(3600),
        NOW.minusSeconds(3000),
        "CLEARED".equals(state) ? NOW.minusSeconds(600) : null,
        NOW.minusSeconds(120),
        "something happened",
        31.7,
        8.0,
        null);
  }

  @Test
  @DisplayName("a truck on the road gets the estimate for the stop it is heading for")
  void movingBetweenStops() {
    Instant eta = NOW.plusSeconds(5400);

    ShipmentSummary summary =
        assembler.summarize(
            at(19.0, 73.8567, 62.0),
            PLAN,
            List.of(cleared("S1")),
            estimateFor("S2", eta),
            List.of());

    assertThat(summary.movement()).isEqualTo(Movement.MOVING);
    assertThat(summary.atStop()).isNull();
    assertThat(summary.nextStop().stopId()).isEqualTo("S2");
    assertThat(summary.nextStop().estimatedArrival()).isEqualTo(eta);
    assertThat(summary.nextStop().estimatePending()).isNull();
    assertThat(summary.stopsCompleted()).isEqualTo(1);
    assertThat(summary.stopsTotal()).isEqualTo(3);
  }

  @Test
  @DisplayName("the distance to the next stop is measured now, not read from the estimate")
  void distanceIsMeasuredNotRemembered() {
    // The same ETA document, and two positions half a degree apart. If the dashboard were reading
    // the stored remainingKm -- which is only rewritten when an estimate moves by two minutes --
    // both of these would report the same distance. This is the S11 limitation M5 had to settle.
    Views.EtaView oneEstimate = estimateFor("S2", NOW.plusSeconds(5400));

    ShipmentSummary far =
        assembler.summarize(at(18.6, 73.8567, 62.0), PLAN, List.of(cleared("S1")), oneEstimate, List.of());
    ShipmentSummary near =
        assembler.summarize(at(19.4, 73.8567, 62.0), PLAN, List.of(cleared("S1")), oneEstimate, List.of());

    assertThat(far.nextStop().remainingKm()).isGreaterThan(near.nextStop().remainingKm());

    // And it is the road distance, not the straight line: one degree of latitude is about 111 km,
    // so 0.1 degrees billed at 1.30 is about 14.4.
    assertThat(near.nextStop().remainingKm()).isCloseTo(14.4, Offset.offset(0.6));
  }

  @Test
  @DisplayName("a truck sitting at a dock is AT_STOP, and its estimate is withheld with a reason")
  void atAStopTheEstimateIsSuppressed() {
    ShipmentSummary summary =
        assembler.summarize(
            at(19.5, 73.8567, 0.0),
            PLAN,
            List.of(cleared("S1"), sittingAt("S2")),
            // The stored estimate still names S2 -- the stop the truck is standing in -- because
            // nothing is published while a truck is at a stop.
            estimateFor("S2", NOW.minusSeconds(1800)),
            List.of());

    assertThat(summary.movement()).isEqualTo(Movement.AT_STOP);
    assertThat(summary.atStop().stopId()).isEqualTo("S2");
    assertThat(summary.atStop().since()).isEqualTo(NOW.minusSeconds(1800));

    // S2 is announced as arrived, so the next stop has moved on to S3.
    assertThat(summary.nextStop().stopId()).isEqualTo("S3");
    assertThat(summary.nextStop().estimatedArrival()).isNull();
    assertThat(summary.nextStop().estimatePending()).isEqualTo(FleetAssembler.PENDING_AT_A_STOP);
    // The distance is still measured and still useful, even with no estimate to attach it to.
    assertThat(summary.nextStop().remainingKm()).isPositive();
  }

  @Test
  @DisplayName("an estimate naming a stop already cleared is withheld as superseded")
  void supersededEstimate() {
    // The truck has left S2 and is driving to S3, but no new estimate has been published yet --
    // the first fix of a leg publishes one, so this is the window before it.
    ShipmentSummary summary =
        assembler.summarize(
            at(19.6, 73.8567, 55.0),
            PLAN,
            List.of(cleared("S1"), cleared("S2")),
            estimateFor("S2", NOW.minusSeconds(1800)),
            List.of());

    assertThat(summary.movement()).isEqualTo(Movement.MOVING);
    assertThat(summary.nextStop().stopId()).isEqualTo("S3");
    assertThat(summary.nextStop().estimatedArrival()).isNull();
    assertThat(summary.nextStop().estimatePending()).isEqualTo(FleetAssembler.PENDING_SUPERSEDED);
  }

  @Test
  @DisplayName("a shipment with every stop cleared is delivered and has nothing ahead")
  void delivered() {
    ShipmentSummary summary =
        assembler.summarize(
            at(21.0, 73.8567, 0.0),
            PLAN,
            List.of(cleared("S1"), cleared("S2"), cleared("S3")),
            estimateFor("S3", NOW.minusSeconds(600)),
            List.of());

    assertThat(summary.movement()).isEqualTo(Movement.DELIVERED);
    assertThat(summary.nextStop()).isNull();
    assertThat(summary.stopsCompleted()).isEqualTo(3);
    assertThat(summary.remainingRouteKm()).isZero();
  }

  @Test
  @DisplayName("a shipment nobody planned still gets a marker")
  void unplannedShipmentIsNotAnError() {
    ShipmentSummary summary = assembler.summarize(at(19.0, 73.8567, 40.0), null, List.of(), null, List.of());

    assertThat(summary.movement()).isEqualTo(Movement.MOVING);
    assertThat(summary.stopsTotal()).isZero();
    assertThat(summary.nextStop()).isNull();
    assertThat(summary.fractionComplete()).isNull();
    assertThat(summary.remainingRouteKm()).isNull();
  }

  @Test
  @DisplayName("no estimate at all is a different pending reason from a superseded one")
  void noEstimateYet() {
    ShipmentSummary summary =
        assembler.summarize(at(18.6, 73.8567, 30.0), PLAN, List.of(), null, List.of());

    assertThat(summary.nextStop().stopId()).isEqualTo("S1");
    assertThat(summary.nextStop().estimatePending()).isEqualTo(FleetAssembler.PENDING_NO_ESTIMATE);
  }

  @Test
  @DisplayName("open exceptions come back worst first, and set the summary's severity")
  void worstFirst() {
    ShipmentSummary summary =
        assembler.summarize(
            at(19.0, 73.8567, 50.0),
            PLAN,
            List.of(cleared("S1")),
            estimateFor("S2", NOW.plusSeconds(600)),
            List.of(
                incident("x-1", "UNPLANNED_STOP", "WARNING", "OPEN"),
                incident("x-2", "TEMPERATURE_EXCURSION", "CRITICAL", "OPEN"),
                incident("x-3", "ROUTE_DEVIATION", "WARNING", "CLEARED")));

    assertThat(summary.worstSeverity()).isEqualTo("CRITICAL");
    assertThat(summary.openExceptions()).hasSize(2);
    assertThat(summary.openExceptions().get(0).exceptionId()).isEqualTo("x-2");
  }

  @Test
  @DisplayName("a clean shipment sends no exception list at all")
  void cleanShipmentsSendNothing() {
    ShipmentSummary summary =
        assembler.summarize(at(19.0, 73.8567, 50.0), PLAN, List.of(cleared("S1")), null, List.of());

    assertThat(summary.openExceptions()).isNull();
    assertThat(summary.worstSeverity()).isNull();
  }

  @Test
  @DisplayName("a severity this build has never heard of sorts last instead of throwing")
  void anUnknownSeverityDoesNotTakeTheFleetViewDown() {
    ShipmentSummary summary =
        assembler.summarize(
            at(19.0, 73.8567, 50.0),
            PLAN,
            List.of(cleared("S1")),
            null,
            List.of(
                incident("x-1", "SOMETHING_NEW", "CATASTROPHIC", "OPEN"),
                incident("x-2", "UNPLANNED_STOP", "WARNING", "OPEN")));

    assertThat(summary.openExceptions()).hasSize(2);
    assertThat(summary.openExceptions().get(0).severity()).isEqualTo("WARNING");
    assertThat(summary.openExceptions().get(1).severity()).isEqualTo("CATASTROPHIC");
  }

  @Test
  @DisplayName("staleness never goes negative when simulated time runs ahead of the wall clock")
  void stalenessUnderATimeScaledRun() {
    // At time-scale 300 the simulator's clock outruns the wall clock, so occurredAt -- and with a
    // fast enough gateway, receivedAt -- can be in the future. A raw subtraction would report every
    // truck on the road as minus forty minutes stale.
    Views.PositionView fromTheFuture =
        new Views.PositionView(
            "SHP-1",
            "evt-1",
            "VEH-1",
            "DEV-1",
            NOW.plusSeconds(2400),
            NOW.plusSeconds(2400),
            NOW,
            new GeoJsonPoint(73.8567, 19.0),
            60.0,
            0.0,
            120000.0,
            6.0,
            "TELEMATICS");

    ShipmentSummary summary = assembler.summarize(fromTheFuture, PLAN, List.of(), null, List.of());

    assertThat(summary.staleSeconds()).isZero();
  }

  @Test
  @DisplayName("the detail folds the plan, the paperwork and the trail around the same summary")
  void detailWrapsTheSummary() {
    ShipmentSummary summary =
        assembler.summarize(
            at(19.5, 73.8567, 0.0),
            PLAN,
            List.of(cleared("S1"), sittingAt("S2")),
            estimateFor("S2", NOW.minusSeconds(1800)),
            List.of());

    ShipmentDetail detail =
        assembler.detail(
            summary,
            PLAN,
            List.of(cleared("S1"), sittingAt("S2")),
            new Views.ManifestView(
                "SHP-1",
                "MEDIVAULT",
                "COLD_CHAIN",
                "1.0.0",
                NOW.minusSeconds(86400),
                java.util.Map.of("temperature", java.util.Map.of("minCelsius", 2, "maxCelsius", 8))),
            List.of(incident("x-1", "TEMPERATURE_EXCURSION", "CRITICAL", "CLEARED")),
            List.of(
                new Views.TrackPointView(
                    "e1", NOW.minusSeconds(120), "SHP-1", new GeoJsonPoint(73.85, 19.4), 50.0, 0.0, "TELEMATICS")));

    assertThat(detail.summary()).isSameAs(summary);
    assertThat(detail.stops()).hasSize(3);
    assertThat(detail.stops().get(0).status()).isEqualTo(ShipmentDetail.PlannedStop.DEPARTED);
    assertThat(detail.stops().get(0).dwellSeconds()).isEqualTo(3600);
    assertThat(detail.stops().get(1).status()).isEqualTo(ShipmentDetail.PlannedStop.AT);
    assertThat(detail.stops().get(2).status()).isEqualTo(ShipmentDetail.PlannedStop.PENDING);

    // The body is handed through as the customer sent it -- no field of it is known to this service.
    assertThat(detail.manifest().body()).containsKey("temperature");

    // A cleared incident still appears on the detail. The fleet view showed nothing for this load.
    assertThat(detail.exceptions()).hasSize(1);
    assertThat(summary.openExceptions()).isNull();

    assertThat(detail.track()).hasSize(1);
  }
}
