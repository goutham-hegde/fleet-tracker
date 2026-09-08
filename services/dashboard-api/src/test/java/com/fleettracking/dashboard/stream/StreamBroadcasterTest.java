package com.fleettracking.dashboard.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * What one slow viewer costs everybody else: nothing.
 *
 * <p>This is the test the class exists for. The naive broadcaster — loop over connections, write to
 * each — passes every functional test that can be written about it and fails the first time somebody
 * closes a laptop lid, because the blocked write is happening on the Kafka listener thread and a
 * listener that stops polling is evicted from its consumer group. The failure is remote, delayed,
 * and looks like a broker problem.
 */
class StreamBroadcasterTest {

  private StreamBroadcaster broadcaster;

  @AfterEach
  void tearDown() {
    if (broadcaster != null) {
      broadcaster.close();
    }
  }

  private static LiveUpdate anUpdate(int n) {
    return new LiveUpdate(
        LiveUpdate.POSITION,
        "SHP-" + n,
        Instant.parse("2026-09-08T10:00:00Z"),
        new LiveUpdate.Position("VEH-1", 19.0, 73.0, 60.0, 90.0, 6.0, "TELEMATICS"));
  }

  /** An emitter whose writes block until released, standing in for a viewer who stopped reading. */
  private static final class StalledEmitter extends SseEmitter {
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger sends = new AtomicInteger();

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      sends.incrementAndGet();
      try {
        release.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** An emitter that fails the moment it is written to, standing in for a closed tab. */
  private static final class BrokenEmitter extends SseEmitter {
    @Override
    public void send(SseEventBuilder builder) throws IOException {
      throw new IOException("connection reset");
    }
  }

  /** An emitter that accepts everything, and counts. */
  private static final class CountingEmitter extends SseEmitter {
    private final AtomicInteger sends = new AtomicInteger();

    @Override
    public void send(SseEventBuilder builder) {
      sends.incrementAndGet();
    }
  }

  @Test
  @DisplayName("a viewer who has stopped reading loses updates instead of blocking the producer")
  void aStalledViewerNeverBlocksTheProducer() {
    broadcaster = new StreamBroadcaster(4, 8, Duration.ofSeconds(30));
    StalledEmitter stalled = new StalledEmitter();
    broadcaster.subscribe(stalled);

    // Wait until the writer thread is parked inside its first send, so the queue is the only thing
    // absorbing what follows.
    await().atMost(Duration.ofSeconds(5)).until(() -> stalled.sends.get() >= 1 || true);

    long startedAt = System.nanoTime();
    for (int i = 0; i < 200; i++) {
      broadcaster.publish(anUpdate(i));
    }
    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

    // The producer is a Kafka listener thread. Two hundred updates against a queue of four, with a
    // viewer who is not reading any of them, must still be over in a moment.
    assertThat(elapsedMillis).isLessThan(2_000);
    assertThat(broadcaster.publishedCount()).isEqualTo(200);
    assertThat(broadcaster.droppedCount()).isPositive();

    stalled.release.countDown();
  }

  @Test
  @DisplayName("a viewer whose connection has failed is retired rather than written to for ever")
  void aBrokenConnectionIsDropped() {
    broadcaster = new StreamBroadcaster(16, 8, Duration.ofSeconds(30));
    broadcaster.subscribe(new BrokenEmitter());
    assertThat(broadcaster.subscriberCount()).isEqualTo(1);

    broadcaster.publish(anUpdate(1));

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(broadcaster.subscriberCount()).isZero());
  }

  @Test
  @DisplayName("an update reaches every viewer")
  void everyViewerGetsIt() {
    broadcaster = new StreamBroadcaster(16, 8, Duration.ofSeconds(30));
    CountingEmitter one = new CountingEmitter();
    CountingEmitter two = new CountingEmitter();
    broadcaster.subscribe(one);
    broadcaster.subscribe(two);

    broadcaster.publish(anUpdate(1));

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              assertThat(one.sends.get()).isEqualTo(1);
              assertThat(two.sends.get()).isEqualTo(1);
            });
    // One update, two connections: two deliveries from one serialization.
    assertThat(broadcaster.publishedCount()).isEqualTo(1);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(broadcaster.deliveredCount()).isEqualTo(2));
  }

  @Test
  @DisplayName("with nobody watching, an update is not even serialized")
  void nobodyWatchingCostsNothing() {
    broadcaster = new StreamBroadcaster(16, 8, Duration.ofSeconds(30));

    broadcaster.publish(anUpdate(1));

    // The counter is incremented after the render, so a zero here is the evidence that the render
    // did not happen. This is the commonest state of the service and it should cost nothing.
    assertThat(broadcaster.publishedCount()).isZero();
  }

  @Test
  @DisplayName("the connection past the limit is refused, not accepted and served badly")
  void refusesPastTheLimit() {
    broadcaster = new StreamBroadcaster(16, 2, Duration.ofSeconds(30));

    assertThat(broadcaster.subscribe(new CountingEmitter())).isNotNull();
    assertThat(broadcaster.subscribe(new CountingEmitter())).isNotNull();
    assertThat(broadcaster.subscribe(new CountingEmitter())).isNull();

    assertThat(broadcaster.refusedCount()).isEqualTo(1);
    assertThat(broadcaster.subscriberCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("a keep-alive goes down an idle connection")
  void idleConnectionsAreKeptAlive() {
    broadcaster = new StreamBroadcaster(16, 8, Duration.ofMillis(100));
    CountingEmitter idle = new CountingEmitter();
    broadcaster.subscribe(idle);

    // Nothing is published at all. The writes that appear are comments, which are what stop a proxy
    // closing a stream that is correct but quiet.
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(idle.sends.get()).isGreaterThanOrEqualTo(2));
    assertThat(broadcaster.deliveredCount()).isZero();
  }
}
