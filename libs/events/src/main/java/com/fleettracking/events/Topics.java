package com.fleettracking.events;

/**
 * The Kafka topics that carry this platform's events.
 *
 * <p>Constants rather than strings scattered through the code, because a typo in a topic name does
 * not fail — the producer simply writes somewhere nobody is listening, and the symptom is a
 * consumer that never receives anything with no error on either side.
 *
 * <p>This lived in the gateway until S9, when a second service needed to name the same topic. Two
 * copies of a wire contract diverge silently and in exactly the way described above, so it moved
 * here, beside the envelopes that travel on it: a topic name and the shape of what is on it are one
 * contract, and every service already depends on this module for the second half of it.
 *
 * <p>A service's <em>own</em> dead-letter topic stays with that service. Nothing else produces to it
 * or consumes from it, so it is not a shared contract — {@link #DEAD_LETTER} is here only because
 * the ingest gateway's rejections are the platform's record of what it refused.
 *
 * <p>The {@code .v1} suffix is the schema version of what travels on the topic, not the version of
 * any service. Adding a field to an envelope stays on v1, because every consumer ignores unknown
 * properties by construction. Removing a field or changing its type is a v2, produced alongside v1
 * until the last consumer has moved.
 *
 * <p>Every topic here is created explicitly by the {@code kafka-topics} Job in
 * {@code deploy/base/kafka}, with a partition count chosen per topic. A new topic goes in that Job;
 * it is never left to a broker's auto-creation, which would pick one partition and quietly cap how
 * far its consumer can ever scale.
 */
public final class Topics {

  /** Where a shipment is. The high-volume topic — every telematics ping in the fleet. */
  public static final String POSITION = "position.events.v1";

  /** What happened to a shipment: arrivals claimed by a carrier, temperatures, delays. */
  public static final String STATUS = "status.events.v1";

  /** What this platform concluded: arrivals, departures, ETA revisions. */
  public static final String DERIVED = "shipment.derived.v1";

  /** SLA exceptions raised and cleared. */
  public static final String EXCEPTIONS = "exceptions.v1";

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
