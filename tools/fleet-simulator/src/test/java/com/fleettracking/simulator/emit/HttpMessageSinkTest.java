package com.fleettracking.simulator.emit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.SourceSystem;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The HTTP sink against a real server on a real socket.
 *
 * <p>A JDK {@link HttpServer} rather than a mocked client: the things worth checking here are that
 * each feed lands on its own path, that the content type survives (EDI is not JSON and a gateway
 * endpoint that only consumes JSON would reject it), and that a full queue drops instead of
 * blocking. None of those are visible if the client is mocked out.
 */
class HttpMessageSinkTest {

  record Received(String path, String contentType, String body) {}

  private HttpServer server;
  private ExecutorService handlers;
  private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
  private volatile CountDownLatch hold;
  private volatile int responseStatus = 202;
  private volatile String responseBody;
  private volatile long handlerDelayMillis;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger mostInFlight = new AtomicInteger();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ingest", this::handle);
    // A pool, so the server can answer as many requests at once as the sink sends. The default
    // handles one at a time, which would serialize several workers and hide whether they overlap.
    handlers = Executors.newFixedThreadPool(8);
    server.setExecutor(handlers);
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
    handlers.shutdownNow();
  }

  private void handle(HttpExchange exchange) throws IOException {
    mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
    try {
      if (hold != null) {
        hold.await(5, TimeUnit.SECONDS);
      }
      if (handlerDelayMillis > 0) {
        Thread.sleep(handlerDelayMillis);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try (InputStream in = exchange.getRequestBody()) {
      received.add(
          new Received(
              exchange.getRequestURI().getPath(),
              exchange.getRequestHeaders().getFirst("Content-Type"),
              new String(in.readAllBytes(), StandardCharsets.UTF_8)));
    }
    inFlight.decrementAndGet();
    byte[] body = responseBody == null ? null : responseBody.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(responseStatus, body == null ? -1 : body.length);
    if (body != null) {
      exchange.getResponseBody().write(body);
    }
    exchange.close();
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  private HttpMessageSink sink(int capacity) {
    return new HttpMessageSink(baseUrl(), Duration.ofSeconds(5), capacity);
  }

  private static SourceMessage message(SourceSystem source, String body) {
    return SourceMessage.live(source, "KEY", Instant.parse("2026-08-31T10:00:00Z"), body);
  }

  @Test
  void sendsEachFeedToItsOwnEndpointWithItsOwnContentType() {
    try (HttpMessageSink sink = sink(100)) {
      sink.accept(message(SourceSystem.TELEMATICS, "{\"a\":1}"));
      sink.accept(message(SourceSystem.MOBILE_APP, "{\"b\":2}"));
      sink.accept(message(SourceSystem.EDI_214, "ISA*00*~"));
      sink.accept(message(SourceSystem.REEFER_SENSOR, "{\"c\":3}"));
    } // close() drains the queue before returning

    List<Received> all = List.copyOf(received);
    assertThat(all).hasSize(4);
    assertThat(all).extracting(Received::path)
        .containsExactly("/ingest/telematics", "/ingest/mobile", "/ingest/edi214", "/ingest/reefer");
    // EDI is not JSON, and a gateway endpoint declaring consumes=application/json would answer 415.
    assertThat(all).extracting(Received::contentType)
        .containsExactly("application/json", "application/json", "application/edi-x12", "application/json");
    assertThat(all.get(2).body()).isEqualTo("ISA*00*~");
  }

  @Test
  void countsWhatTheGatewayAccepted() {
    try (HttpMessageSink sink = sink(100)) {
      sink.accept(message(SourceSystem.TELEMATICS, "{\"a\":1}"));
      sink.accept(message(SourceSystem.TELEMATICS, "{\"a\":2}"));
      sink.close();

      assertThat(sink.sent()).isEqualTo(2);
      assertThat(sink.refused()).isZero();
    }
  }

  @Test
  void countsAnythingOtherThanTwoOhTwoAsRefused() {
    // What the gateway answers for a feed whose normalizer does not exist yet.
    responseStatus = 503;

    try (HttpMessageSink sink = sink(100)) {
      sink.accept(message(SourceSystem.MOBILE_APP, "{\"b\":2}"));
      sink.close();

      assertThat(sink.sent()).isZero();
      assertThat(sink.refused()).isEqualTo(1);
    }
  }

  @Test
  void dropsRatherThanBlockingWhenTheGatewayCannotKeepUp() {
    // The property that matters most: the tick thread moves every truck, so a sink that blocked
    // would slow the fleet to whatever the gateway could absorb while its timestamps claimed
    // otherwise. A real device with a full buffer drops too.
    hold = new CountDownLatch(1);

    try (HttpMessageSink sink = sink(1)) {
      for (int i = 0; i < 20; i++) {
        sink.accept(message(SourceSystem.TELEMATICS, "{\"n\":" + i + "}"));
      }

      // At most one message is in the handler and one in the queue; the rest had nowhere to go.
      assertThat(sink.dropped()).isGreaterThanOrEqualTo(15);
      hold.countDown();
    }
  }

  @Test
  void severalWorkersOverlapRequestsWhileEachDeviceStaysInOrder() {
    // Slow enough that one worker could never have two requests in flight at once.
    handlerDelayMillis = 20;
    List<String> devices = List.of("K1", "K2", "K3", "K4");

    try (HttpMessageSink sink =
        new HttpMessageSink(baseUrl(), Duration.ofSeconds(5), 1000, 4, null)) {
      for (int seq = 0; seq < 10; seq++) {
        for (String device : devices) {
          sink.accept(
              SourceMessage.live(
                  SourceSystem.TELEMATICS,
                  device,
                  Instant.parse("2026-08-31T10:00:00Z"),
                  device + "#" + seq));
        }
      }
      sink.close();

      assertThat(sink.sent()).isEqualTo(40);
      // Every request took at least the handler's delay, and the percentile says so.
      assertThat(sink.percentile(0.5)).isGreaterThanOrEqualTo(Duration.ofMillis(20));
    }

    // More than one request at a time reached the server: the workers really do run in parallel.
    assertThat(mostInFlight.get()).isGreaterThan(1);
    // And yet each device's messages arrived in exactly the order it emitted them.
    for (String device : devices) {
      assertThat(received)
          .extracting(Received::body)
          .filteredOn(body -> body.startsWith(device + "#"))
          .containsExactly(
              java.util.stream.IntStream.range(0, 10)
                  .mapToObj(seq -> device + "#" + seq)
                  .toArray(String[]::new));
    }
  }

  @Test
  void countsAnAcceptanceByDeadLetteringApartFromAPublishedOne() {
    // The gateway's answer for a message it could not use: still 202, because the original is
    // durably on the dead-letter topic, but nothing reached the canonical topic.
    responseBody =
        "{\"outcome\":\"DEAD_LETTERED\",\"published\":0,\"deadLettered\":1,"
            + "\"reason\":\"UNRESOLVED_IDENTITY\"}";

    try (HttpMessageSink sink = sink(100)) {
      sink.accept(message(SourceSystem.TELEMATICS, "{\"a\":1}"));
      sink.close();

      assertThat(sink.sent()).isEqualTo(1);
      assertThat(sink.deadLettered()).isEqualTo(1);
    }
  }
}
