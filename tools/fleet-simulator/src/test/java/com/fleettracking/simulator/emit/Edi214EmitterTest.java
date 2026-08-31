package com.fleettracking.simulator.emit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.simulator.fleet.TruckTransition;
import com.fleettracking.simulator.fleet.VehicleSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Edi214EmitterTest {

  private static final Duration TICK = Duration.ofMinutes(1);

  private final RecordingMessageSink sink = new RecordingMessageSink();

  private Edi214Emitter emitter() {
    return new Edi214Emitter(
        new EmissionProperties.Edi(
            true, Duration.ofMinutes(30), Duration.ofMinutes(45), "FLTX", "CARRIER01",
            "FLEETTRACK"),
        sink);
  }

  /**
   * Feeds the given transitions on the next tick, then lets simulated time run on.
   *
   * <p>The tick counter is a field rather than a local so that two calls continue one timeline
   * instead of each replaying it from the start — which matters here more than anywhere else in
   * this module, because this emitter's whole behaviour is about elapsed time.
   */
  private int tick;

  private void run(Edi214Emitter emitter, int ticks, List<TruckTransition> transitions) {
    VehicleSnapshot truck = Snapshots.driving();
    for (int i = 1; i <= ticks; i++) {
      Instant at = Snapshots.AT.plus(TICK.multipliedBy(++tick));
      emitter.onTick(
          Snapshots.report(at, tick, List.of(Snapshots.at(truck, at)), i == 1 ? transitions : List.of()));
    }
  }

  private static List<TruckTransition> departedPickup() {
    return List.of(
        new TruckTransition.Departed("VEH-0007", "SHP-CHI-0007", Snapshots.PICKUP, Snapshots.AT));
  }

  @Test
  @DisplayName("files nothing until the back office has entered it and a batch window has passed")
  void isDelayedTwice() {
    Edi214Emitter emitter = emitter();

    run(emitter, 30, departedPickup()); // 30 minutes: batch window due, filing delay not yet met
    assertThat(sink.messages()).isEmpty();
    assertThat(emitter.pendingCount()).isEqualTo(1);

    run(emitter, 60, List.of()); // carry on past the 45-minute filing delay
    assertThat(sink.messages()).hasSize(1);
    assertThat(sink.messages().getFirst().lag()).isGreaterThanOrEqualTo(Duration.ofMinutes(45));
  }

  @Test
  @DisplayName("wraps the statuses in a complete ISA/GS interchange envelope")
  void writesAValidEnvelope() {
    run(emitter(), 120, departedPickup());

    String body = sink.messages().getFirst().body();
    assertThat(body).startsWith("ISA*00*");
    assertThat(body).contains("GS*QM*CARRIER01*FLEETTRACK*");
    assertThat(body).contains("ST*214*0001~");
    assertThat(body).contains("SE*6*0001~");
    assertThat(body).contains("GE*1*");
    assertThat(body).contains("IEA*1*");
    // The terminator is ~, and every segment must carry one.
    long segments = body.chars().filter(c -> c == '~').count();
    assertThat(segments).isEqualTo(body.lines().count());
  }

  @Test
  @DisplayName("names a city and a state and carries no coordinates at all")
  void carriesNoCoordinates() {
    run(emitter(), 120, departedPickup());

    String body = sink.messages().getFirst().body();
    assertThat(body).contains("MS1*CHICAGO*IL*US~");
    // The stop's real position is 41.8781, -87.6298 and none of it goes on the wire.
    assertThat(body).doesNotContain("41.8");
    assertThat(body).doesNotContain("-87.6");
    // Nor does the carrier know this platform's stop identifiers.
    assertThat(body).doesNotContain("chi-dc");
  }

  @Test
  @DisplayName("uses the carrier's own status vocabulary, not the platform's")
  void usesX12StatusCodes() {
    run(emitter(), 120, departedPickup());
    // AF: carrier departed pick-up location with shipment.
    assertThat(sink.messages().getFirst().body()).contains("AT7*AF*NS***");

    RecordingMessageSink arrivals = new RecordingMessageSink();
    Edi214Emitter arrivalEmitter =
        new Edi214Emitter(
            new EmissionProperties.Edi(
                true, Duration.ofMinutes(30), Duration.ofMinutes(45), "FLTX", "CARRIER01",
                "FLEETTRACK"),
            arrivals);
    run(
        arrivalEmitter,
        120,
        List.of(
            new TruckTransition.Arrived(
                "VEH-0007", "SHP-CHI-0007", Snapshots.DELIVERY, Snapshots.AT)));
    // X1: arrived at delivery location.
    assertThat(arrivals.messages().getFirst().body()).contains("AT7*X1*NS***");
  }

  @Test
  @DisplayName("keeps the empty appointment elements that hold later positions")
  void preservesEmptyElements() {
    run(emitter(), 120, departedPickup());

    String at7 =
        sink.messages().getFirst().body().lines()
            .filter(line -> line.startsWith("AT7"))
            .findFirst()
            .orElseThrow();
    // AT7-03 and AT7-04 are unpopulated; collapsing them would shift the date into their place.
    String[] elements = at7.replace("~", "").split("\\*", -1);
    assertThat(elements[3]).isEmpty();
    assertThat(elements[4]).isEmpty();
    assertThat(elements[5]).hasSize(8); // CCYYMMDD
    assertThat(elements[6]).hasSize(4); // HHMM, no seconds
  }

  @Test
  @DisplayName("never files a fuel stop, because a waypoint is not a freight event")
  void ignoresWaypoints() {
    run(
        emitter(),
        120,
        List.of(
            new TruckTransition.Arrived(
                "VEH-0007", "SHP-CHI-0007", Snapshots.WAYPOINT, Snapshots.AT)));

    assertThat(sink.messages()).isEmpty();
  }

  @Test
  @DisplayName("batches several shipments into one interchange with no single key")
  void batchesManyShipments() {
    run(
        emitter(),
        120,
        List.of(
            new TruckTransition.Departed(
                "VEH-0007", "SHP-CHI-0007", Snapshots.PICKUP, Snapshots.AT),
            new TruckTransition.Arrived(
                "VEH-0002", "SHP-LAX-0002", Snapshots.DELIVERY, Snapshots.AT)));

    assertThat(sink.messages()).hasSize(1);
    String body = sink.messages().getFirst().body();
    assertThat(body).contains("ST*214*0001~").contains("ST*214*0002~").contains("GE*2*");
    assertThat(body).contains("SHP-CHI-0007").contains("SHP-LAX-0002");
    // A batch belongs to no single shipment, so it cannot be keyed until M2 splits it.
    assertThat(sink.messages().getFirst().routingKey()).isNull();
  }
}
