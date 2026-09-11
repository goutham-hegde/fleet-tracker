package com.fleettracking.publicview.lookup;

import static com.fleettracking.publicview.Events.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.publicview.lookup.Wire.PlannedStop;
import com.fleettracking.publicview.lookup.Wire.ShipmentDetail;
import com.fleettracking.publicview.lookup.Wire.ShipmentSummary;
import com.fleettracking.publicview.plan.Plans;
import com.fleettracking.publicview.store.ShipmentFacts;
import com.fleettracking.publicview.store.ShipmentFacts.IncidentFacts;
import com.fleettracking.publicview.store.ShipmentFacts.Position;
import com.fleettracking.publicview.store.ShipmentFacts.StopFacts;
import com.fleettracking.publicview.store.ShipmentFacts.Watermark;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The public page's judgements, against the real packaged plans. The HYD lane is three stops:
 * Genome Valley (the origin), Kurnool clinic, Bengaluru hospital.
 */
class PublicAssemblerTest {

  static final String HYD = "SHP-HYD-0002";
  static final Instant NOW = at(600);

  private final PublicAssembler assembler =
      new PublicAssembler(Plans.packaged(), Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void anArrivalWithNoDepartureIsAtAStopAndCountsAsDone() {
    ShipmentSummary s =
        summary(
            facts(
                HYD,
                position(0.0),
                stop("hyd-genome", 0, 40),
                stop("knl-clinic", 200, null)));

    assertThat(s.movement()).isEqualTo("AT_STOP");
    assertThat(s.atStop().stopId()).isEqualTo("knl-clinic");
    assertThat(s.atStop().since()).isEqualTo(at(200));
    assertThat(s.stopsCompleted()).isEqualTo(2);
    assertThat(s.stopsTotal()).isEqualTo(3);
  }

  @Test
  void everyPlannedStopArrivedAtIsDelivered() {
    ShipmentSummary s =
        summary(
            facts(
                HYD,
                position(0.0),
                stop("hyd-genome", 0, 40),
                stop("knl-clinic", 200, 240),
                stop("blr-hosp", 400, null)));

    assertThat(s.movement()).isEqualTo("DELIVERED");
    assertThat(s.stopsCompleted()).isEqualTo(3);
  }

  @Test
  void betweenStopsTheSpeedDecidesAtTheSameThresholdAsTheLiveView() {
    assertThat(summary(facts(HYD, position(5.0), stop("hyd-genome", 0, 40))).movement())
        .isEqualTo("MOVING");
    assertThat(summary(facts(HYD, position(4.9), stop("hyd-genome", 0, 40))).movement())
        .isEqualTo("STOPPED");
    assertThat(summary(facts(HYD, position(null), stop("hyd-genome", 0, 40))).movement())
        .isEqualTo("STOPPED");
  }

  @Test
  void aReRunStartsAFreshJourneyAtItsOriginArrival() {
    // Run one reached every stop. Run two, over the same shipment id after a demonstration reset,
    // has just arrived at the origin again. Without the journey rule this reads as delivered.
    ShipmentSummary s =
        summary(
            facts(
                HYD,
                position(0.0),
                stop("hyd-genome", 500, null),
                stop("knl-clinic", 200, 240),
                stop("blr-hosp", 400, 430)));

    assertThat(s.movement()).isEqualTo("AT_STOP");
    assertThat(s.atStop().stopId()).isEqualTo("hyd-genome");
    assertThat(s.stopsCompleted()).isEqualTo(1);

    List<PlannedStop> stops = detail(facts(HYD, position(0.0), stop("hyd-genome", 500, null), stop("knl-clinic", 200, 240))).stops();
    assertThat(stops).extracting(PlannedStop::status).containsExactly("AT", "PENDING", "PENDING");
  }

  @Test
  void aDepartureOlderThanItsArrivalIsFromAnEarlierVisit() {
    // Arrived at 300, but the stored departure is from 240: that departure belongs to a previous
    // visit, so the truck is at the stop now rather than already gone.
    ShipmentDetail d =
        detail(facts(HYD, position(0.0), stop("hyd-genome", 0, 40), stop("knl-clinic", 300, 240)));

    PlannedStop kurnool = d.stops().get(1);
    assertThat(kurnool.status()).isEqualTo("AT");
    assertThat(kurnool.departedAt()).isNull();
    assertThat(kurnool.dwellSeconds()).isNull();
  }

  @Test
  void theDetailCarriesThePlanWithRealNamesAndFenceSizes() {
    ShipmentDetail d =
        detail(facts(HYD, position(40.0), stop("hyd-genome", 0, 47)));

    assertThat(d.stops()).extracting(PlannedStop::name)
        .containsExactly("Genome Valley depot", "Kurnool clinic dock", "Bengaluru hospital dock");
    assertThat(d.stops()).extracting(PlannedStop::radiusMeters).containsExactly(400.0, 120.0, 120.0);
    assertThat(d.stops().get(0).dwellSeconds()).isEqualTo(Duration.ofMinutes(47).toSeconds());
  }

  @Test
  void aShipmentTheArchiveNeverPlacedDrawsNothing() {
    assertThat(assembler.summary(facts(HYD, null, stop("hyd-genome", 0, 40)))).isEmpty();
    assertThat(assembler.detail(facts(HYD, null))).isEmpty();
  }

  @Test
  void anUnplannedLoadIsDrawnWithNoRoute() {
    ShipmentSummary s = summary(facts("SHP-XYZ-0001", position(30.0)));

    assertThat(s.stopsTotal()).isZero();
    assertThat(s.movement()).isEqualTo("MOVING");
    assertThat(detail(facts("SHP-XYZ-0001", position(30.0))).stops()).isEmpty();
  }

  @Test
  void openIncidentsAreWorstFirstAndClearedOnesDoNotColourTheMarker() {
    ShipmentFacts f =
        new ShipmentFacts(
            HYD,
            position(0.0),
            Map.of(),
            List.of(
                incident("a", "WARNING", 10, null),
                incident("b", "CRITICAL", 20, null),
                incident("c", "CRITICAL", 5, 30)));

    ShipmentSummary s = summary(f);
    assertThat(s.openExceptions()).extracting(Wire.IncidentSummary::exceptionId).containsExactly("b", "a");
    assertThat(s.worstSeverity()).isEqualTo("CRITICAL");

    assertThat(assembler.incidents(List.of(f), false, 10))
        .extracting(Wire.IncidentSummary::exceptionId)
        .containsExactly("b", "a", "c");
    assertThat(assembler.incidents(List.of(f), true, 10)).hasSize(2);
  }

  @Test
  void aClearSeenWithoutItsRaiseIsStillACompleteClearedIncident() {
    ShipmentFacts f =
        new ShipmentFacts(HYD, position(0.0), Map.of(), List.of(incident("x", null, 10, 50)));

    Wire.IncidentSummary only = assembler.incidents(List.of(f), false, 10).get(0);
    assertThat(only.state()).isEqualTo("CLEARED");
    assertThat(only.onsetAt()).isEqualTo(at(10));
    assertThat(summary(f).openExceptions()).isNull();
  }

  @Test
  void stalenessIsWallClockFromWhenTheFixWasReceived() {
    // Received at minute 0 plus two seconds; the page is looked at ten hours later.
    assertThat(summary(facts(HYD, position(0.0))).staleSeconds())
        .isEqualTo(Duration.ofMinutes(600).toSeconds() - 2);
  }

  @Test
  void metaSaysArchiveAndHowFarItRuns() {
    Wire.Meta meta =
        assembler.meta(
            List.of(facts(HYD, position(0.0)), facts("SHP-DEL-0001", null)),
            List.of(
                new Watermark("position.events.v1", at(90), "k1", NOW),
                new Watermark("exceptions.v1", at(60), "k2", NOW)));

    assertThat(meta.source()).isEqualTo("archive");
    assertThat(meta.trackedShipments()).isEqualTo(1);
    assertThat(meta.archivedThrough()).isEqualTo(at(90));
  }

  // -----------------------------------------------------------------------------------------

  private ShipmentSummary summary(ShipmentFacts f) {
    return assembler.summary(f).orElseThrow();
  }

  private ShipmentDetail detail(ShipmentFacts f) {
    return assembler.detail(f).orElseThrow();
  }

  private static ShipmentFacts facts(String id, Position position, StopFacts... stops) {
    Map<String, StopFacts> byId = new LinkedHashMap<>();
    for (StopFacts s : stops) {
      byId.put(s.stopId(), s);
    }
    return new ShipmentFacts(id, position, byId, List.of());
  }

  private static Position position(Double kph) {
    return new Position(
        "e1", "TRK-02", "DEV-1", "TELEMATICS", at(0), at(0).plusSeconds(2), 17.3, 78.2, kph, 90.0, 6.0);
  }

  private static StopFacts stop(String stopId, Integer arrived, Integer departed) {
    return new StopFacts(
        stopId,
        arrived == null ? null : at(arrived),
        departed == null ? null : at(departed),
        null);
  }

  private static IncidentFacts incident(String id, String severity, int onset, Integer cleared) {
    return new IncidentFacts(
        id, HYD, "UNPLANNED_STOP", severity, "detail", null, at(onset),
        cleared == null ? null : at(cleared), null, null, cleared == null ? null : "recovered");
  }
}
