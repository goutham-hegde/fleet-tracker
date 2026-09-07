package com.fleettracking.exceptions.consume;

import com.fleettracking.events.EtaUpdated;
import com.fleettracking.events.EventJson;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.ShipmentArrived;
import com.fleettracking.events.StatusEvent;
import com.fleettracking.events.Topics;
import com.fleettracking.exceptions.rule.RuleService;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * The three subscriptions, and the one piece of shape-sniffing this service has to do.
 *
 * <h2>Its own consumer group, which is the whole point of Kafka here</h2>
 *
 * <p>This service reads the same position topic the tracking processor reads, from the beginning,
 * without either service knowing the other exists and without the tracking processor's progress
 * being affected in any way. That is what a consumer group is: Kafka divides a topic's partitions
 * among the members of a group, and two different groups each get their own complete copy of the
 * stream and their own position in it. A queue would have made the second consumer a change to the
 * first.
 *
 * <p>{@code idIsGroup = false} is on every listener here, and it is not decoration. Spring Kafka
 * uses a listener's {@code id} as the group id by default, silently overriding
 * {@code spring.kafka.consumer.group-id} — the trap that had the tracking processor running for a
 * whole session in a group nobody had configured, with nothing reporting the disagreement. The group
 * id is the name that lag checks and offset resets are performed against, so a group whose real name
 * is an annotation's side effect is one nobody can operate.
 *
 * <h2>Three listeners, three threads, and what that means for state</h2>
 *
 * <p>Spring gives each annotated method its own listener container and its own thread, so a
 * shipment's positions and its reefer readings can be handled at the same moment. Rule state is
 * therefore partitioned by rule rather than shared: the temperature rule's state is written only
 * from the status topic, both position rules' only from the position topic, and the late-arrival
 * rule keeps none. The one thing genuinely shared across all three — the last time each shipment was
 * heard from — is a concurrent map, and it is the only place in this service where two threads meet.
 *
 * <h2>Telling three event shapes apart on one topic</h2>
 *
 * <p>The derived topic carries arrivals, departures and estimates, distinguished by which fields are
 * present. CLAUDE.md records what that already cost once: S11 added estimates and S10's geofencing
 * test counted them as arrivals, failing "exactly one arrival" for reasons unrelated to geofencing.
 *
 * <p>So the discrimination here is written to be as narrow as possible: parse to a tree, look for
 * the one field that identifies the shape, and ignore everything that matches nothing. An
 * unrecognised shape is not an error and is not dead-lettered — a fourth kind of derived event added
 * later must not make this service start rejecting the platform's own traffic.
 */
public class ExceptionConsumers {

  private static final Logger log = LoggerFactory.getLogger(ExceptionConsumers.class);

  private final RuleService rules;
  private final ExceptionDeadLetters deadLetters;

  private final AtomicLong positions = new AtomicLong();
  private final AtomicLong statuses = new AtomicLong();
  private final AtomicLong derived = new AtomicLong();
  private final AtomicLong deadLettered = new AtomicLong();

  public ExceptionConsumers(RuleService rules, ExceptionDeadLetters deadLetters) {
    this.rules = rules;
    this.deadLetters = deadLetters;
  }

  /** Positions: an unplanned stop, and a route deviation. */
  @KafkaListener(topics = Topics.POSITION, id = "exceptions-position", idIsGroup = false)
  public void onPosition(ConsumerRecord<String, String> record) {
    PositionEvent event;
    try {
      event = EventJson.mapper().readValue(record.value(), PositionEvent.class);
    } catch (JacksonException malformed) {
      // Identical bytes parse identically for ever, so retrying is a loop rather than a recovery.
      // The same rule the gateway and the tracking processor both apply.
      deadLetters.setAside(record, "UNPARSEABLE", malformed.getOriginalMessage());
      deadLettered.incrementAndGet();
      return;
    }
    if (event.shipmentId() == null || event.occurredAt() == null || event.position() == null) {
      deadLetters.setAside(record, "INCOMPLETE", "missing shipmentId, occurredAt or position");
      deadLettered.incrementAndGet();
      return;
    }
    rules.onPosition(event);
    positions.incrementAndGet();
  }

  /** Statuses: the reefer readings, and nothing else this service acts on. */
  @KafkaListener(topics = Topics.STATUS, id = "exceptions-status", idIsGroup = false)
  public void onStatus(ConsumerRecord<String, String> record) {
    StatusEvent event;
    try {
      event = EventJson.mapper().readValue(record.value(), StatusEvent.class);
    } catch (JacksonException malformed) {
      deadLetters.setAside(record, "UNPARSEABLE", malformed.getOriginalMessage());
      deadLettered.incrementAndGet();
      return;
    }
    if (event.shipmentId() == null || event.occurredAt() == null || event.status() == null) {
      deadLetters.setAside(record, "INCOMPLETE", "missing shipmentId, occurredAt or status");
      deadLettered.incrementAndGet();
      return;
    }
    rules.onStatus(event);
    statuses.incrementAndGet();
  }

  /**
   * What the platform concluded: arrivals settle a projection, estimates make one.
   *
   * <p>Departures are read and ignored. They are counted as the shipment having been heard from —
   * which is all the signal-loss rule needs — and no rule has an opinion about them.
   */
  @KafkaListener(topics = Topics.DERIVED, id = "exceptions-derived", idIsGroup = false)
  public void onDerived(ConsumerRecord<String, String> record) {
    JsonNode tree;
    try {
      tree = EventJson.mapper().readTree(record.value());
    } catch (JacksonException malformed) {
      deadLetters.setAside(record, "UNPARSEABLE", malformed.getOriginalMessage());
      deadLettered.incrementAndGet();
      return;
    }

    try {
      if (tree.has("estimatedArrival")) {
        // Only an estimate carries a predicted arrival.
        rules.onEstimate(EventJson.mapper().treeToValue(tree, EtaUpdated.class));
      } else if (tree.has("dwell")) {
        // Only a departure carries a dwell. Nothing to judge, but it still counts as a sighting.
        String shipmentId = tree.path("shipmentId").asString(null);
        String eventId = tree.path("eventId").asString(null);
        String occurredAt = tree.path("occurredAt").asString(null);
        if (shipmentId != null && occurredAt != null) {
          rules.onDeparture(shipmentId, java.time.Instant.parse(occurredAt), eventId);
        }
      } else if (tree.has("stopId")) {
        // What is left with a stop is an arrival.
        rules.onArrival(EventJson.mapper().treeToValue(tree, ShipmentArrived.class));
      } else {
        // A shape this service does not recognise. Deliberately not an error and deliberately not
        // dead-lettered: a fifth kind of derived event must be able to appear without this
        // service rejecting it.
        log.debug("ignoring an unrecognised derived event shape");
      }
    } catch (JacksonException malformed) {
      deadLetters.setAside(record, "UNPARSEABLE", malformed.getOriginalMessage());
      deadLettered.incrementAndGet();
      return;
    }
    derived.incrementAndGet();
  }

  /** Position events seen. */
  public long positionCount() {
    return positions.get();
  }

  /** Status events seen. */
  public long statusCount() {
    return statuses.get();
  }

  /** Derived events seen. */
  public long derivedCount() {
    return derived.get();
  }

  /** Records set aside as unprocessable. */
  public long deadLetteredCount() {
    return deadLettered.get();
  }

  /** A one-line summary, logged periodically by the heartbeat. */
  public String summary() {
    return "positions=" + positions.get()
        + " statuses=" + statuses.get()
        + " derived=" + derived.get()
        + " dlq=" + deadLettered.get();
  }
}
