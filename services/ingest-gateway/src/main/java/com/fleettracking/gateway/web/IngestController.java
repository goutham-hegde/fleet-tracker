package com.fleettracking.gateway.web;

import com.fleettracking.events.SourceSystem;
import com.fleettracking.gateway.IngestOutcome;
import com.fleettracking.gateway.IngestService;
import com.fleettracking.gateway.normalize.InboundMessage;
import com.fleettracking.gateway.publish.EventPublisher;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The four doors into the platform.
 *
 * <h2>Why the body is a String</h2>
 *
 * <p>Every endpoint takes {@code @RequestBody String} rather than a typed payload. Letting Spring
 * bind the request to a record would be less code and would break the one thing this service exists
 * to do: a malformed message would fail inside request binding and come back to the sender as a
 * framework-generated {@code 400} that this service never saw. The dead-letter topic would then be
 * empty of precisely the messages it exists to hold. Taking the raw bytes means a parse failure is
 * something the gateway decides what to do with.
 *
 * <p>It is also what makes the {@code raw} field on the canonical envelopes honest. Round-tripping
 * a payload through a parser and back to a string normalizes whitespace and key order and drops
 * anything the model does not know about, so it would be a re-rendering rather than the original.
 *
 * <h2>Why a rejected message still gets 202</h2>
 *
 * <p>The instinct is to answer {@code 400 Bad Request} for a payload that could not be parsed, and
 * it produces the wrong behaviour. A well-built producer retries a 4xx-free failure and gives up on
 * a 4xx; a badly built one retries everything. A corrupt message will corrupt identically on every
 * retry, so a 400 either loses it or invites an infinite loop, and in both cases the bytes end up
 * nowhere. Writing it to the dead-letter topic and answering {@code 202 Accepted} says something
 * true — the message is durably stored and the sender need not send it again — while the body
 * states plainly that it was dead-lettered and why.
 *
 * <p>A {@code 503} is reserved for the case where this platform is at fault: the broker did not
 * acknowledge, or the feed has no normalizer yet. Those are the only situations in which resending
 * the same bytes can produce a different result, and therefore the only ones worth a retry.
 */
@RestController
@RequestMapping("/ingest")
public class IngestController {

  private final IngestService ingest;
  private final Clock clock;

  public IngestController(IngestService ingest, Clock clock) {
    this.ingest = ingest;
    this.clock = clock;
  }

  /** In-cab telematics units. Nested imperial JSON, keyed by vehicle, roughly every 30 seconds. */
  @PostMapping(path = "/telematics", consumes = "application/json")
  public ResponseEntity<IngestResponse> telematics(
      @RequestBody String body,
      @RequestHeader(value = "Content-Type", required = false) String contentType) {
    return accept(SourceSystem.TELEMATICS, contentType, body);
  }

  /** Driver phone app. Terse keys, epoch millis, metres per second, and duplicates. */
  @PostMapping(path = "/mobile", consumes = "application/json")
  public ResponseEntity<IngestResponse> mobile(
      @RequestBody String body,
      @RequestHeader(value = "Content-Type", required = false) String contentType) {
    return accept(SourceSystem.MOBILE_APP, contentType, body);
  }

  /** A carrier's EDI 214 interchange: one request, many shipments, no coordinates. */
  @PostMapping(path = "/edi214", consumes = {"application/edi-x12", "text/plain"})
  public ResponseEntity<IngestResponse> edi214(
      @RequestBody String body,
      @RequestHeader(value = "Content-Type", required = false) String contentType) {
    return accept(SourceSystem.EDI_214, contentType, body);
  }

  /** Refrigerated trailer probe. A temperature and a device id, and nothing else. */
  @PostMapping(path = "/reefer", consumes = "application/json")
  public ResponseEntity<IngestResponse> reefer(
      @RequestBody String body,
      @RequestHeader(value = "Content-Type", required = false) String contentType) {
    return accept(SourceSystem.REEFER_SENSOR, contentType, body);
  }

  private ResponseEntity<IngestResponse> accept(
      SourceSystem source, String contentType, String body) {
    if (!ingest.handles(source)) {
      // The message is not wrong -- this gateway is not finished. Dead-lettering good data because
      // a normalizer has not been written yet would fill the rejection topic with messages that
      // have nothing wrong with them and hide the ones that do.
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .body(
              new IngestResponse(
                  IngestResponse.Outcome.DEAD_LETTERED,
                  0,
                  0,
                  "UNSUPPORTED_FEED",
                  "no normalizer for " + source + " yet; retry later"));
    }

    IngestOutcome outcome =
        ingest.accept(new InboundMessage(source, contentType, body, clock.instant()));
    return ResponseEntity.accepted().body(IngestResponse.of(outcome));
  }

  /**
   * The broker did not accept the message, so nothing durable happened and the producer is the only
   * remaining copy. A 503 is the one answer that reliably gets it sent again.
   */
  @ExceptionHandler(EventPublisher.PublishFailedException.class)
  public ResponseEntity<IngestResponse> brokerUnavailable(EventPublisher.PublishFailedException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            new IngestResponse(
                IngestResponse.Outcome.DEAD_LETTERED, 0, 0, "BROKER_UNAVAILABLE", e.getMessage()));
  }
}
