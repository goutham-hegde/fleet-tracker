package com.fleettracking.exceptions.rule;

import com.fleettracking.events.ExceptionType;
import com.fleettracking.events.Severity;
import com.fleettracking.events.StatusCode;
import com.fleettracking.events.StatusEvent;
import com.fleettracking.events.TemperatureReading;
import com.fleettracking.exceptions.ExceptionProperties;
import com.fleettracking.exceptions.incident.Finding;
import com.fleettracking.exceptions.manifest.SlaTerms;
import com.fleettracking.exceptions.manifest.TemperatureBand;
import java.time.Duration;
import java.util.Optional;

/**
 * The cold chain: has this load been outside the range it was promised, for long enough to matter?
 *
 * <h2>The band comes from the customer, not from the equipment</h2>
 *
 * <p>Every reefer reading carries two numbers, and it is tempting to use both: the measured
 * temperature and the setpoint the unit was told to hold. Comparing them is easy, needs no manifest,
 * and answers the wrong question. The setpoint is what somebody dialled into a machine. The band is
 * what a customer agreed their freight would experience. A unit set to 4°C carrying a load
 * contracted at 2–8°C has four degrees of headroom either side, and a drift to 6.5°C is a
 * refrigeration unit doing its job imperfectly — not a breach of anything.
 *
 * <p>So the band is read from the manifest, and the reading's own setpoint is a fallback for loads
 * with no manifest on file. That fallback deliberately produces a weaker exception:
 *
 * <ul>
 *   <li><b>With a manifest band</b> the platform knows a customer's commitment has been broken.
 *       {@link Severity#CRITICAL} — for a pharma consignment this is destroyed freight, not late
 *       freight.
 *   <li><b>Without one</b> the platform only knows the equipment is not doing what it was told.
 *       {@link Severity#WARNING}, because nobody has said what this load can tolerate.
 * </ul>
 *
 * <p>Severity therefore falls out of what is known rather than being a table of magic numbers — the
 * property {@code Severity} itself asks for: a property of the incident, decided with the shipment
 * in hand.
 *
 * <h2>Sustained, because a door is not a failure</h2>
 *
 * <p>The simulator opens the trailer doors while a truck is on a dock, so warm readings cluster
 * around every dwell. That is realistic and it is exactly the noise this rule has to survive: a
 * loading bay warms the box for a few minutes on every stop, and a system that alerted on each one
 * would be muted within a day.
 *
 * <p>The tolerance separates them. A customer who stated {@code excursionToleranceMinutes} in their
 * manifest gets their own number — MediVault's schema carries it, and it is the customer's
 * judgement about their own product, not this platform's. Otherwise a default applies. Either way,
 * the incident is stamped with the <em>first</em> out-of-band reading, so the exception reports the
 * excursion as starting when the temperature actually left the band.
 *
 * <h2>What this rule cannot see</h2>
 *
 * <p>A reefer reading carries no position and no door state on the canonical envelope, so this rule
 * cannot tell a warm box on a dock from a warm box on a motorway. The distinction is real and
 * matters — the first is loading, the second is a failing unit — and the only defence available
 * here is the clock. Carrying door state through the normalizer would let a future version tell them
 * apart directly, and would be the single highest-value addition to this rule.
 */
public class TemperatureExcursionRule {

  private final Duration defaultTolerance;
  private final double setpointToleranceCelsius;

  public TemperatureExcursionRule(ExceptionProperties.Temperature settings) {
    this.defaultTolerance = settings.defaultTolerance();
    this.setpointToleranceCelsius = settings.setpointToleranceCelsius();
  }

