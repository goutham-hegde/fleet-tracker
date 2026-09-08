package com.fleettracking.dashboard.api;

import com.fleettracking.dashboard.read.FleetService;
import com.fleettracking.dashboard.read.ShipmentDetail;
import com.fleettracking.dashboard.read.ShipmentSummary;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The snapshot half of the dashboard's API: what is true right now.
 *
 * <h2>Snapshot then stream</h2>
 *
 * <p>A browser that has just connected knows nothing, and the live stream begins at the moment it
 * subscribes — so a dashboard built on the stream alone would open to an empty map and fill in over
 * the following minutes as each truck happened to report. Every endpoint here answers "what is the
 * state of the world"; {@code /api/stream} answers "what has changed since you asked". Load one,
 * follow the other. It is the same split the platform makes internally between a stored current
 * position and the topic it was derived from.
 *
 * <h2>Read-only, entirely</h2>
 *
 * <p>There is no verb here but {@code GET}, and that is a property of the service rather than a
 * feature not yet written. Every write in this platform goes through the component that owns the
 * data — a manifest through the shipment service, a position through the gateway — and a dashboard
 * that could write would be a second writer of documents whose single-writer property is what makes
 * them safe to denormalise in the first place.
 *
 * <h2>Response codes</h2>
 *
 * <table>
 *   <tr><td>{@code 200}</td><td>The snapshot, however empty it may be</td></tr>
 *   <tr><td>{@code 404}</td><td>Nothing has ever reported a position for this shipment</td></tr>
 * </table>
 *
 * <p>Notably no {@code 503}. The gateway and the shipment service both answer {@code 503} when the
 * platform lacks something the caller's request needs — a normalizer, a schema — because sending
 * the identical request later can succeed. An empty fleet is not that: the answer "no trucks are
 * reporting" is a complete and correct answer to the question asked, and dressing it as an outage
 * would make a quiet Sunday look like a broken service.
 */
@RestController
@RequestMapping("/api")
public class FleetController {

  private final FleetService fleet;

  public FleetController(FleetService fleet) {
    this.fleet = fleet;
  }

  /** Every shipment with a known position — one marker each. */
  @GetMapping("/shipments")
  public List<ShipmentSummary> shipments() {
    return fleet.fleet();
  }

  /** One shipment in full: plan, paperwork, incidents and recent trail. */
  @GetMapping("/shipments/{shipmentId}")
  public ResponseEntity<ShipmentDetail> shipment(@PathVariable String shipmentId) {
    return fleet.detail(shipmentId).map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  /**
   * The exceptions panel.
   *
   * @param open whether to show only what is currently wrong. Defaults to true, because the panel's
   *     job is to be looked at when something needs doing; the full history is one parameter away
   */
  @GetMapping("/exceptions")
  public List<ShipmentSummary.IncidentSummary> exceptions(
      @RequestParam(defaultValue = "true") boolean open,
      @RequestParam(defaultValue = "200") int limit) {
    return fleet.exceptions(open, Math.min(Math.max(limit, 1), 1000));
  }

  /** What this service can see. The first thing to check when a map opens empty. */
  @GetMapping("/meta")
  public Map<String, Object> meta() {
    return fleet.meta();
  }
}
