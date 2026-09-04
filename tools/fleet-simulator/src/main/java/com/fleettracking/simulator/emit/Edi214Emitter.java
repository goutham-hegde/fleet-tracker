package com.fleettracking.simulator.emit;

import com.fleettracking.events.SourceSystem;
import com.fleettracking.simulator.Simulation;
import com.fleettracking.simulator.TickObserver;
import com.fleettracking.simulator.fleet.TruckTransition;
import com.fleettracking.simulator.route.Stop;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The carrier's back office, filing EDI 214 status messages hours after the fact.
 *
 * <p>This is the feed that breaks the assumption the other three share: that a message describes
 * roughly the present. An EDI 214 describes something that happened, in a batch that went out much
 * later, and it does so in a format with no coordinates in it at all.
 *
 * <ul>
 *   <li><b>No position, ever.</b> The only location is a city and a state in an {@code MS1}
 *       segment: {@code MS1*BHIWANDI*MH*IN}. That is a true and useful statement which cannot be
 *       drawn on a map, geofenced, or compared to a previous position until something geocodes it.
 *       It is why {@link com.fleettracking.events.LocationHint} is a distinct type from
 *       {@link com.fleettracking.events.GeoPoint} — the difference between knowing where a truck is
 *       and knowing what the carrier called the place is worth having in the type system.
 *   <li><b>Deliberately not carrying our stop id.</b> A carrier does not know the identifiers in
 *       this platform's route model, so matching "BHIWANDI MH" to the Bhiwandi DC is real work for
 *       M2 rather than a lookup. Putting a stop id on the wire here would have quietly deleted the
 *       hardest part of the problem.
 *   <li><b>Two separate delays.</b> The back office takes {@code filingDelay} to enter an event at
 *       all, and the entered event then waits for the next {@code batchInterval} window. A
 *       departure at 09:30 can reach the platform at 11:00, by which time the truck is 130 km away
 *       and telematics has said so a hundred and eighty times.
 *   <li><b>Minute resolution.</b> {@code AT7} carries {@code HHMM} and no seconds, so every EDI
 *       timestamp is rounded. Reconciling it against a GPS fix stamped to the millisecond is an
 *       exercise in tolerances.
 *   <li><b>Many shipments in one message.</b> An interchange is a batch covering whatever the
 *       carrier had queued, so it has no single shipment id and therefore <em>no Kafka key</em>.
 *       M2 has to split the interchange into per-shipment events before it can key anything, which
 *       is why {@code routingKey} is null on these messages rather than dishonestly set to the
 *       first shipment in the batch.
 * </ul>
 *
 * <p>Fuel stops and other waypoints are never filed. Carriers report freight events, not every time
 * a truck stops moving — so M3's geofencing will observe arrivals that EDI has no opinion about,
 * which is a realistic and useful disagreement between two sources.
 */
public class Edi214Emitter implements TickObserver {

  private final EmissionProperties.Edi config;
  private final MessageSink sink;

  private final List<PendingStatus> pending = new ArrayList<>();
  private Instant nextBatchAt;
  private long interchangeControlNumber = 100;

  public Edi214Emitter(EmissionProperties.Edi config, MessageSink sink) {
    this.config = config;
    this.sink = sink;
  }

  @Override
  public void onTick(Simulation.TickReport report) {
    for (TruckTransition transition : report.transitions()) {
      statusCodeFor(transition)
          .ifPresent(
              code ->
                  pending.add(
                      new PendingStatus(
                          transition.shipmentId(),
                          transition.vehicleId(),
                          code,
                          transition.stop(),
                          transition.at())));
    }

    Instant now = report.at();
    if (nextBatchAt == null) {
      nextBatchAt = now.plus(config.batchInterval());
      return;
    }
    if (now.isBefore(nextBatchAt)) {
      return;
    }
    nextBatchAt = now.plus(config.batchInterval());
    flush(now);
  }

  /**
   * Sends everything the back office has got round to entering.
   *
   * <p>A status is eligible only once {@code filingDelay} has passed since the event. Anything
   * newer stays in the queue for the following batch, which is what produces the realistic mix of
   * a batch containing events of quite different ages.
   */
  private void flush(Instant now) {
    List<PendingStatus> ready = new ArrayList<>();
    pending.removeIf(
        status -> {
          if (!status.occurredAt().plus(config.filingDelay()).isAfter(now)) {
            ready.add(status);
            return true;
          }
          return false;
        });

    if (ready.isEmpty()) {
      return;
    }

    Instant earliest =
        ready.stream().map(PendingStatus::occurredAt).min(Instant::compareTo).orElse(now);
    String body = interchange(ready, now, ++interchangeControlNumber);

    // No routing key: a batch covers many shipments and belongs to none of them.
    sink.accept(
        new SourceMessage(
            SourceSystem.EDI_214,
            SourceSystem.EDI_214.defaultContentType(),
            null,
            earliest,
            now,
            body));
  }

