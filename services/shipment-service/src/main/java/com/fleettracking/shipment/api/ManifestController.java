package com.fleettracking.shipment.api;

import com.fleettracking.shipment.manifest.FreightMode;
import com.fleettracking.shipment.manifest.Manifest;
import com.fleettracking.shipment.manifest.ManifestService;
import com.fleettracking.shipment.manifest.ManifestStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * How a manifest gets in, and how one comes back out.
 *
 * <h2>Why this binds a typed record, when the gateway deliberately does not</h2>
 *
 * <p>The ingest gateway binds request bodies as {@code String} because a message that fails to parse
 * must still reach the dead-letter topic — if the framework rejects it during binding, the one
 * message the DLQ exists to hold never reaches the service.
 *
 * <p>That reasoning does not transfer here, and copying it would be cargo cult. There is no
 * dead-letter topic for manifests, because there is a caller on the other end of the connection:
 * a customer's order system, synchronously, that can be told what was wrong and send a corrected
 * document. A carrier's EDI batch is fire-and-forget and irreplaceable; a manifest submission is a
 * conversation. Malformed JSON here is genuinely a {@code 400} the caller should fix.
 *
 * <h2>Response codes</h2>
 *
 * <table>
 *   <tr><td>{@code 201}</td><td>Validated against the customer's schema and stored</td></tr>
 *   <tr><td>{@code 400}</td><td>The request was not valid JSON, or the envelope was incomplete</td></tr>
 *   <tr><td>{@code 422}</td><td>Well-formed, but the body breaks the customer's schema — the
 *       response carries the violations</td></tr>
 *   <tr><td>{@code 503}</td><td>No schema on file for this customer and mode</td></tr>
 * </table>
 *
 * <p>{@code 422} rather than {@code 400} for a schema violation because the two are different
 * failures and a caller automates against them differently: {@code 400} means the request could not
 * be understood, {@code 422} means it was understood perfectly and its contents were unacceptable.
 * Collapsing them would leave an order system unable to tell "our JSON serializer is broken" from
 * "we sent a temperature above the permitted maximum".
 *
 * <p>{@code 503} for a missing schema follows the gateway's rule exactly: it is reserved for the
 * cases where sending the identical request later can produce a different result. Nothing is wrong
 * with the customer's manifest — this platform has not been given their contract yet, and once it
 * is, the same bytes will succeed. Answering {@code 422} would blame the caller for our gap and
 * invite them to "fix" a document that was already correct.
 */
@RestController
@RequestMapping("/manifests")
public class ManifestController {

  private final ManifestService service;
  private final ManifestStore store;

  public ManifestController(ManifestService service, ManifestStore store) {
    this.service = service;
    this.store = store;
  }

  /**
   * The envelope a submission must carry.
   *
   * <p>Constrained here only as far as the envelope goes. The body is deliberately an open map —
   * validating its contents is the schema's job, and duplicating any of it as bean-validation
   * annotations would be a second contract to keep in step with the first.
   */
  public record Submission(
      @NotBlank String shipmentId,
      @NotBlank String customerId,
      @NotNull FreightMode mode,
      @NotNull Map<String, Object> body) {}

  /** What a caller gets back when their manifest was refused. */
  public record Rejection(String reason, String schemaVersion, List<?> violations) {}

  @PostMapping
  public ResponseEntity<?> submit(@Valid @RequestBody Submission submission) {
    var result =
        service.accept(
            submission.shipmentId(),
            submission.customerId(),
            submission.mode(),
            submission.body());

    return switch (result) {
      case ManifestService.SubmissionResult.Accepted accepted ->
          ResponseEntity.status(HttpStatus.CREATED).body(accepted.manifest());

      case ManifestService.SubmissionResult.Rejected rejected ->
          ResponseEntity.unprocessableEntity()
              .body(
                  new Rejection(
                      "The manifest does not satisfy the schema on file for this customer",
                      rejected.schemaVersion(),
                      rejected.violations()));

      case ManifestService.SubmissionResult.SchemaMissing missing ->
          ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
              .body(
                  new Rejection(
                      "No manifest schema is on file for customer "
                          + missing.customerId()
                          + " in mode "
                          + missing.mode()
                          + ". This is a platform configuration gap, not a fault in the submitted"
                          + " manifest; the same request will succeed once the schema is loaded.",
                      null,
                      List.of()));
    };
  }

  /** The manifest for one shipment — the read the dashboard makes when a marker is clicked. */
  @GetMapping("/{shipmentId}")
  public ResponseEntity<Manifest> byShipment(@PathVariable String shipmentId) {
    return store.findByShipment(shipmentId).map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * Manifests by customer or by freight mode, from the one collection that holds all four shapes.
   *
   * <p>Exactly one of the two parameters is expected. This is the narrow read S12 needs to
   * demonstrate that the shapes coexist and remain queryable; the full query API arrives in S14.
   */
  @GetMapping
  public ResponseEntity<List<Manifest>> search(
      @RequestParam(required = false) String customerId,
      @RequestParam(required = false) FreightMode mode) {

    if (customerId != null) {
      return ResponseEntity.ok(store.findByCustomer(customerId));
    }
    if (mode != null) {
      return ResponseEntity.ok(store.findByMode(mode));
    }
    return ResponseEntity.badRequest().build();
  }
}
