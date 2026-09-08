package com.fleettracking.dashboard.stream;

import com.fleettracking.events.EtaUpdated;
import com.fleettracking.events.EventJson;
import com.fleettracking.events.ExceptionCleared;
import com.fleettracking.events.ExceptionRaised;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.ShipmentArrived;
import com.fleettracking.events.ShipmentDeparted;
import com.fleettracking.events.StatusEvent;
import com.fleettracking.events.Topics;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The four subscriptions that make the map move.
 *
 * <h2>A consumer group per instance, which is the opposite of every other consumer here</h2>
 *
 * <p>Kafka divides a topic's partitions among the members of a group, so that each record is handled
 * by exactly one of them. That is what the tracking processor and the exception service want: work
 * shared, each event stored or judged once, and adding an instance adds throughput.
 *
 * <p>This service wants the reverse. Each instance holds its own set of open browser connections and
 * can only forward what it has itself received, so an instance that was handed half the partitions
 * would show half the fleet moving and the other half frozen — and which half would change on every
 * rebalance. So the group id carries {@code ${random.uuid}} and every instance gets a complete copy
 * of the stream. It is the one place in this platform where a shared group would be wrong, and it is
 * worth stating loudly because the surrounding services all establish the opposite habit.
 *
 * <p>{@code idIsGroup = false} is on every listener for the usual reason: Spring Kafka otherwise
 * uses a listener's {@code id} as the group id, silently overriding the configured one. Here that
 * would be worse than usual — the four listeners would land in four groups all named after
 * themselves, shared across every instance, and the fan-out this service depends on would be gone
 * with nothing reporting it.
 *
 * <h2>Nothing here is dead-lettered</h2>
 *
 * <p>The other three consumers set aside a record they cannot use, because a position that was never
 * stored or an SLA breach that was never judged is a real loss that somebody has to be able to find.
 * A record this service cannot parse costs a viewer one frame of one marker. Producing to a
 * dead-letter topic to record that would make a read-only service a writer, for no gain — and the
 * same record is already being dead-lettered by whichever consumer actually needed it.
 *
 * <h2>Positions are thinned per shipment</h2>
 *
 * <p>Telematics reports every ten seconds of simulated time, so a demonstration run at a time scale
 * of three hundred produces thirty fixes per second per truck. Sixty-four trucks would be nearly two
 * thousand updates a second into every open browser, which no map can draw and no eye can follow —
 * and the browser is the thing that would fall over, not this service.
 *
 * <p>The gate is per shipment rather than global, so a quiet truck is never delayed by a busy one,
 * and it is measured in wall-clock time rather than event time. That is deliberate: the question the
 * gate answers is "how often can a screen usefully redraw", which is about the viewer's second, not
 * the simulation's. A gate in event time would pass everything at a low time scale and almost
 * nothing at a high one, which is precisely backwards.
 */
public class DashboardConsumers {

  private static final Logger log = LoggerFactory.getLogger(DashboardConsumers.class);

  /**
   * When the per-shipment gate is cleared out.
   *
   * <p>The map holds one timestamp per shipment seen. On a fleet of sixty-four that is nothing, but
   * a process running for months against a real carrier would accumulate an entry per load ever
   * carried. Clearing the whole map when it grows past this is crude and exactly right: the only
   * cost of forgetting is that every shipment's next update passes the gate immediately, which is
   * one extra frame each.
   */
  private static final int GATE_CAPACITY = 20_000;

  private final StreamBroadcaster broadcaster;
  private final long sampleIntervalMillis;

  private final Map<String, Long> lastForwarded = new ConcurrentHashMap<>();

  private final AtomicLong positions = new AtomicLong();
  private final AtomicLong thinned = new AtomicLong();
  private final AtomicLong statuses = new AtomicLong();
  private final AtomicLong derived = new AtomicLong();
  private final AtomicLong exceptions = new AtomicLong();
  private final AtomicLong unreadable = new AtomicLong();

  public DashboardConsumers(StreamBroadcaster broadcaster, Duration sampleInterval) {
    this.broadcaster = broadcaster;
    this.sampleIntervalMillis = sampleInterval.toMillis();
  }

