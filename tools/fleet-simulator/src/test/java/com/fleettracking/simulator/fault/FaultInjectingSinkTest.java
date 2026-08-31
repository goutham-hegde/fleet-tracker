package com.fleettracking.simulator.fault;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.SourceSystem;
import com.fleettracking.simulator.emit.MessageSink;
import com.fleettracking.simulator.emit.SourceMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FaultInjectingSinkTest {

  private static final Instant AT = Instant.parse("2026-08-31T14:12:03Z");

  private final List<SourceMessage> delivered = new ArrayList<>();
  private final MessageSink downstream = delivered::add;

  private static SourceMessage message() {
    return SourceMessage.live(
        SourceSystem.TELEMATICS, "VEH-0001", AT, "{\"gps\":{\"lat\":41.8781,\"speedMph\":62.1}}");
  }

  private FaultInjectingSink sink(FaultProperties properties) {
    return new FaultInjectingSink(downstream, new FaultProfile(properties, new Random(5)));
  }

  @Test
  @DisplayName("passes everything through when no fault is switched on")
  void isTransparentByDefault() {
    FaultInjectingSink sink = sink(new FaultProperties(true, 6.0, 0, 1500, 0, 0, 0));

    for (int i = 0; i < 50; i++) {
      sink.accept(message());
    }

    assertThat(delivered).hasSize(50);
    assertThat(sink.dropped()).isZero();
    assertThat(sink.duplicated()).isZero();
  }

  @Test
  @DisplayName("a dropped message reaches nothing downstream")
  void dropsMessages() {
    FaultInjectingSink sink = sink(new FaultProperties(true, 0, 0, 1500, 1.0, 0, 0));

    sink.accept(message());

    assertThat(delivered).isEmpty();
    assertThat(sink.dropped()).isEqualTo(1);
  }

  @Test
  @DisplayName("a duplicate is byte-for-byte identical, so dedup cannot cheat")
  void duplicatesExactly() {
    FaultInjectingSink sink = sink(new FaultProperties(true, 0, 0, 1500, 0, 1.0, 0));

    sink.accept(message());

    assertThat(delivered).hasSize(2);
    assertThat(delivered.get(0).body()).isEqualTo(delivered.get(1).body());
    assertThat(delivered.get(0).occurredAt()).isEqualTo(delivered.get(1).occurredAt());
    assertThat(sink.duplicated()).isEqualTo(1);
  }

  @Test
  @DisplayName("a corrupted message keeps its provenance, so the DLQ can say where it came from")
  void malformsWithoutLosingProvenance() {
    FaultInjectingSink sink = sink(new FaultProperties(true, 0, 0, 1500, 0, 0, 1.0));

    sink.accept(message());

    assertThat(delivered).hasSize(1);
    SourceMessage corrupted = delivered.getFirst();
    assertThat(corrupted.body()).isNotEqualTo(message().body());
    assertThat(corrupted.source()).isEqualTo(SourceSystem.TELEMATICS);
    assertThat(corrupted.routingKey()).isEqualTo("VEH-0001");
    assertThat(corrupted.occurredAt()).isEqualTo(AT);
    assertThat(sink.malformed()).isEqualTo(1);
  }

  @Test
  @DisplayName("a dropped message is never also duplicated")
  void dropWinsOverDuplicate() {
    FaultInjectingSink sink = sink(new FaultProperties(true, 0, 0, 1500, 1.0, 1.0, 1.0));

    for (int i = 0; i < 20; i++) {
      sink.accept(message());
    }

    assertThat(delivered).isEmpty();
    assertThat(sink.dropped()).isEqualTo(20);
  }

  @Test
  @DisplayName("closes the sink it wraps")
  void closesDownstream() {
    List<String> closed = new ArrayList<>();
    MessageSink recording =
        new MessageSink() {
          @Override
          public void accept(SourceMessage message) {}

          @Override
          public void close() {
            closed.add("closed");
          }
        };

    new FaultInjectingSink(recording, FaultProfile.none()).close();

    assertThat(closed).containsExactly("closed");
  }
}
