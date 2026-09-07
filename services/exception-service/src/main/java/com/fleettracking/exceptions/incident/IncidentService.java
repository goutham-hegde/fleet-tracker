package com.fleettracking.exceptions.incident;

import com.fleettracking.events.ExceptionCleared;
import com.fleettracking.events.ExceptionRaised;
import com.fleettracking.events.ExceptionType;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns what a rule concluded into an incident on the topic and a document in the database.
 *
 * <p>All five rules go through here, which is what stops five slightly different opinions about
 * when an exception is new, when it is the same one continuing, and what a clear means. A rule
 * decides <em>whether the condition holds</em>; everything about identity, ordering, durability and
 * the shape of the two events is decided once, here.
 *
 * <h2>Raising is idempotent, and that is the whole job</h2>
 *
 * <p>A rule does not know whether it has fired before. A reefer sitting three degrees warm reports
 * every couple of minutes and the rule concludes "still out of band" every single time; the
 * temperature rule would happily produce a finding per reading. What turns a stream of identical
 * findings into one incident is the check below: if something of this type is already open for this
 * shipment and stop, the finding updates it and publishes nothing.
 *
 * <p>This is the difference between an exception system somebody uses and one they mute.
 *
 * <h2>Publish, then record</h2>
 *
 * <p>The same order geofencing uses, chosen the same way. There is no transaction across Kafka and
 * MongoDB, so a crash between the two steps has to be survivable in one direction or the other.
 * Recording first can leave an incident marked raised whose alert was never published — invisible,
 * permanent, and exactly the failure an alerting system must not have. Publishing first can repeat
 * an alert, and the repeat is byte-identical because {@link IncidentIds} derives every id from the
 * incident rather than generating one.
 *
 * <p>Clearing takes the same order for the same reason, and the asymmetry is worth noticing: a lost
 * raise means nobody is told about a broken cold chain, while a lost clear means somebody is told
 * about one that has already recovered. Both are bad; only the first is dangerous.
 */
public class IncidentService {

  private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

  private final IncidentStore store;
  private final ExceptionPublisher publisher;

  private final AtomicLong raised = new AtomicLong();
  private final AtomicLong cleared = new AtomicLong();
  private final AtomicLong suppressed = new AtomicLong();

  public IncidentService(IncidentStore store, ExceptionPublisher publisher) {
    this.store = store;
    this.publisher = publisher;
  }

  /**
   * Opens an incident for this finding, or confirms the one already open.
   *
   * @return the incident, whether it was opened now or was already running
   */
  public Incident raise(Finding finding) {
    Optional<Incident> existing =
        store.open(finding.type(), finding.shipmentId(), finding.stopId());

    if (existing.isPresent()) {
      // The condition is still true and was already announced. Refresh what is known about it --
      // how bad it has got, and how recently it was confirmed -- and say nothing on the topic.
      Incident updated =
          existing
              .get()
              .stillTrue(
                  finding.confirmedAt(),
                  finding.severity(),
                  finding.detail(),
                  finding.observedValue());
      store.save(updated);
      suppressed.incrementAndGet();
      return updated;
    }

    String exceptionId =
        IncidentIds.incident(
            finding.type(), finding.shipmentId(), finding.stopId(), finding.onsetAt());

    // Stamped with the onset, not with the moment the rule became sure. See Finding.
    ExceptionRaised event =
        new ExceptionRaised(
            IncidentIds.raised(exceptionId),
            finding.shipmentId(),
            finding.onsetAt(),
            finding.causedBy(),
            exceptionId,
            finding.type(),
            finding.severity(),
            finding.detail(),
            finding.stopId(),
            finding.observedValue(),
            finding.thresholdValue());

    publisher.publish(event);

    Incident incident =
        new Incident(
            exceptionId,
            finding.shipmentId(),
            finding.type(),
            finding.stopId(),
            finding.severity(),
            Incident.OPEN,
            finding.onsetAt(),
            finding.confirmedAt(),
            null,
            finding.confirmedAt(),
            finding.detail(),
            finding.observedValue(),
            finding.thresholdValue(),
            null);

    store.save(incident);
    raised.incrementAndGet();
    log.info(
        "raised {} for {}{}: {}",
        finding.type(),
        finding.shipmentId(),
        finding.stopId() == null ? "" : " at " + finding.stopId(),
        finding.detail());
    return incident;
  }

  /**
   * Closes the open incident of this type, if there is one.
   *
   * <p>Called unconditionally by the rules on every event that shows the condition no longer holds,
   * so the common case is that nothing is open and nothing happens. That is deliberate: a rule that
   * had to remember whether it had raised in order to know whether to clear would be keeping a
   * second copy of the state this collection already holds.
   *
   * @param resolution how it ended, in a couple of words. Free text rather than an enum, which
   *     {@code ExceptionCleared} explains: the real categories are not yet known, and an enum
   *     guessed now would need migrating
   * @return true if something was actually closed
   */
  public boolean clear(
      ExceptionType type,
      String shipmentId,
      String stopId,
      Instant at,
      String causedBy,
      String resolution) {

    Optional<Incident> existing = store.open(type, shipmentId, stopId);
    if (existing.isEmpty()) {
      return false;
    }
    Incident incident = existing.get();

    // Measured from the onset, so the reported duration is how long the shipment was actually in
    // breach -- not how long this service had known about it.
    Duration openFor = Duration.between(incident.onsetAt(), at);
    if (openFor.isNegative()) {
      // An out-of-order event, resolving an incident it predates. The pair would then claim a
      // negative duration, which no consumer should have to defend against.
      openFor = Duration.ZERO;
    }

    ExceptionCleared event =
        new ExceptionCleared(
            IncidentIds.cleared(incident.exceptionId()),
            shipmentId,
            at,
            causedBy,
            incident.exceptionId(),
            type,
            incident.onsetAt(),
            openFor,
            resolution);

    publisher.publish(event);
    store.save(incident.closed(at, resolution));
    cleared.incrementAndGet();
    log.info(
        "cleared {} for {} after {} ({})",
        type,
        shipmentId,
        openFor,
        resolution);
    return true;
  }

  /** Incidents opened by this process since it started. */
  public long raisedCount() {
    return raised.get();
  }

  /** Incidents closed by this process since it started. */
  public long clearedCount() {
    return cleared.get();
  }

  /**
   * Findings that restated an incident already open.
   *
   * <p>Worth counting rather than ignoring: this number being large relative to the raise count is
   * the system working — one alert per breach instead of one per reading — and it being zero when
   * exceptions are being raised means the deduplication is not doing anything, which would be a
   * symptom of onset instants moving when they should not.
   */
  public long suppressedCount() {
    return suppressed.get();
  }

  /** A one-line summary, logged periodically by the heartbeat. */
  public String summary() {
    return "raised=" + raised.get()
        + " cleared=" + cleared.get()
        + " restated=" + suppressed.get();
  }
}
