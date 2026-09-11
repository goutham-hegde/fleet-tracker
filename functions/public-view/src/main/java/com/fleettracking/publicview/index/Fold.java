package com.fleettracking.publicview.index;

import com.fleettracking.events.EtaUpdated;
import com.fleettracking.events.Event;
import com.fleettracking.events.ExceptionCleared;
import com.fleettracking.events.ExceptionRaised;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.ShipmentArrived;
import com.fleettracking.events.ShipmentDeparted;
import com.fleettracking.events.StatusEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One archive file reduced to the handful of facts the public page is made of.
 *
 * <h2>Why reduce before writing anything</h2>
 *
 * <p>An hour of positions is tens of thousands of lines, and the table this feeds has a fixed
 * write capacity: ten writes a second, because provisioned capacity is what keeps DynamoDB inside
 * the always-free allowance. Writing each line would take an hour to index an hour. Almost all of
 * those lines are superseded within the same file anyway, since only the newest fix per shipment
 * matters to a map. So the file is folded in memory first, and what reaches the table is at most
 * one position per shipment, one arrival and one departure per stop, and one row per incident.
 *
 * <h2>What "newest" means</h2>
 *
 * <p>Event time, strictly. A later fix replaces an earlier one only if its {@code occurredAt} is
 * later, which is the rule the tracking processor applies to {@code shipment.position} and for the
 * same reason: the mobile app delivers backlogs out of order, and the last line of a burst is not
 * the last place the truck was. The table applies the same condition again, across files.
 *
 * <h2>What is left out</h2>
 *
 * <p>Estimates and status readings are counted and skipped. An estimate in an archive is an opinion
 * about a moment that may be days gone, and a public page that showed "arriving 14:05" from last
 * week's run would be wrong in a way nobody looking at it could detect. Status readings would add
 * the reefer temperature, which is worth doing later and is not what M8 asks for.
 */
public final class Fold {

  /** A stop on one shipment's plan. */
  public record StopKey(String shipmentId, String stopId) {}

  /**
   * An incident as far as one file has seen it. Either half may be missing: the raise and the
   * clear can sit in different files, and those files can be indexed in either order.
   */
  public record Incident(ExceptionRaised raised, ExceptionCleared cleared) {

    Incident with(ExceptionRaised r) {
      return new Incident(raised == null || r.occurredAt().isAfter(raised.occurredAt()) ? r : raised, cleared);
    }

    Incident with(ExceptionCleared c) {
      return new Incident(raised, cleared == null || c.occurredAt().isAfter(cleared.occurredAt()) ? c : cleared);
    }
  }

  private final Map<String, PositionEvent> positions = new LinkedHashMap<>();
  private final Map<StopKey, ShipmentArrived> arrivals = new LinkedHashMap<>();
  private final Map<StopKey, ShipmentDeparted> departures = new LinkedHashMap<>();
  private final Map<String, Incident> incidents = new LinkedHashMap<>();
  private Instant newestReceived;
  private int events;
  private int skipped;

  /** Takes one event into account. Returns this, for chaining in tests. */
  public Fold add(Event event) {
    events++;
    switch (event) {
      case PositionEvent p -> positions.merge(p.shipmentId(), p, Fold::later);
      case ShipmentArrived a ->
          arrivals.merge(new StopKey(a.shipmentId(), a.stopId()), a, (x, y) -> y.occurredAt().isAfter(x.occurredAt()) ? y : x);
      case ShipmentDeparted d ->
          departures.merge(new StopKey(d.shipmentId(), d.stopId()), d, (x, y) -> y.occurredAt().isAfter(x.occurredAt()) ? y : x);
      case ExceptionRaised r ->
          incidents.merge(r.exceptionId(), new Incident(r, null), (x, y) -> x.with(r));
      case ExceptionCleared c ->
          incidents.merge(c.exceptionId(), new Incident(null, c), (x, y) -> x.with(c));
      case EtaUpdated e -> skipped++;
      case StatusEvent s -> skipped++;
    }
    return this;
  }

  /**
   * Records when the platform received the newest line, from its Kafka timestamp. That is
   * wall-clock time, so it is what "the archive runs up to" means to a person reading the page.
   */
  public void received(Instant timestamp) {
    if (timestamp != null && (newestReceived == null || timestamp.isAfter(newestReceived))) {
      newestReceived = timestamp;
    }
  }

  private static PositionEvent later(PositionEvent held, PositionEvent candidate) {
    return candidate.occurredAt().isAfter(held.occurredAt()) ? candidate : held;
  }

  public Map<String, PositionEvent> positions() {
    return positions;
  }

  public Map<StopKey, ShipmentArrived> arrivals() {
    return arrivals;
  }

  public Map<StopKey, ShipmentDeparted> departures() {
    return departures;
  }

  public Map<String, Incident> incidents() {
    return incidents;
  }

  public Instant newestReceived() {
    return newestReceived;
  }

  public int events() {
    return events;
  }

  public int skipped() {
    return skipped;
  }

  /** How many rows this file will touch at most. */
  public int facts() {
    return positions.size() + arrivals.size() + departures.size() + incidents.size();
  }
}
