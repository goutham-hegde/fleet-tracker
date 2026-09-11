package com.fleettracking.publicview.lookup;

import com.fleettracking.events.Severity;
import com.fleettracking.publicview.lookup.Wire.AtStop;
import com.fleettracking.publicview.lookup.Wire.IncidentSummary;
import com.fleettracking.publicview.lookup.Wire.Meta;
import com.fleettracking.publicview.lookup.Wire.PlannedStop;
import com.fleettracking.publicview.lookup.Wire.ShipmentDetail;
import com.fleettracking.publicview.lookup.Wire.ShipmentSummary;
import com.fleettracking.publicview.plan.Plans;
import com.fleettracking.publicview.plan.Plans.Stop;
import com.fleettracking.publicview.store.ShipmentFacts;
import com.fleettracking.publicview.store.ShipmentFacts.IncidentFacts;
import com.fleettracking.publicview.store.ShipmentFacts.Position;
import com.fleettracking.publicview.store.ShipmentFacts.StopFacts;
import com.fleettracking.publicview.store.ShipmentFacts.Watermark;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns stored facts into what the public page draws. A pure function of the facts, the plan and a
 * clock, like the dashboard API's {@code FleetAssembler}, and for the same reason: every awkward case
 * is tested by calling a method.
 *
 * <h2>Where this agrees with the live dashboard, and why it is a copy</h2>
 *
 * <p>Which stop a truck is at, how many it has done, and what its marker says are decided exactly as
 * {@code FleetAssembler} decides them: an arrival announced and no departure is "at a stop"; every
 * planned stop arrived at is "delivered"; otherwise speed against {@link #MOVING_KPH}. It is a copy
 * rather than a call because {@code FleetAssembler} takes MongoDB documents as its input. Reusing it
 * would ship Spring Data and a Mongo driver in a function that can reach neither. If either copy
 * changes, change both, and {@code dashboard/src/fleet/FleetStore.ts}, which holds the third copy of
 * the speed threshold.
 *
 * <h2>The one rule the live dashboard does not need</h2>
 *
 * <p>The archive can hold more than one run over the same shipment id: a demonstration is re-run by
 * dropping the geofence state and starting the simulator again. The live view never sees both, because
 * that state was dropped. This table does, because it outlives the drop. So a stop's facts count only
 * if they belong to the <b>latest journey</b>, which begins at the most recent arrival at the plan's
 * origin. The platform announces that arrival at the start of every run, because each truck spawns
 * inside its pickup's geofence. A departure older than its arrival is from an earlier visit, and is not
 * counted either. Without this rule a re-run would show a truck at its first stop and already departed
 * from its last.
 */
public final class PublicAssembler {

  /** Above this, a truck is moving. The same 5 km/h as {@code FleetAssembler.MOVING_KPH}. */
  public static final double MOVING_KPH = 5.0;

  static final String PENDING = "PENDING";
  static final String AT = "AT";
  static final String DEPARTED = "DEPARTED";

  private final Plans plans;
  private final Clock clock;

  public PublicAssembler(Plans plans, Clock clock) {
    this.plans = plans;
    this.clock = clock;
  }

  /** A planned stop with what the latest journey did there. */
  record Visit(Stop stop, Instant arrived, Instant departed) {
    String status() {
      return arrived == null ? PENDING : departed == null ? AT : DEPARTED;
    }
  }

  /** Every marker, most troubled first, as the live fleet view orders them. */
  public List<ShipmentSummary> fleet(Collection<ShipmentFacts> shipments) {
    return shipments.stream()
        .map(this::summary)
        .flatMap(Optional::stream)
        .sorted(
            Comparator.comparingInt((ShipmentSummary s) -> -rank(s.worstSeverity()))
                .thenComparing(ShipmentSummary::shipmentId))
        .toList();
  }

  /**
   * One marker, or nothing for a shipment the archive has never shown a position for. The same rule
   * as the live view: with no position there is nothing to draw.
   */
  public Optional<ShipmentSummary> summary(ShipmentFacts facts) {
    Position p = facts.position();
    if (p == null) {
      return Optional.empty();
    }
    List<Visit> visits = visits(facts);
    long arrived = visits.stream().filter(v -> v.arrived() != null).count();
    Visit current = visits.stream().filter(v -> AT.equals(v.status())).findFirst().orElse(null);

    String movement;
    if (!visits.isEmpty() && arrived == visits.size()) {
      movement = "DELIVERED";
    } else if (current != null) {
      movement = "AT_STOP";
    } else {
      movement = p.speedKph() != null && p.speedKph() >= MOVING_KPH ? "MOVING" : "STOPPED";
    }

    List<IncidentSummary> open =
        facts.incidents().stream()
            .filter(IncidentFacts::isOpen)
            .map(PublicAssembler::incident)
            .sorted(
                Comparator.comparingInt((IncidentSummary i) -> -rank(i.severity()))
                    .thenComparing(IncidentSummary::onsetAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

    return Optional.of(
        new ShipmentSummary(
            facts.shipmentId(),
            p.vehicleId(),
            p.deviceId(),
            p.source(),
            p.occurredAt(),
            p.receivedAt(),
            staleSeconds(p.receivedAt()),
            p.latitude(),
            p.longitude(),
            p.speedKph(),
            p.headingDegrees(),
            p.accuracyMeters(),
            movement,
            current == null
                ? null
                : new AtStop(
                    current.stop().stopId(),
                    current.stop().name(),
                    current.stop().city(),
                    current.stop().seq(),
                    current.arrived()),
            (int) arrived,
            visits.size(),
            open.isEmpty() ? null : open,
            open.isEmpty() ? null : open.get(0).severity()));
  }

  /** The detail panel: the marker, the plan with what happened at each stop, and every incident. */
  public Optional<ShipmentDetail> detail(ShipmentFacts facts) {
    return summary(facts)
        .map(
            summary -> {
              List<PlannedStop> stops = new ArrayList<>();
              for (Visit v : visits(facts)) {
                Stop s = v.stop();
                stops.add(
                    new PlannedStop(
                        s.stopId(),
                        s.seq(),
                        s.name(),
                        s.city(),
                        s.state(),
                        s.latitude(),
                        s.longitude(),
                        s.radiusMeters(),
                        s.kind(),
                        v.status(),
                        v.arrived(),
                        v.departed(),
                        v.arrived() != null && v.departed() != null
                            ? Duration.between(v.arrived(), v.departed()).toSeconds()
                            : null));
              }
              List<IncidentSummary> all = byOnsetNewestFirst(facts.incidents().stream().map(PublicAssembler::incident).toList());
              return new ShipmentDetail(summary, stops, all.isEmpty() ? null : all);
            });
  }

  /** Incidents across the fleet, newest onset first, as the live exceptions endpoint orders them. */
  public List<IncidentSummary> incidents(
      Collection<ShipmentFacts> shipments, boolean openOnly, int limit) {
    return byOnsetNewestFirst(
            shipments.stream()
                .flatMap(s -> s.incidents().stream())
                .filter(i -> !openOnly || i.isOpen())
                .map(PublicAssembler::incident)
                .toList())
        .stream()
        .limit(limit)
        .toList();
  }

  public Meta meta(Collection<ShipmentFacts> shipments, List<Watermark> watermarks) {
    int tracked = (int) shipments.stream().filter(s -> s.position() != null).count();
    int open =
        (int) shipments.stream().flatMap(s -> s.incidents().stream()).filter(IncidentFacts::isOpen).count();
    Instant through =
        watermarks.stream()
            .map(Watermark::newestReceived)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
    return new Meta(tracked, open, "archive", through);
  }

  /** The plan, with the latest journey's facts laid over it. See the class comment. */
  List<Visit> visits(ShipmentFacts facts) {
    List<Stop> plan = plans.forShipment(facts.shipmentId());
    if (plan.isEmpty()) {
      return List.of();
    }
    StopFacts origin = facts.stops().get(plan.get(0).stopId());
    Instant journeyStart = origin == null ? null : origin.arrivedAt();

    List<Visit> visits = new ArrayList<>();
    for (Stop stop : plan) {
      StopFacts f = facts.stops().get(stop.stopId());
      Instant arrived = f == null ? null : f.arrivedAt();
      if (arrived != null && journeyStart != null && arrived.isBefore(journeyStart)) {
        arrived = null;
      }
      Instant departed =
          arrived == null || f.departedAt() == null || f.departedAt().isBefore(arrived)
              ? null
              : f.departedAt();
      visits.add(new Visit(stop, arrived, departed));
    }
    return visits;
  }

  static IncidentSummary incident(IncidentFacts i) {
    return new IncidentSummary(
        i.exceptionId(),
        i.shipmentId(),
        i.type(),
        i.severity(),
        i.isOpen() ? "OPEN" : "CLEARED",
        i.onsetAt(),
        // The wire carries when a condition began and when it cleared, but not when the rule became
        // sure of it. The live view reads that from the exception service's own collection; absent
        // here rather than guessed.
        null,
        i.clearedAt(),
        i.stopId(),
        i.detail(),
        i.observedValue(),
        i.thresholdValue(),
        i.resolution());
  }

  private static List<IncidentSummary> byOnsetNewestFirst(List<IncidentSummary> incidents) {
    return incidents.stream()
        .sorted(
            Comparator.comparing(IncidentSummary::onsetAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(IncidentSummary::exceptionId))
        .toList();
  }

  /** Unknown severities rank lowest rather than throwing, as in {@code FleetAssembler}. */
  static int rank(String severity) {
    if (severity == null) {
      return -1;
    }
    try {
      return Severity.valueOf(severity).ordinal();
    } catch (IllegalArgumentException unknown) {
      return -1;
    }
  }

  /**
   * Wall-clock seconds since the platform received the fix, clamped at zero, as the live view
   * measures it. On this page it is routinely hours or days, which is the honest answer.
   */
  private Long staleSeconds(Instant receivedAt) {
    return receivedAt == null ? null : Math.max(0, Duration.between(receivedAt, clock.instant()).toSeconds());
  }
}
