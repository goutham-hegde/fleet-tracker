package com.fleettracking.tracking.eta;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The current estimate for one shipment, and the model that produced it.
 *
 * <h2>Two jobs in one document, and why they belong together</h2>
 *
 * <p>The first four fields after the key are the <em>answer</em>: what the platform currently
 * believes about when this shipment reaches its next stop. That is what a dashboard reads, and it
 * is the reason this exists as a document rather than only as a stream of events — asking "what is
 * the ETA for these forty shipments" should be forty primary-key lookups, not a scan of a topic.
 *
 * <p>The last four are the <em>model</em>: what the platform has learned about how fast this truck
 * actually travels. They are here so that a restart does not throw that away. A processor that came
 * back with no idea of a truck's pace would fall back to a nominal speed for its first several
 * minutes and publish a burst of corrections as it re-learned something it already knew.
 *
 * <h2>Written on publish, not on every fix</h2>
 *
 * <p>The model is advanced in memory by every position and written here only when an estimate is
 * actually published, which is a handful of times per leg rather than once per fix. This follows
 * the rule the geofence state established: the busiest path in the platform pays for reads and for
 * changes, not for arithmetic. The cost is that a restart resumes from the model as it stood at the
 * last publish rather than at the last fix — minutes of staleness in a quantity that is smoothed
 * over minutes by design.
 *
 * @param shipmentId the primary key. One estimate per shipment, because there is one truck
 * @param stopId the stop the estimate is for — the next one the shipment has not arrived at
 * @param estimatedArrival the last estimate published for that stop. What the next event will carry
 *     as its {@code previousEstimate}, and what the publish threshold is measured against
 * @param remainingKm road distance still to drive when that estimate was made
 * @param confidence how much the platform trusts it, 0 to 1
 * @param expectedSpeedKph the learned travel speed, in km/h: a time-decayed average of the ground
 *     speed reported while the truck was actually moving
 * @param movingSeconds how much event time of movement that average is built from. The measure of
 *     how warmed-up the model is, and an input to confidence
 * @param lastMovingAt the newest fix that showed the vehicle moving. How long it has been stationary
 *     is what turns a confident estimate into a provisional one
 * @param lastFixAt the newest fix applied to the model. Fixes older than this are ignored, for the
 *     same reason the geofencer ignores them: the mobile app dumps buffered backlogs out of order
 * @param updatedAt wall-clock time of this write, distinct from every instant above. A stale
 *     {@code updatedAt} means this service stopped; a stale {@code lastFixAt} beneath a fresh one
 *     means the truck stopped reporting
 */
@Document(collection = EtaState.COLLECTION)
public record EtaState(
    @Id String shipmentId,
    String stopId,
    Instant estimatedArrival,
    Double remainingKm,
    Double confidence,
    double expectedSpeedKph,
    long movingSeconds,
    Instant lastMovingAt,
    Instant lastFixAt,
    Instant updatedAt) {

  public static final String COLLECTION = "shipment.eta";

  /**
   * The state of a shipment nothing has been estimated for yet.
   *
   * <p>The speed is zero rather than a nominal guess, and that is deliberate: zero is how the
   * calculator recognises that it has learned nothing, so that it can say so through the confidence
   * on the first estimate instead of quietly presenting a default as if it were a measurement.
   */
  public static EtaState initial(String shipmentId) {
    return new EtaState(shipmentId, null, null, null, null, 0, 0, null, null, null);
  }

  /** Whether any estimate has been published for this shipment yet. */
  public boolean hasEstimate() {
    return estimatedArrival != null;
  }

  /** Whether the model has seen the vehicle move at all. */
  public boolean hasSpeed() {
    return expectedSpeedKph > 0;
  }
}
