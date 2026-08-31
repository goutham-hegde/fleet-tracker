package com.fleettracking.gateway.publish;

/**
 * The topics this service writes to.
 *
 * <p>Constants rather than strings scattered through the code, because a typo in a topic name does
 * not fail — the producer simply writes somewhere nobody is listening, and the symptom is a
 * consumer that never receives anything with no error on either side.
 *
 * <p>The {@code .v1} suffix is the schema version of what travels on the topic, not the version of
 * any service. Adding a field to an envelope stays on v1, because every consumer ignores unknown
 * properties by construction. Removing a field or changing its type is a v2, produced alongside v1
 * until the last consumer has moved.
 */
public final class Topics {

  /** Where a shipment is. The high-volume topic — every telematics ping in the fleet. */
  public static final String POSITION = "position.events.v1";

  /** What happened to a shipment: arrivals claimed by a carrier, temperatures, delays. */
  public static final String STATUS = "status.events.v1";

  /**
   * Everything the gateway could not turn into a canonical event.
   *
   * <p>One topic for all four feeds rather than one per feed. The reason to split a dead-letter
   * topic is that different consumers replay from it independently, and nothing does: replay here
   * means fixing a normalizer and running the whole backlog through the same gateway. Each message
   * records which feed it came from, so filtering by source is a header check rather than a
   * different subscription.
   */
  public static final String DEAD_LETTER = "ingest.dlq.v1";

  private Topics() {}
}
