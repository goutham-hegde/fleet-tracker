package com.fleettracking.gateway;

import com.fleettracking.events.SourceEvent;
import com.fleettracking.gateway.publish.DeadLetter;
import com.fleettracking.gateway.publish.EventPublisher;
import java.util.ArrayList;
import java.util.List;

/**
 * An {@link EventPublisher} that keeps what it was given instead of sending it.
 *
 * <p>Follows the same pattern as the simulator's recording sink: a small real implementation rather
 * than a mock with verification chains, so a test reads as a statement about what came out rather
 * than about which methods were called.
 *
 * <p>The superclass is constructed with a null template, which is never dereferenced because both
 * publishing methods are overridden.
 */
public class RecordingPublisher extends EventPublisher {

  private final List<SourceEvent> published = new ArrayList<>();
  private final List<DeadLetter> deadLettered = new ArrayList<>();

  public RecordingPublisher() {
    super(null, 0L);
  }

  @Override
  public String publish(SourceEvent event) {
    published.add(event);
    return "recorded";
  }

  @Override
  public void publishDeadLetter(DeadLetter deadLetter) {
    deadLettered.add(deadLetter);
  }

  public List<SourceEvent> published() {
    return published;
  }

  public List<DeadLetter> deadLettered() {
    return deadLettered;
  }
}
