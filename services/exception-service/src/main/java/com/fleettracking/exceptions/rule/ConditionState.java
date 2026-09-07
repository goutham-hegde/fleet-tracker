package com.fleettracking.exceptions.rule;

import com.fleettracking.events.ExceptionType;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * How long a condition has been true for one shipment, under one rule.
 *
 * <h2>Three rules, one shape, and why that is not a coincidence</h2>
 *
 * <p>A temperature excursion, an unplanned stop and a route deviation look like three different
 * problems and are three instances of the same one: something is momentarily wrong, and what
 * decides whether it matters is <em>how long it stays wrong</em>. A reefer a degree warm for ninety
 * seconds is a door opening; the same reading for forty minutes is a broken unit. A truck stationary
 * for two minutes is a traffic light; for half an hour it is a breakdown. A fix ten kilometres off
 * the planned line is a reflected signal; the same offset for a quarter of an hour is a diversion.
 *
 * <p>So all three need exactly this: when did it start, how bad has it got, where was the vehicle,
 * and what is the newest event I have applied. A shared record rather than three near-identical ones
 * means the tolerance logic is written once and the three rules only supply the judgement that makes
 * them different.
 *
 * <p>The fourth and fifth rules deliberately do not use it. A late arrival is not a sustained
 * condition — the projection is either past the deadline or it is not, and the incident's own onset
 * records when it first was. A silent device is the absence of events, and a state keyed by the
 * events that arrive cannot represent it.
 *
 * <h2>Written on transition, not on every event</h2>
 *
 * <p>The condition starting, ending, or being confirmed into an incident are the moments worth
 * making durable — a handful per shipment per journey. Between them only {@code lastEventAt} and
 * {@code worst} move, and losing those to a restart costs a restarted clock on a condition that is
 * still true, which the next tolerance period recovers from. Writing per event would put a MongoDB
 * write on the busiest path in the platform to protect a number that is regenerated within minutes.
 *
 * @param id {@code shipmentId|TYPE}, derived so that the same rule's state for the same shipment is
 *     always the same document
 * @param since when the condition began, or null when it is not currently true. This is what the
 *     incident id will be derived from, so it must survive a restart and must never be revised
 *     while the condition holds
 * @param lastEventAt the newest event time applied. Anything not strictly after it is refused: the
 *     mobile feed dumps backlogs out of order, and an old fix must not walk a rule's clock backwards
 * @param worst the most extreme value seen since the onset — degrees outside the band, kilometres
 *     off the corridor. Reported in the detail so the sentence describes the breach at its worst
 *     rather than at whatever it happened to be on the reading that confirmed it
 * @param latitude and {@code longitude} where the vehicle was when the condition began, for the
 *     rules that are about a place. Null for the reefer, which has no idea where it is
 */
@Document(collection = ConditionState.COLLECTION)
public record ConditionState(
    @Id String id,
    String shipmentId,
    ExceptionType type,
    Instant since,
    Instant lastEventAt,
    Double worst,
    Double latitude,
    Double longitude) {

  /** One collection for all three rules; the type is part of the id and of every document. */
  public static final String COLLECTION = "exception.state";

  /** The document id for a shipment under one rule. */
  public static String idFor(String shipmentId, ExceptionType type) {
    return shipmentId + "|" + type.name();
  }

  /** A shipment this rule has never had an opinion about. */
  public static ConditionState initial(String shipmentId, ExceptionType type) {
    return new ConditionState(idFor(shipmentId, type), shipmentId, type, null, null, null, null, null);
  }

  /** Whether the condition is currently true. */
  public boolean holding() {
    return since != null;
  }

  /** Whether this event is newer than everything already applied. */
  public boolean isNewerThanApplied(Instant at) {
    return lastEventAt == null || at.isAfter(lastEventAt);
  }

  /** How long the condition has held as of an instant. Zero if it is not holding. */
  public Duration heldFor(Instant at) {
    return since == null ? Duration.ZERO : Duration.between(since, at);
  }

  /** The condition has just become true. */
  public ConditionState beginning(Instant at, double value, Double lat, Double lon) {
    return new ConditionState(id, shipmentId, type, at, at, value, lat, lon);
  }

  /** The condition is still true, and possibly worse than it was. */
  public ConditionState continuing(Instant at, double value) {
    double worstSoFar = worst == null ? value : Math.max(worst, value);
    return new ConditionState(id, shipmentId, type, since, at, worstSoFar, latitude, longitude);
  }

  /** The condition is no longer true. The clock is cleared, so the next onset is a new incident. */
  public ConditionState ending(Instant at) {
    return new ConditionState(id, shipmentId, type, null, at, null, null, null);
  }

  /** Nothing changed except how far this rule has read. */
  public ConditionState quiet(Instant at) {
    return new ConditionState(id, shipmentId, type, since, at, worst, latitude, longitude);
  }
}
