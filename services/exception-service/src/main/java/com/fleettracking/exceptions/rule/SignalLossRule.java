package com.fleettracking.exceptions.rule;

import com.fleettracking.events.ExceptionType;
import com.fleettracking.events.Severity;
import com.fleettracking.exceptions.ExceptionProperties;
import com.fleettracking.exceptions.incident.Finding;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A device that has stopped saying anything.
 *
 * <h2>The rule that cannot be evaluated on the stream</h2>
 *
 * <p>Every other rule is a function of an event: something arrives, it is judged, an opinion comes
 * out. This one fires on the <em>absence</em> of events, and no event will ever arrive to trigger
 * it. A truck whose telematics unit has failed produces exactly nothing, for ever, and a consumer
 * that only reacts to messages will sit there contentedly for the rest of the shipment.
 *
 * <p>So it needs two things the others do not: a memory of when each shipment was last heard from,
 * and a timer that periodically asks who has gone quiet.
 *
 * <h2>Two clocks, answering two different questions</h2>
 *
 * <p>This is the part that is easy to get wrong, and getting it wrong produces a flood of false
 * alerts at exactly the worst moment — service startup.
 *
 * <p><b>Event time</b> answers "how much of the shipment's day has passed with no news". It has to
 * be event time, because the simulator runs at up to three hundred times speed and a rule measured
 * against the wall clock would never fire during a fast run: four simulated hours of silence go past
 * in under a minute of real time. Since there is no event to read a clock from — that is the whole
 * problem — the current event time is taken as a <b>watermark</b>: the newest instant seen on any of
 * the three topics, from any shipment. If the fleet has collectively reached noon and one truck was
 * last heard from at half past ten, that truck has been silent for ninety minutes of shipment time.
 *
 * <p><b>Wall-clock time</b> answers a different and less obvious question: "have I actually had a
 * chance to hear from it?" Consider a service starting up and replaying an hour of retained topic
 * from the beginning. Within seconds the watermark races to the newest record, and every shipment
 * whose last message sits earlier in the backlog looks silent — the whole fleet would be reported
 * as having lost signal, all at once, purely because the consumer was catching up. Requiring that a
 * shipment has also been quiet for a short stretch of <em>real</em> time defeats that completely:
 * during a replay every shipment is being seen continuously in wall-clock terms, however far apart
 * their event times are.
 *
 * <p>Neither clock alone is sufficient, and the failure modes are opposite: event time alone floods
 * on startup, wall-clock time alone never fires under a time-scaled run.
 *
 * <h2>What it remembers, and what it deliberately does not</h2>
 *
 * <p>The last-seen map is in memory and is not persisted. A restart therefore forgets everyone, and
 * nothing is reported until each shipment has been seen at least once — which is the honest
 * behaviour. A service that has just started genuinely does not know whether a silent truck broke
 * down or was simply never assigned to this instance's partitions, and the alternative — persisting
 * a heartbeat per shipment per event — would put a database write on the busiest path in the
 * platform to preserve a number that regenerates on the next message.
 *
 * <p>A shipment that reaches the end of its itinerary is dropped from the watch list, and any open
 * incident for it is closed. Without that, every completed delivery becomes a permanent signal loss
 * a few minutes after the driver switches off — which is the single most common way an alerting
 * system fills up with things nobody can action.
 */
public class SignalLossRule {

  private final Duration threshold;
  private final Duration catchUpGrace;
  private final Clock clock;

  /** Last sighting per shipment, by both clocks. Bounded by the size of the active fleet. */
  private final Map<String, Sighting> lastSeen = new ConcurrentHashMap<>();