  /** Where the trucks are. The firehose, and the only stream that is thinned. */
  @KafkaListener(topics = Topics.POSITION, id = "dashboard-position", idIsGroup = false)
  public void onPosition(ConsumerRecord<String, String> record) {
    PositionEvent event;
    try {
      event = EventJson.mapper().readValue(record.value(), PositionEvent.class);
    } catch (JacksonException unparseable) {
      unreadable.incrementAndGet();
      return;
    }
    if (event.shipmentId() == null || event.position() == null) {
      unreadable.incrementAndGet();
      return;
    }
    positions.incrementAndGet();

    if (!passesGate(event.shipmentId())) {
      thinned.incrementAndGet();
      return;
    }

    broadcaster.publish(
        new LiveUpdate(
            LiveUpdate.POSITION,
            event.shipmentId(),
            event.occurredAt(),
            new LiveUpdate.Position(
                event.vehicleId(),
                event.position().latitude(),
                event.position().longitude(),
                event.speedKph(),
                event.headingDegrees(),
                event.accuracyMeters(),
                event.raw() == null ? null : event.raw().source().name())));
  }

  /** Temperatures, mostly. Rare enough to forward every one. */
  @KafkaListener(topics = Topics.STATUS, id = "dashboard-status", idIsGroup = false)
  public void onStatus(ConsumerRecord<String, String> record) {
    StatusEvent event;
    try {
      event = EventJson.mapper().readValue(record.value(), StatusEvent.class);
    } catch (JacksonException unparseable) {
      unreadable.incrementAndGet();
      return;
    }
    if (event.shipmentId() == null) {
      unreadable.incrementAndGet();
      return;
    }
    statuses.incrementAndGet();

    broadcaster.publish(
        new LiveUpdate(
            LiveUpdate.STATUS,
            event.shipmentId(),
            event.occurredAt(),
            new LiveUpdate.Status(
                event.vehicleId(),
                event.status() == null ? null : event.status().name(),
                event.reasonCode(),
                event.temperature() == null ? null : event.temperature().celsius(),
                event.temperature() == null ? null : event.temperature().setpointCelsius())));
  }

  /**
   * What the platform concluded: arrivals, departures and estimates, told apart by shape.
   *
   * <p>Field presence rather than a type field, matching the exception service exactly — and, like
   * it, an unrecognised shape is ignored rather than treated as an error. A fifth kind of derived
   * event must be able to appear without a dashboard refusing the platform's own traffic.
   */
  @KafkaListener(topics = Topics.DERIVED, id = "dashboard-derived", idIsGroup = false)
  public void onDerived(ConsumerRecord<String, String> record) {
    JsonNode tree;
    try {
      tree = EventJson.mapper().readTree(record.value());
    } catch (JacksonException unparseable) {
      unreadable.incrementAndGet();
      return;
    }

    try {
      if (tree.has("estimatedArrival")) {
        EtaUpdated estimate = EventJson.mapper().treeToValue(tree, EtaUpdated.class);
        broadcaster.publish(
            new LiveUpdate(
                LiveUpdate.ESTIMATE,
                estimate.shipmentId(),
                estimate.occurredAt(),
                new LiveUpdate.Estimate(
                    estimate.stopId(),
                    estimate.estimatedArrival(),
                    estimate.previousEstimate(),
                    estimate.remainingKm(),
                    estimate.confidence())));
      } else if (tree.has("dwell")) {
        ShipmentDeparted departed = EventJson.mapper().treeToValue(tree, ShipmentDeparted.class);
        broadcaster.publish(
            new LiveUpdate(
                LiveUpdate.DEPARTED,
                departed.shipmentId(),
                departed.occurredAt(),
                new LiveUpdate.StopEvent(
                    departed.stopId(),
                    departed.position().latitude(),
                    departed.position().longitude(),
                    departed.dwell() == null ? null : departed.dwell().toSeconds(),
                    null)));
      } else if (tree.has("stopId")) {
        ShipmentArrived arrived = EventJson.mapper().treeToValue(tree, ShipmentArrived.class);
        broadcaster.publish(
            new LiveUpdate(
                LiveUpdate.ARRIVED,
                arrived.shipmentId(),
                arrived.occurredAt(),
                new LiveUpdate.StopEvent(
                    arrived.stopId(),
                    arrived.position().latitude(),
                    arrived.position().longitude(),
                    null,
                    arrived.scheduledArrival())));
      } else {
        log.debug("stream: ignoring an unrecognised derived event shape");
        return;
      }
    } catch (JacksonException malformed) {
      unreadable.incrementAndGet();
      return;
    }
    derived.incrementAndGet();
  }

