package com.fleettracking.tracking.consume;

/**
 * This service's own topic.
 *
 * <p>The topics it shares with the rest of the platform live in {@code libs/events}, because a
 * topic that two services touch is a contract between them and belongs where both can see one
 * definition of it. This one is not shared: nothing else produces to it, and nothing consumes it
 * except a person investigating. Putting it in the shared module would imply a promise to other
 * services that does not exist.
 */
public final class TrackingTopics {

  /**
   * Position events this processor could not turn into a stored measurement.
   *
   * <p>Three partitions, matching the other low-volume topics. If this one is busy, something in
   * the platform is broken rather than something out on the road — see {@link TrackingDeadLetters}.
   */
  public static final String DEAD_LETTER = "tracking.dlq.v1";

  private TrackingTopics() {}
}
