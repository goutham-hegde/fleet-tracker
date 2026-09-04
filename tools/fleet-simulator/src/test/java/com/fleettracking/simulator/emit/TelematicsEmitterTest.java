package com.fleettracking.simulator.emit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fleettracking.events.SourceSystem;
import com.fleettracking.simulator.TickObserver;
import com.fleettracking.simulator.fleet.VehicleSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class TelematicsEmitterTest {

  private static final Duration TICK = Duration.ofSeconds(1);

  private final RecordingMessageSink sink = new RecordingMessageSink();

  private TelematicsEmitter emitter(Duration interval) {
    return new TelematicsEmitter(
        new EmissionProperties.Telematics(true, interval, "TLM"),
        sink,
        new Random(42),
        // Clean fixes: these tests are about the wire format, not about GPS error.
        com.fleettracking.simulator.fault.FaultProfile.none());
  }

  /** Drives an emitter through ticks of simulated time, holding the truck's state constant. */
  private static void run(TickObserver emitter, VehicleSnapshot truck, int ticks) {
    for (int i = 1; i <= ticks; i++) {
      Instant at = Snapshots.AT.plus(TICK.multipliedBy(i));
      emitter.onTick(Snapshots.report(at, i, Snapshots.at(truck, at)));
    }
  }

  @Test
  @DisplayName("reports on its configured cadence in simulated time")
  void reportsOnCadence() {
    run(emitter(Duration.ofSeconds(30)), Snapshots.driving(), 1200); // 20 simulated minutes

    // 1200 seconds at one report per 30 leaves 40, give or take the random starting phase.
    assertThat(sink.size()).isBetween(39, 41);
    assertThat(sink.from(SourceSystem.TELEMATICS)).hasSize(sink.size());
  }

  @Test
  @DisplayName("converts to imperial units on the wire")
  void emitsImperialUnits() {
    run(emitter(Duration.ofSeconds(30)), Snapshots.driving(), 60);

    JsonNode gps = sink.firstJson().get("gps");
    // 60 km/h is 37.3 mph. A normalizer that forgets to convert produces a truck doing 60 mph.
    assertThat(gps.get("speedMph").doubleValue()).isCloseTo(37.3, within(0.1));
    assertThat(sink.firstJson().get("odometer").get("unit").stringValue()).isEqualTo("mi");
    assertThat(sink.firstJson().get("odometer").get("value").doubleValue())
        .isCloseTo(76_712.4, within(0.5)); // 123456.789 km
  }

  @Test
  @DisplayName("carries no shipment id anywhere, because the box does not know one")
  void carriesNoShipmentIdentity() {
    run(emitter(Duration.ofSeconds(30)), Snapshots.driving(), 60);

    SourceMessage message = sink.messages().getFirst();
    assertThat(message.body()).doesNotContain("SHP-");
    assertThat(sink.firstJson().has("shipmentId")).isFalse();
    // The only identity it can offer downstream is the vehicle.
    assertThat(message.routingKey()).isEqualTo("VEH-0007");
  }

  @Test
  @DisplayName("uses its own device namespace, distinct from the reefer probe on the same truck")
  void usesItsOwnDeviceNamespace() {
    run(emitter(Duration.ofSeconds(30)), Snapshots.driving(), 60);

    // The snapshot's deviceId is DEV-0007; the telematics unit is a different box.
    assertThat(sink.firstJson().get("deviceId").stringValue()).isEqualTo("TLM-0007");
    assertThat(sink.messages().getFirst().body()).doesNotContain("DEV-0007");
  }

  @Test
  @DisplayName("nests position, odometer and engine the way a device vendor would")
  void nestsPayload() {
    run(emitter(Duration.ofSeconds(30)), Snapshots.driving(), 60);

    JsonNode payload = sink.firstJson();
    assertThat(payload.get("gps").isObject()).isTrue();
    assertThat(payload.get("vehicle").get("id").stringValue()).isEqualTo("VEH-0007");
    assertThat(payload.get("engine").get("rpm").intValue()).isGreaterThan(900);
    // HDOP, not metres. Converting it is the normalizer's problem.
    assertThat(payload.get("gps").get("hdop").doubleValue()).isBetween(0.7, 1.6);
    assertThat(payload.get("gps").has("accuracyMeters")).isFalse();
  }

  @Test
  @DisplayName("truncates coordinates to what a real receiver reports")
  void roundsCoordinates() {
    run(emitter(Duration.ofSeconds(30)), Snapshots.driving(), 60);

    // Six decimal places is about a tenth of a metre; the snapshot carries nine.
    assertThat(sink.firstJson().get("gps").get("lat").doubleValue()).isEqualTo(26.912346);
  }

  @Test
  @DisplayName("goes quiet once the truck has finished its route")
  void stopsWhenCompleted() {
    run(emitter(Duration.ofSeconds(30)), Snapshots.completed(), 600);

    assertThat(sink.messages()).isEmpty();
  }

  @Test
  @DisplayName("sends the moment the event happened, so there is no feed lag")
  void hasNoLag() {
    run(emitter(Duration.ofSeconds(30)), Snapshots.driving(), 60);

    assertThat(sink.messages().getFirst().lag()).isZero();
  }
}