  /**
   * SLA breaches, raised and cleared.
   *
   * <p>Told apart by {@code openFor}, which only a clear carries — a raise does not yet know how
   * long the condition will last. The two share an {@code exceptionId}, which is what lets a browser
   * strike a warning off a marker rather than adding a second one beside it.
   */
  @KafkaListener(topics = Topics.EXCEPTIONS, id = "dashboard-exceptions", idIsGroup = false)
  public void onException(ConsumerRecord<String, String> record) {
    JsonNode tree;
    try {
      tree = EventJson.mapper().readTree(record.value());
    } catch (JacksonException unparseable) {
      unreadable.incrementAndGet();
      return;
    }

    try {
      if (tree.has("openFor")) {
        ExceptionCleared cleared = EventJson.mapper().treeToValue(tree, ExceptionCleared.class);
        broadcaster.publish(
            new LiveUpdate(
                LiveUpdate.EXCEPTION_CLEARED,
                cleared.shipmentId(),
                cleared.occurredAt(),
                new LiveUpdate.ExceptionEvent(
                    cleared.exceptionId(),
                    cleared.exceptionType() == null ? null : cleared.exceptionType().name(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    cleared.raisedAt(),
                    cleared.openFor() == null ? null : cleared.openFor().toSeconds(),
                    cleared.resolution())));
      } else {
        ExceptionRaised raised = EventJson.mapper().treeToValue(tree, ExceptionRaised.class);
        broadcaster.publish(
            new LiveUpdate(
                LiveUpdate.EXCEPTION_RAISED,
                raised.shipmentId(),
                raised.occurredAt(),
                new LiveUpdate.ExceptionEvent(
                    raised.exceptionId(),
                    raised.exceptionType() == null ? null : raised.exceptionType().name(),
                    raised.severity() == null ? null : raised.severity().name(),
                    raised.detail(),
                    raised.stopId(),
                    raised.observedValue(),
                    raised.thresholdValue(),
                    null,
                    null,
                    null)));
      }
    } catch (JacksonException malformed) {
      unreadable.incrementAndGet();
      return;
    }
    exceptions.incrementAndGet();
  }

  /** Position events seen, before thinning. */
  public long positionCount() {
    return positions.get();
  }

  /** Position events not forwarded because the shipment had just been reported. */
  public long thinnedCount() {
    return thinned.get();
  }

  /** Derived events forwarded. */
  public long derivedCount() {
    return derived.get();
  }

  /** Exception events forwarded. */
  public long exceptionCount() {
    return exceptions.get();
  }

  /** Records that could not be read. Not an error here — see the class note. */
  public long unreadableCount() {
    return unreadable.get();
  }

  /** A one-line summary for the heartbeat. */
  public String summary() {
    return "positions=" + positions.get()
        + " thinned=" + thinned.get()
        + " statuses=" + statuses.get()
        + " derived=" + derived.get()
        + " exceptions=" + exceptions.get()
        + " unreadable=" + unreadable.get();
  }

  /**
   * Whether this shipment has waited long enough since its last forwarded position.
   *
   * <p>Deliberately not synchronized. Two threads racing here can let one extra update through for
   * one shipment, which is a frame on a map; a lock on the busiest path in the service to prevent it
   * would be a poor trade.
   */
  private boolean passesGate(String shipmentId) {
    if (sampleIntervalMillis <= 0) {
      return true;
    }
    long now = System.currentTimeMillis();
    Long last = lastForwarded.get(shipmentId);
    if (last != null && now - last < sampleIntervalMillis) {
      return false;
    }
    if (lastForwarded.size() > GATE_CAPACITY) {
      lastForwarded.clear();
    }
    lastForwarded.put(shipmentId, now);
    return true;
  }
}
