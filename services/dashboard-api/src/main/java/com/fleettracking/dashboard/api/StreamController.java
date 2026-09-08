package com.fleettracking.dashboard.api;

import com.fleettracking.dashboard.stream.StreamBroadcaster;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live half: one long-lived connection per viewer, carrying what has changed.
 *
 * <h2>Why server-sent events rather than WebSockets</h2>
 *
 * <p>The traffic here is entirely one-directional. A viewer subscribes and then receives; there is
 * no message a browser needs to send back, because everything a person can do to this platform goes
 * through a different service's write path. WebSockets solve the bidirectional case and charge for
 * it — a second protocol, an upgrade handshake, framing, its own reconnection logic, and a class of
 * proxy that mishandles it.
 *
 * <p>Server-sent events are plain HTTP: a {@code GET} that never finishes, sending
 * {@code event:}/{@code data:} lines. That means it works through anything that understands HTTP,
 * it can be read with {@code curl} — which is literally one of M5's exit criteria — and the browser
 * reconnects on its own when a connection drops, with no client code at all. The one real limit,
 * six connections per origin in HTTP/1.1, applies to a dashboard opening one.
 *
 * <h2>What a client is expected to do</h2>
 *
 * <p>Load {@code /api/shipments} for the state of the world, then open this and apply what arrives.
 * The stream deliberately does not replay history — it begins at the moment of subscription, which
 * is why the snapshot exists. A reconnecting client re-fetches the snapshot rather than asking for
 * what it missed.
 *
 * <h2>Response codes</h2>
 *
 * <table>
 *   <tr><td>{@code 200}</td><td>The stream, open until the client goes away</td></tr>
 *   <tr><td>{@code 503}</td><td>This instance is already serving as many viewers as it will</td></tr>
 * </table>
 *
 * <p>{@code 503} follows the platform's rule exactly: it is for the cases where the identical
 * request can succeed later — another viewer disconnecting, or another instance taking the
 * connection. Accepting the connection and serving it badly would be the alternative, and a map that
 * is quietly jerky is worse than one that says it cannot be shown.
 */
@RestController
@RequestMapping("/api")
public class StreamController {

  private final StreamBroadcaster broadcaster;

  public StreamController(StreamBroadcaster broadcaster) {
    this.broadcaster = broadcaster;
  }

  /** Subscribe to everything happening on the platform, for as long as the connection is held. */
  @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> stream() {
    SseEmitter emitter = broadcaster.subscribe();
    if (emitter == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_EVENT_STREAM)
        // Nothing between here and the browser may buffer this response: a proxy accumulating a
        // few kilobytes before forwarding would turn a live map into a slideshow, and the symptom
        // would appear only once there was a proxy in the way. nginx honours the second header.
        .header("Cache-Control", "no-cache, no-transform")
        .header("X-Accel-Buffering", "no")
        .body(emitter);
  }
}
