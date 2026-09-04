package com.fleettracking.simulator.emit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fleettracking.simulator.fleet.TruckTransition;
import com.fleettracking.simulator.fleet.VehicleSnapshot;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MobileAppEmitterTest {

  private static final Duration TICK = Duration.ofSeconds(30);

  private final RecordingMessageSink sink = new RecordingMessageSink();

  private MobileAppEmitter emitter(double outageProbability) {
    return new MobileAppEmitter(
        new EmissionProperties.Mobile(
            true, Duration.ofMinutes(3), outageProbability, Duration.ofMinutes(25), "3.4.1"),
        sink,
        new Random(11),
        com.fleettracking.simulator.fault.FaultProfile.none());
  }

  private void run(MobileAppEmitter emitter, int ticks, List<TruckTransition> transitions) {
    VehicleSnapshot truck = Snapshots.driving();
    for (int i = 1; i <= ticks; i++) {
      Instant at = Snapshots.AT.plus(TICK.multipliedBy(i));
      emitter.onTick(
          Snapshots.report(
              at, i, List.of(Snapshots.at(truck, at)), i == 1 ? transitions : List.of()));
    }
  }

  @Test
  @DisplayName("uses abbreviated keys, epoch milliseconds and metres per second")
  void usesItsOwnWireShape() {
    run(emitter(0), 40, List.of());

    JsonNode payload = sink.firstJson();
    assertThat(payload.get("sid").stringValue()).isEqualTo("SHP-DEL-0007");
    // Epoch millis, not an ISO string -- a different time representation from every other feed.
    assertThat(payload.get("ts").longValue()).isGreaterThan(1_700_000_000_000L);
    // 60 km/h is 16.7 m/s. Neither km/h nor mph.
    assertThat(payload.get("spd").doubleValue()).isCloseTo(16.7, within(0.1));
    assertThat(payload.get("acc").doubleValue()).isBetween(4.0, 30.0);
    assertThat(payload.get("app").stringValue()).isEqualTo("3.4.1");
  }

  @Test
  @DisplayName("knows the shipment and not the vehicle, the exact inverse of telematics")
  void knowsTheShipmentNotTheVehicle() {
    run(emitter(0), 40, List.of());

    assertThat(sink.messages().getFirst().body()).doesNotContain("VEH-");
    assertThat(sink.messages().getFirst().routingKey()).isEqualTo("SHP-DEL-0007");
  }

  @Test
  @DisplayName("sends a status event when the driver taps arrive or depart")
  void reportsDriverTaps() {
    run(
        emitter(0),
        40,
        List.of(
            new TruckTransition.Arrived(
                "VEH-0007", "SHP-DEL-0007", Snapshots.DELIVERY, Snapshots.AT)));

    JsonNode first = sink.firstJson();
    assertThat(first.get("evt").stringValue()).isEqualTo("arrive");
    assertThat(first.get("stop").stringValue()).isEqualTo("amd-aslali");
    // An ordinary ping carries no stop at all, so the shape varies between messages.
    assertThat(sink.json(1).has("stop")).isFalse();
    assertThat(sink.json(1).get("evt").stringValue()).isEqualTo("ping");
  }

  @Test
  @DisplayName("is punctual when the dead-zone fault is switched off")
  void isPunctualWithoutOutages() {
    run(emitter(0), 200, List.of());

    assertThat(sink.messages()).isNotEmpty();
    assertThat(sink.messages()).allSatisfy(m -> assertThat(m.lag()).isZero());
  }

  @Test
  @DisplayName("goes quiet in a dead zone and dumps the backlog on reconnect")
  void buffersThroughAnOutage() {
    run(emitter(1.0), 400, List.of()); // always loses signal at the first opportunity

    assertThat(sink.messages()).isNotEmpty();
    // Everything that arrives after an outage describes a moment well in the past.
    assertThat(sink.messages().stream().filter(m -> !m.lag().isZero()).count()).isPositive();
    assertThat(sink.messages().stream().mapToLong(m -> m.lag().toMinutes()).max().orElse(0))
        .isGreaterThanOrEqualTo(3);
  }

  @Test
  @DisplayName("the reconnect burst arrives out of order")
  void burstIsOutOfOrder() {
    run(emitter(1.0), 400, List.of());

    boolean chronological = true;
    List<SourceMessage> messages = sink.messages();
    for (int i = 1; i < messages.size(); i++) {
      if (messages.get(i).occurredAt().isBefore(messages.get(i - 1).occurredAt())) {
        chronological = false;
        break;
      }
    }
    assertThat(chronological)
        .as("a consumer trusting arrival order would read these backwards")
        .isFalse();
  }

  @Test
  @DisplayName("repeats a message whose acknowledgement was lost, so seq is needed to dedupe")
  void producesDuplicates() {
    run(emitter(1.0), 400, List.of());

    List<Long> sequences =
        sink.messages().stream().map(m -> sequenceOf(m.body())).toList();

    assertThat(sequences).hasSizeGreaterThan(sequences.stream().distinct().toList().size());
  }

  private static long sequenceOf(String body) {
    return com.fleettracking.events.EventJson.mapper().readTree(body).get("seq").longValue();
  }
}
