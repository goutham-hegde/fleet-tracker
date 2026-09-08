package com.fleettracking.dashboard.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Telling four topics' worth of shapes apart, without a broker.
 *
 * <p>Three of the four listeners have to identify what they are holding from the fields present on
 * it, because the derived topic and the exceptions topic each carry more than one kind of event.
 * That is exactly the sort of code that is easy to get subtly wrong and impossible to notice: a
 * misidentified shape does not throw, it simply means one kind of update silently never reaches a
 * viewer, and the map looks fine because everything else still moves.
 *
 * <p>The payloads here are written out by hand rather than serialized from the event records, and
 * that is the point — a test that serializes with the same mapper it deserializes with proves the
 * mapper is self-consistent and nothing else. These are what is actually on the topics, including
 * the {@code type} discriminator every event carries, which is how S14 discovered that the exception
 * events were putting that key on the wire twice.
 */
class DashboardConsumersTest {

  private StreamBroadcaster broadcaster;
  private DashboardConsumers consumers;
  private CapturingEmitter viewer;

  /** An emitter that keeps what it was asked to send, so a test can read the wire format. */
  private static final class CapturingEmitter extends SseEmitter {
    private final List<String> sent = new CopyOnWriteArrayList<>();

    @Override
    public void send(SseEventBuilder builder) {
      builder.build().forEach(part -> sent.add(String.valueOf(part.getData())));
    }
  }

  @BeforeEach
  void setUp() {
    broadcaster = new StreamBroadcaster(64, 4, Duration.ofHours(1));
    consumers = new DashboardConsumers(broadcaster, Duration.ZERO);
    viewer = new CapturingEmitter();
    broadcaster.subscribe(viewer);
  }

  @AfterEach
  void tearDown() {
    broadcaster.close();
  }

  private static ConsumerRecord<String, String> record(String topic, String value) {
    return new ConsumerRecord<>(topic, 0, 0L, "SHP-1", value);
  }

