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
import java.util.concurrent.TimeUnit;
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
  private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
  private volatile CountDownLatch hold;
  private volatile int responseStatus = 202;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ingest", this::handle);
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange) throws IOException {
    if (hold != null) {
      try {
        hold.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    try (InputStream in = exchange.getRequestBody()) {
      received.add(
          new Received(
              exchange.getRequestURI().getPath(),
              exchange.getRequestHeaders().getFirst("Content-Type"),
              new String(in.readAllBytes(), StandardCharsets.UTF_8)));
    }
    exchange.sendResponseHeaders(responseStatus, -1);
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
}
