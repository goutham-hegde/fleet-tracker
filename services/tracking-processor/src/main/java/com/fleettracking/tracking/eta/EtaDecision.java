package com.fleettracking.tracking.eta;

import com.fleettracking.events.EtaUpdated;
import java.util.Optional;

/**
 * What one position fix meant for a shipment's estimate.
 *
 * <p>Returned rather than acted on, for the same reason {@code GeofenceDecision} is: the whole of
 * the interesting behaviour — smoothing, the publish threshold, what happens when a truck stops —
 * is then testable without a database or a broker.
 *
 * <p>Note the asymmetry between the two fields. {@code state} always moves forward, because the
 * model learns from every fix; {@code update} is present only on the few fixes whose estimate
 * differs enough from the last published one to be worth telling anybody about. That gap is the
 * entire anti-thrash mechanism, and keeping the two separate is what stops a quiet estimate from
 * also being a forgetful one.
 *
 * @param state the model and estimate as they now stand. Never null
 * @param update the event to publish, if this fix moved the estimate far enough
 */
public record EtaDecision(EtaState state, EtaUpdated update) {

  /** The model advanced, but the published estimate stands. */
  public static EtaDecision quiet(EtaState state) {
    return new EtaDecision(state, null);
  }

  public Optional<EtaUpdated> event() {
    return Optional.ofNullable(update);
  }

  /** Whether this fix produced something to publish — and therefore something to persist. */
  public boolean publishes() {
    return update != null;
  }
}
