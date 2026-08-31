package com.fleettracking.simulator.emit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.simulator.TickObserver;
import com.fleettracking.simulator.fleet.VehicleSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ReeferEmitterTest {

  private static final Duration TICK = Duration.ofSeconds(30);

  private final RecordingMessageSink sink = new RecordingMessageSink();

  private ReeferEmitter emitter(boolean onlyColdChain) {
    return new ReeferEmitter(
        new EmissionProperties.Reefer(true, Duration.ofMinutes(5), onlyColdChain, "ThermoKing-CX7"),
        sink,
        new Random(7));
  }

  private static void run(TickObserver emitter, int ticks, VehicleSnapshot... trucks) {
    for (int i = 1; i <= ticks; i++) {
      Instant at = Snapshots.AT.plus(TICK.multipliedBy(i));
      VehicleSnapshot[] moved = new VehicleSnapshot[trucks.length];
      for (int t = 0; t < trucks.length; t++) {
        moved[t] = Snapshots.at(trucks[t], at);
      }
      emitter.onTick(Snapshots.report(at, i, moved));
    }
  }

  @Test
  @DisplayName("reports temperature, setpoint and nothing that locates the trailer")
  void reportsTemperatureAndNoPosition() {
    run(emitter(true), 40, Snapshots.coldChainDwelling());

    JsonNode payload = sink.firstJson();
    assertThat(payload.get("tempC").doubleValue()).isEqualTo(4.2);
    assertThat(payload.get("setpointC").doubleValue()).isEqualTo(4.0);

    // The three things it cannot possibly know.
    assertThat(payload.has("lat")).isFalse();
    assertThat(payload.has("lon")).isFalse();
    assertThat(sink.messages().getFirst().body()).doesNotContain("SHP-");
    assertThat(sink.messages().getFirst().body()).doesNotContain("VEH-");
  }

  @Test
  @DisplayName("offers a device id as its only identity, which is what S8 has to resolve")
  void carriesOnlyADeviceId() {
    run(emitter(true), 40, Snapshots.coldChainDwelling());

    assertThat(sink.firstJson().get("probe").stringValue()).isEqualTo("DEV-0002");
    assertThat(sink.messages().getFirst().routingKey()).isEqualTo("DEV-0002");
  }

  @Test
  @DisplayName("only refrigerated lanes carry a probe")
  void skipsDryVans() {
    run(emitter(true), 40, Snapshots.driving(), Snapshots.coldChainDwelling());

    assertThat(sink.messages()).isNotEmpty();
    // The dry van on the Chicago lane reports nothing at all.
    assertThat(sink.messages()).allSatisfy(m -> assertThat(m.routingKey()).isEqualTo("DEV-0002"));
  }

  @Test
  @DisplayName("every trailer reports when only-cold-chain is switched off")
  void canCoverTheWholeFleet() {
    run(emitter(false), 40, Snapshots.driving(), Snapshots.coldChainDwelling());

    assertThat(sink.messages().stream().map(SourceMessage::routingKey).distinct())
        .containsExactlyInAnyOrder("DEV-0007", "DEV-0002");
  }

  @Test
  @DisplayName("doors are open on a dock and shut on the road")
  void reportsDoorState() {
    run(emitter(true), 40, Snapshots.coldChainDwelling());
    assertThat(sink.firstJson().get("door").stringValue()).isEqualTo("OPEN");

    RecordingMessageSink driving = new RecordingMessageSink();
    ReeferEmitter onTheRoad =
        new ReeferEmitter(
            new EmissionProperties.Reefer(true, Duration.ofMinutes(5), false, "ThermoKing-CX7"),
            driving,
            new Random(7));
    run(onTheRoad, 40, Snapshots.driving());
    assertThat(driving.firstJson().get("door").stringValue()).isEqualTo("CLOSED");
  }

  @Test
  @DisplayName("raises its own alarm flag once the box drifts far from setpoint")
  void flagsExcursions() {
    run(emitter(true), 40, Snapshots.coldChainDwelling());
    // 4.2 against a 4.0 setpoint is healthy, and the field is absent rather than null.
    assertThat(sink.firstJson().has("alarm")).isFalse();

    RecordingMessageSink warm = new RecordingMessageSink();
    ReeferEmitter warming =
        new ReeferEmitter(
            new EmissionProperties.Reefer(true, Duration.ofMinutes(5), true, "ThermoKing-CX7"),
            warm,
            new Random(7));
    run(warming, 40, Snapshots.coldChainWarm());
    assertThat(warm.firstJson().get("alarm").stringValue()).isEqualTo("TEMP_DEVIATION");
  }

  @Test
  @DisplayName("supply air is colder than the box and return air is warmer")
  void reportsAirflowTemperatures() {
    run(emitter(true), 40, Snapshots.coldChainDwelling());

    JsonNode payload = sink.firstJson();
    double measured = payload.get("tempC").doubleValue();
    assertThat(payload.get("supplyAirC").doubleValue()).isLessThan(measured);
    assertThat(payload.get("returnAirC").doubleValue()).isGreaterThan(measured);
  }
}