  /** Wraps the transaction sets in an ISA/GS envelope, as a carrier's translator would. */
  String interchange(List<PendingStatus> statuses, Instant sentAt, long controlNumber) {
    StringBuilder out = new StringBuilder();

    out.append(
        X12.segment(
            "ISA",
            "00",
            X12.pad("", 10),
            "00",
            X12.pad("", 10),
            "02", // sender is identified by SCAC
            X12.pad(config.senderId(), 15),
            "ZZ", // receiver is identified by a mutually agreed id
            X12.pad(config.receiverId(), 15),
            X12.date6(sentAt),
            X12.time4(sentAt),
            "U",
            "00401",
            X12.controlNumber(controlNumber, 9),
            "0", // no acknowledgement requested
            "P", // production, not test
            ">"));

    out.append(
        X12.segment(
            "GS",
            "QM", // Transportation Carrier Shipment Status
            config.senderId(),
            config.receiverId(),
            X12.date8(sentAt),
            X12.time4(sentAt),
            String.valueOf(controlNumber),
            "X",
            "004010"));

    int setNumber = 0;
    for (PendingStatus status : statuses) {
      out.append(transactionSet(status, ++setNumber));
    }

    out.append(X12.segment("GE", String.valueOf(statuses.size()), String.valueOf(controlNumber)));
    out.append(X12.segment("IEA", "1", X12.controlNumber(controlNumber, 9)));
    return out.toString();
  }

  /**
   * One shipment status: {@code ST} through {@code SE}.
   *
   * <p>{@code SE} carries the number of segments in the set, counting {@code ST} and {@code SE}
   * themselves. It is a checksum a receiver validates, so it has to be right.
   */
  private String transactionSet(PendingStatus status, int setNumber) {
    String control = "%04d".formatted(setNumber);
    StringBuilder set = new StringBuilder();

    set.append(X12.segment("ST", "214", control));
    set.append(
        X12.segment(
            "B10",
            // The carrier's own trip number, which means nothing to this platform.
            String.valueOf(Math.abs(status.shipmentId().hashCode() % 10_000_000)),
            status.shipmentId(),
            config.scac()));
    set.append(X12.segment("LX", "1"));
    set.append(
        X12.segment(
            "AT7",
            status.code(),
            "NS", // normal status, as opposed to an exception reason
            "", // appointment status, unpopulated
            "", // appointment reason, unpopulated
            X12.date8(status.occurredAt()),
            X12.time4(status.occurredAt()),
            "UT")); // universal time
    set.append(
        X12.segment(
            "MS1",
            status.stop().city().toUpperCase(java.util.Locale.ROOT),
            status.stop().state(),
            // ISO 3166 alpha-2, as X12 expects. "IN" while the fleet runs Indian lanes; this
            // read "US" and is the one field in the segment that is not derived from the stop.
            "IN"));

    // ST, B10, LX, AT7, MS1, and the SE about to be written.
    int segmentCount = 6;
    set.append(X12.segment("SE", String.valueOf(segmentCount), control));
    return set.toString();
  }

  /**
   * Maps a simulator transition to an X12 shipment status code.
   *
   * <p>The codes are the carrier's vocabulary, not this platform's, which is exactly why
   * {@link com.fleettracking.events.StatusCode} does not contain them: translating {@code X3} into
   * "arrived at a pickup" is the EDI normalizer's job and nobody else's.
   *
   * <p>Waypoints return empty — a fuel stop is not a freight event and is never filed.
   */
  private static java.util.Optional<String> statusCodeFor(TruckTransition transition) {
    Stop stop = transition.stop();
    boolean pickup = stop.kind() == Stop.StopKind.PICKUP;

    return switch (transition) {
      case TruckTransition.Arrived a ->
          switch (stop.kind()) {
            case PICKUP -> java.util.Optional.of("X3"); // arrived at pick-up location
            case DELIVERY -> java.util.Optional.of("X1"); // arrived at delivery location
            case WAYPOINT -> java.util.Optional.empty();
          };
      case TruckTransition.Departed d ->
          switch (stop.kind()) {
            // AF: carrier departed pick-up location with shipment.
            case PICKUP -> java.util.Optional.of("AF");
            case DELIVERY -> java.util.Optional.of("CD"); // carrier departed delivery location
            case WAYPOINT -> java.util.Optional.empty();
          };
      // X4: completed unloading at delivery location. The route is over.
      case TruckTransition.RouteCompleted c ->
          pickup ? java.util.Optional.empty() : java.util.Optional.of("X4");
    };
  }

  /** How many statuses are waiting for the next batch window. Test seam. */
  int pendingCount() {
    return pending.size();
  }

  /** A status the back office has recorded but not yet transmitted. */
  record PendingStatus(
      String shipmentId, String vehicleId, String code, Stop stop, Instant occurredAt) {}
}
