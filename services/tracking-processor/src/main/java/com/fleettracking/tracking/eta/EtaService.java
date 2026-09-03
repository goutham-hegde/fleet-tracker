package com.fleettracking.tracking.eta;

import com.fleettracking.events.PositionEvent;
import com.fleettracking.tracking.geofence.DerivedEventPublisher;
import com.fleettracking.tracking.geofence.ShipmentProgress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns one stored position into a revised estimate, and says so if it is news.
 *
 * <h2>The same order as an arrival, for the same reason</h2>
 *
 * <p>Publish the event, then record it. There is no transaction spanning Kafka and MongoDB, so a
 * crash in between has to land somewhere, and the two choices are not symmetric: recording first
 * can leave an estimate that was never announced but which the publish threshold will now measure
 * everything against, so the correction is suppressed and the topic carries an ETA the platform
 * itself no longer believes. Publishing first can only repeat an estimate — and the repeat is
 * byte-identical, because the id is derived from the position that caused it.
 *
 * <h2>The model advances whether or not anything is published</h2>
 *
 * <p>This is the part that is easy to get wrong. The decision returns a new state on almost every
 * fix, and only some of those states are worth a database write; but all of them are worth keeping,
 * because they carry what the truck's speed has just taught. Dropping the ones that produced no
 * event would mean the model only ever learned from fixes that changed the estimate, which is
 * precisely backwards — a steady truck teaches the most and says the least.
 */
public class EtaService {

  private final EtaStateStore states;
  private final EtaCalculator calculator;
  private final DerivedEventPublisher publisher;

  private final AtomicLong published = new AtomicLong();
  private final AtomicLong evaluated = new AtomicLong();

  public EtaService(
      EtaStateStore states, EtaCalculator calculator, DerivedEventPublisher publisher) {
    this.states = states;
    this.calculator = calculator;
    this.publisher = publisher;
  }

  /**
   * Evaluates a position against the shipment's remaining plan.
   *
   * <p>Called after the position has been stored and after geofencing has run, because the estimate
   * depends on both: on the measurement being durable, and on knowing which stop is next as of this
   * fix rather than as of the one before it. If this throws, the whole record is retried, which is
   * safe for the same reason the arrival path is — the ids are derived, so a repeat is a repeat
   * rather than a second event.
   */
  public void apply(PositionEvent event, ShipmentProgress progress) {
    EtaState current = states.forShipment(event.shipmentId());
    EtaDecision decision = calculator.evaluate(current, progress, event);
    evaluated.incrementAndGet();

    if (!decision.publishes()) {
      // Learned something, said nothing. The overwhelmingly common case on a steady leg.
      states.remember(decision.state());
      return;
    }

    publisher.publish(decision.update());
    published.incrementAndGet();
    states.save(decision.state());
  }

  /** Estimates announced by this process since it started. */
  public long publishedCount() {
    return published.get();
  }

  /** Positions this process has estimated against. The denominator for the publish rate. */
  public long evaluatedCount() {
    return evaluated.get();
  }

  /**
   * A one-line summary, logged periodically by the heartbeat.
   *
   * <p>The ratio is the number to watch. An ETA event for a large share of positions means the
   * estimate is thrashing and the threshold or the smoothing is wrong; near zero over a run in
   * which trucks are visibly moving means it has gone silent, which looks identical to working.
   */
  public String summary() {
    long seen = evaluated.get();
    long sent = published.get();
    String share = seen == 0 ? "-" : Math.round(100.0 * sent / seen) + "%";
    return "etas=" + sent + "/" + seen + " (" + share + ")";
  }
}