  /**
   * Shipments whose itinerary is finished, which must never go back on the watch list.
   *
   * <p>Removing a delivered load from {@link #lastSeen} is not enough on its own, and the gap is
   * only visible in a running system. Arrivals come from the derived topic and positions from the
   * position topic, on different listener threads and at different offsets — so the last few fixes
   * of the final leg routinely land *after* the arrival that concluded the shipment. Each one calls
   * {@link #seen}, which puts the load straight back under watch, and since it is delivered and
   * nothing more will ever be reported, it is announced as silent half an hour later.
   *
   * <p>That is precisely the failure this rule's completion logic exists to prevent, arriving
   * through a door that a single-threaded reading of the code does not show: found in S13 when a
   * live run finished with three open signal losses against loads that had all been delivered.
   *
   * <p>Bounded, and eviction is harmless: the worst case for a long-forgotten shipment is that a
   * straggling message re-registers it, which is the behaviour that existed before this map.
   */
  private final Set<String> completed =
      Collections.newSetFromMap(
          Collections.synchronizedMap(
              new LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                  return size() > 4096;
                }
              }));

  /** The newest event time seen on any topic, from any shipment. */
  private volatile Instant watermark = Instant.EPOCH;

  public SignalLossRule(ExceptionProperties.SignalLoss settings, Clock clock) {
    this.threshold = settings.threshold();
    this.catchUpGrace = settings.sweepInterval().multipliedBy(3);
    this.clock = clock;
  }

  /**
   * Records that a shipment was heard from.
   *
   * <p>Called by all three consumers, which is what makes "signal loss" mean total silence rather
   * than the absence of one particular feed. A reefer probe still reporting temperature is enough to
   * know a load has not fallen off the network, even if its GPS has stopped.
   *
   * @return a clearance if this shipment had been reported silent, so that the first message back
   *     closes the incident immediately rather than waiting for the next sweep
   */
  public Clearance seen(String shipmentId, Instant occurredAt, String eventId) {
    if (shipmentId == null || occurredAt == null) {
      return null;
    }
    // The watermark advances even for a delivered load: a straggling message is still evidence of
    // how far the fleet's day has got, which is what every other shipment is measured against.
    advanceWatermark(occurredAt);

    if (completed.contains(shipmentId)) {
      // Delivered. Nothing further will ever be reported for it, and that is not a fault.
      return null;
    }

    Instant wallClock = clock.instant();
    // Merged rather than overwritten, and the guard is load-bearing. Three of the four feeds are
    // delayed by design -- an EDI 214 interchange describes something that happened before the
    // batch window that carried it. Overwriting unconditionally would let a message that arrives
    // now, describing an hour ago, move a shipment's last-seen instant *backwards* and make a
    // perfectly healthy truck look as though it had gone quiet.
    //
    // The wall clock still advances either way: a delayed message is real evidence that the
    // shipment exists and is being reported on, whatever instant it describes.
    Sighting previous = lastSeen.get(shipmentId);
    lastSeen.merge(
        shipmentId,
        new Sighting(occurredAt, wallClock, eventId),
        (existing, arriving) ->
            arriving.at().isAfter(existing.at()) ? arriving : existing.heardAgainAt(wallClock));

    if (previous == null || !previous.reportedSilent()) {
      return null;
    }
    return new Clearance(
        ExceptionType.SIGNAL_LOSS,
        shipmentId,
        null,
        occurredAt,
        eventId,
        Clearance.REPORTING_AGAIN);
  }

  /**
   * Stops watching a shipment that has finished.
   *
   * @return a clearance if it was currently reported silent, because a completed shipment's silence
   *     is expected rather than resolved — the resolution says which
   */
  public Clearance completed(String shipmentId, Instant occurredAt, String eventId) {
    if (shipmentId == null) {
      return null;
    }
    completed.add(shipmentId);
    Sighting removed = lastSeen.remove(shipmentId);
    if (removed == null || !removed.reportedSilent()) {
      return null;
    }
    return new Clearance(
        ExceptionType.SIGNAL_LOSS,
        shipmentId,
        null,
        occurredAt,
        eventId,
        Clearance.SHIPMENT_COMPLETED);
  }

  /**
   * Looks for shipments that have gone quiet.
   *
   * <p>Called on a timer. Returns findings rather than raising them, so the rule stays a pure
   * function of what it has been told and the tests need no broker.
   *
   * @param coldChain answers whether a shipment's customer committed to a temperature band, which
   *     is what decides severity — losing sight of a reefer is materially worse than losing sight of
   *     a pallet of dry goods, because the cold chain is now unobserved as well as unmanaged
   */
  public List<Finding> sweep(java.util.function.Predicate<String> coldChain) {
    Instant now = clock.instant();
    Instant currentWatermark = watermark;
    List<Finding> found = new ArrayList<>();

    for (Map.Entry<String, Sighting> entry : lastSeen.entrySet()) {
      Sighting sighting = entry.getValue();

      if (sighting.reportedSilent()) {
        // Already announced, and nothing has been heard since -- a new sighting replaces this
        // object entirely, so a set flag can only mean the silence is the same one.
        continue;
      }

      Duration silentInEventTime = Duration.between(sighting.at(), currentWatermark);
      if (silentInEventTime.compareTo(threshold) < 0) {
        continue;
      }
      Duration silentInRealTime = Duration.between(sighting.wallClock(), now);
      if (silentInRealTime.compareTo(catchUpGrace) < 0) {
        // Seen recently in real terms. Either the consumer is working through a backlog, or this
        // shipment's events are simply arriving with old timestamps -- three of the four feeds are
        // delayed by design. Neither is a device that has gone quiet.
        continue;
      }

      sighting.markReported();
      found.add(
          new Finding(
              ExceptionType.SIGNAL_LOSS,
              entry.getKey(),
              null,
              // The onset is the last thing heard, not the moment the threshold expired. The
              // incident therefore says the silence began when the messages stopped.
              sighting.at(),
              currentWatermark,
              // The last event seen. "Nothing since this" is what the rule actually observed, and
              // the raise event requires a cause -- there is no event to name, only the last one
              // there was.
              sighting.eventId(),
              coldChain.test(entry.getKey()) ? Severity.CRITICAL : Severity.WARNING,
              String.format(
                  "No events of any kind for %d minutes; last heard from at %s.",
                  silentInEventTime.toMinutes(), sighting.at()),
              // Deliberately null, both of them. ExceptionRaised says so in its own documentation:
              // for this rule the evidence is that nothing was measured at all, and inventing a
              // number to fill the field would be describing the silence as a measurement.
              null,
              null));
    }
    return found;
  }

  /** How many shipments are being watched. Reported by the heartbeat. */
  public int watching() {
    return lastSeen.size();
  }

  /** The newest event time seen anywhere. Reported by the heartbeat, and useful when nothing fires. */
  public Instant watermark() {
    return watermark;
  }

  private void advanceWatermark(Instant occurredAt) {
    // Monotonic: an out-of-order event from a backlog must not drag the fleet's clock backwards and
    // silently un-report everyone who had gone quiet.
    Instant current = watermark;
    if (occurredAt.isAfter(current)) {
      watermark = occurredAt;
    }
  }

  /**
   * When a shipment was last heard from, by both clocks, and whether it has been reported silent.
   *
   * <p>The reported flag is what stops the sweep producing a finding for the same silence every ten
   * seconds for the rest of the run. The incident service would suppress the duplicates anyway —
   * that is its job — but it would do so by querying MongoDB once per silent shipment per sweep,
   * which is a real cost for an answer this object already knows.
   */
  private static final class Sighting {
    private final Instant at;
    private final Instant wallClock;
    private final String eventId;
    private volatile boolean reported;

    Sighting(Instant at, Instant wallClock, String eventId) {
      this.at = at;
      this.wallClock = wallClock;
      this.eventId = eventId;
    }

    Instant at() {
      return at;
    }

    Instant wallClock() {
      return wallClock;
    }

    String eventId() {
      return eventId;
    }

    boolean reportedSilent() {
      return reported;
    }

    /**
     * The same sighting, heard about again just now.
     *
     * <p>For a delayed message describing something older than what is already known. The event
     * time does not move, because nothing newer has happened; the wall clock does, because
     * something newer has arrived. The reported flag is dropped, so a shipment announced silent
     * that turns out to be filing delayed paperwork is announced again if it really does stay
     * quiet.
     */
    Sighting heardAgainAt(Instant wallClock) {
      return new Sighting(at, wallClock, eventId);
    }

    void markReported() {
      this.reported = true;
    }
  }
}