  /**
   * Applies one status event.
   *
   * @param terms what the customer committed to, or null if no manifest is on file
   */
  public RuleOutcome evaluate(ConditionState state, StatusEvent event, SlaTerms terms) {
    if (event.status() != StatusCode.TEMPERATURE_READING) {
      // Every other status is somebody else's business. Returning the same state instance means
      // nothing is written for the overwhelming majority of events on this topic.
      return RuleOutcome.nothing(state);
    }
    TemperatureReading reading = event.temperature();
    if (reading == null || reading.celsius() == null) {
      return RuleOutcome.nothing(state);
    }
    if (!state.isNewerThanApplied(event.occurredAt())) {
      // An out-of-order reading. Refused rather than absorbed, for the reason the geofencer refuses
      // an old fix: it would walk the excursion clock backwards, and a backlog dumped after a
      // connectivity gap arrives in whatever order it feels like.
      return RuleOutcome.nothing(state);
    }

    Optional<Judgement> judgement = judgementFor(terms, reading);
    if (judgement.isEmpty()) {
      // No band from a manifest and no setpoint on the reading. There is nothing to compare
      // against, and a rule with no threshold must stay quiet rather than invent one.
      return RuleOutcome.nothing(state);
    }

    Judgement judged = judgement.get();
    double celsius = reading.celsius();

    if (judged.band().contains(celsius)) {
      if (!state.holding()) {
        return RuleOutcome.nothing(state.quiet(event.occurredAt()));
      }
      // Back inside the band. Cleared on the first good reading rather than after a recovery
      // period: the tolerance exists to avoid crying wolf about a breach, and there is no matching
      // reason to be slow about announcing that a load is safe again.
      return RuleOutcome.clear(
          state.ending(event.occurredAt()),
          new Clearance(
              ExceptionType.TEMPERATURE_EXCURSION,
              event.shipmentId(),
              null,
              event.occurredAt(),
              event.eventId(),
              Clearance.RECOVERED));
    }

    double excess = judged.band().excess(celsius);
    ConditionState updated =
        state.holding()
            ? state.continuing(event.occurredAt(), excess)
            : state.beginning(event.occurredAt(), excess, null, null);

    Duration held = updated.heldFor(event.occurredAt());
    if (held.compareTo(judged.tolerance()) < 0) {
      // Outside the band but not yet for long enough. The clock is now running and is durable, so
      // a restart before the tolerance expires does not restart it.
      return RuleOutcome.nothing(updated);
    }

    return RuleOutcome.raise(
        updated,
        new Finding(
            ExceptionType.TEMPERATURE_EXCURSION,
            event.shipmentId(),
            null,
            updated.since(),
            event.occurredAt(),
            event.eventId(),
            judged.severity(),
            detail(celsius, judged, updated.worst(), held),
            celsius,
            judged.band().breachedBound(celsius).orElse(null)));
  }

  /**
   * Which band to hold this load to, and how seriously to take a breach of it.
   *
   * <p>The customer's commitment wins whenever there is one. The setpoint fallback exists so that a
   * load whose paperwork has not arrived is not completely unmonitored, and it is honest about being
   * a weaker check.
   */
  private Optional<Judgement> judgementFor(SlaTerms terms, TemperatureReading reading) {
    if (terms != null && terms.temperature().isPresent()) {
      TemperatureBand band = terms.temperature().get();
      return Optional.of(
          new Judgement(
              band,
              band.statedTolerance().orElse(defaultTolerance),
              Severity.CRITICAL,
              true));
    }
    if (reading.setpointCelsius() == null) {
      return Optional.empty();
    }
    double setpoint = reading.setpointCelsius();
    return Optional.of(
        new Judgement(
            new TemperatureBand(
                setpoint - setpointToleranceCelsius, setpoint + setpointToleranceCelsius, null),
            defaultTolerance,
            Severity.WARNING,
            false));
  }

  /**
   * The sentence that goes on the event.
   *
   * <p>It reports the worst reading since the onset rather than the current one, because the
   * current one is whichever reading happened to tip the tolerance over and says nothing about how
   * bad it got. It also names the source of the band: "the 2.0-8.0C band" and "its 4.0C setpoint"
   * are different claims, and somebody deciding whether to reject a consignment needs to know which
   * one they are looking at.
   */
  private static String detail(
      double celsius, Judgement judged, Double worst, Duration held) {
    String against =
        judged.fromManifest()
            ? "the agreed " + format(judged.band().minC()) + " to " + format(judged.band().maxC()) + "C band"
            : "its reported setpoint";
    String worstPart =
        worst == null ? "" : ", peaking at " + format(worst) + "C outside it";
    return format(celsius)
        + "C is outside "
        + against
        + worstPart
        + ", and has been for "
        + held.toMinutes()
        + " minutes";
  }

  private static String format(double value) {
    return String.format("%.1f", value);
  }

  /** A band, how long a load may sit outside it, and what a breach means. */
  private record Judgement(
      TemperatureBand band, Duration tolerance, Severity severity, boolean fromManifest) {}
}