  private void awaitSent(String fragment) {
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(viewer.sent).anyMatch(sent -> sent.contains(fragment)));
  }

  @Test
  @DisplayName("a raised exception is forwarded, and is not mistaken for a clear")
  void aRaisedExceptionIsForwarded() {
    consumers.onException(
        record(
            "exceptions.v1",
            """
            {"type":"exception.raised","eventId":"evt-x","shipmentId":"SHP-1",\
            "occurredAt":"2026-09-08T09:06:40Z","causedBy":"evt-1","exceptionId":"inc-live",\
            "exceptionType":"TEMPERATURE_EXCURSION","severity":"CRITICAL",\
            "detail":"31.7C is outside the agreed band"}"""));

    assertThat(consumers.unreadableCount()).isZero();
    assertThat(consumers.exceptionCount()).isEqualTo(1);
    awaitSent("inc-live");
    awaitSent("CRITICAL");
  }

  @Test
  @DisplayName("a cleared exception is told apart by openFor, which only a clear carries")
  void aClearedExceptionIsForwarded() {
    consumers.onException(
        record(
            "exceptions.v1",
            """
            {"type":"exception.cleared","eventId":"evt-y","shipmentId":"SHP-1",\
            "occurredAt":"2026-09-08T10:00:00Z","causedBy":"evt-2","exceptionId":"inc-live",\
            "exceptionType":"TEMPERATURE_EXCURSION","raisedAt":"2026-09-08T09:06:40Z",\
            "openFor":"PT51M","resolution":"BACK_IN_BAND"}"""));

    assertThat(consumers.unreadableCount()).isZero();
    // The duration reaches a browser as seconds, not as ISO-8601: a map has no use for PT51M.
    awaitSent("BACK_IN_BAND");
    awaitSent("3060");
  }

  @Test
  @DisplayName("an estimate, a departure and an arrival are three shapes on one topic")
  void theThreeDerivedShapes() {
    consumers.onDerived(
        record(
            "shipment.derived.v1",
            """
            {"type":"eta.updated","eventId":"e1","shipmentId":"SHP-1",\
            "occurredAt":"2026-09-08T09:00:00Z","causedBy":"f1","stopId":"jai-vki",\
            "estimatedArrival":"2026-09-08T12:00:00Z","remainingKm":184.2,"confidence":0.77}"""));
    consumers.onDerived(
        record(
            "shipment.derived.v1",
            """
            {"type":"shipment.departed","eventId":"e2","shipmentId":"SHP-1",\
            "occurredAt":"2026-09-08T09:10:00Z","causedBy":"f2","stopId":"del-okhla",\
            "position":{"latitude":28.5,"longitude":77.2},"dwell":"PT40M"}"""));
    consumers.onDerived(
        record(
            "shipment.derived.v1",
            """
            {"type":"shipment.arrived","eventId":"e3","shipmentId":"SHP-1",\
            "occurredAt":"2026-09-08T09:20:00Z","causedBy":"f3","stopId":"jai-vki",\
            "position":{"latitude":26.9,"longitude":75.8}}"""));

    assertThat(consumers.unreadableCount()).isZero();
    assertThat(consumers.derivedCount()).isEqualTo(3);
    awaitSent("184.2");
    awaitSent("2400");
    awaitSent("26.9");
  }

  @Test
  @DisplayName("a derived shape this build does not recognise is ignored, not counted as an error")
  void anUnrecognisedDerivedShapeIsIgnored() {
    consumers.onDerived(
        record(
            "shipment.derived.v1",
            """
            {"type":"something.new","eventId":"e9","shipmentId":"SHP-1",\
            "occurredAt":"2026-09-08T09:00:00Z","causedBy":"f9",\
            "somethingNobodyHasWrittenYet":true}"""));

    assertThat(consumers.unreadableCount()).isZero();
    assertThat(consumers.derivedCount()).isZero();
  }

  @Test
  @DisplayName("a position is forwarded without the raw source payload it was normalized from")
  void positionsDropTheRawPayload() {
    consumers.onPosition(
        record(
            "position.events.v1",
            """
            {"type":"position","eventId":"p1","shipmentId":"SHP-1","vehicleId":"VEH-1",\
            "occurredAt":"2026-09-08T09:00:00Z","receivedAt":"2026-09-08T09:00:01Z",\
            "position":{"latitude":28.0,"longitude":76.8},"speedKph":62.0,\
            "raw":{"source":"TELEMATICS","contentType":"application/json","body":"{}"}}"""));

    assertThat(consumers.unreadableCount()).isZero();
    assertThat(consumers.positionCount()).isEqualTo(1);
    awaitSent("62.0");
    assertThat(viewer.sent).noneMatch(sent -> sent.contains("\"body\""));
  }

  @Test
  @DisplayName("the per-shipment gate thins a busy truck and never delays a quiet one")
  void thePositionGate() {
    DashboardConsumers gated = new DashboardConsumers(broadcaster, Duration.ofSeconds(30));

    String fix =
        """
        {"type":"position","eventId":"p%d","shipmentId":"%s","vehicleId":"VEH-1",\
        "occurredAt":"2026-09-08T09:00:00Z","receivedAt":"2026-09-08T09:00:01Z",\
        "position":{"latitude":28.0,"longitude":76.8},"speedKph":62.0,\
        "raw":{"source":"TELEMATICS","contentType":"application/json","body":"{}"}}""";

    for (int i = 0; i < 5; i++) {
      gated.onPosition(record("position.events.v1", fix.formatted(i, "SHP-BUSY")));
    }
    // A different truck, reporting for the first time, is not held back by the busy one's gate.
    gated.onPosition(record("position.events.v1", fix.formatted(99, "SHP-QUIET")));

    assertThat(gated.positionCount()).isEqualTo(6);
    assertThat(gated.thinnedCount()).isEqualTo(4);
  }

  @Test
  @DisplayName("an unparseable record is counted and passed over, never dead-lettered")
  void unparseableRecordsAreCountedNotRethrown() {
    consumers.onPosition(record("position.events.v1", "{not json"));

    assertThat(consumers.unreadableCount()).isEqualTo(1);
    assertThat(consumers.positionCount()).isZero();
  }
}
