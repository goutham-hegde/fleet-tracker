package com.fleettracking.simulator.fault;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How often things go wrong with the trucks, and for how long.
 *
 * <p>Separate from {@link FaultProperties} because the two answer different questions — see
 * {@link Disruption} for why a broken reefer and a corrupted payload do not belong in the same
 * settings block. Keeping them apart also means a run can be adversarial about the network and
 * honest about the fleet, or the reverse, which is exactly what is wanted when demonstrating that
 * one particular rule fires.
 *
 * <p>Every probability defaults to zero. A default run has a healthy fleet, and every disruption
 * has to be asked for:
 *
 * <pre>{@code
 * --fleet.simulator.disruptions.breakdown-probability=0.02
 * --spring.profiles.active=disrupted   # all five, at rates that show up in a short run
 * }</pre>
 *
 * <h2>Per truck per hour of simulated time, not per tick</h2>
 *
 * <p>This is the one thing about these settings that is easy to get wrong. A per-tick probability
 * would mean the fault rate depended on {@code tick-interval} and {@code time-scale} — turning the
 * tick rate up to run a demo faster would break down every truck in the fleet within seconds, and
 * the same configuration would produce wildly different runs. Expressing it per hour of simulated
 * time makes a disruption something that happens to a truck on a journey, which is what it is.
 *
 * @param enabled master switch, so a single flag produces a run with a perfectly healthy fleet
 * @param breakdownProbability chance per truck per simulated hour that it stops dead
 * @param breakdownDuration how long a breakdown lasts. Comfortably longer than the exception
 *     service's twenty-minute threshold, or the fault would come and go without ever being seen
 * @param slowdownProbability chance per truck per simulated hour that it slows to a crawl
 * @param slowdownDuration how long the slow patch lasts. Long enough for the ETA to converge on the
 *     new speed and slide past a delivery window — the estimate smooths over a five-minute
 *     half-life, so anything under about twenty minutes barely moves it
 * @param slowdownSpeedRatio what fraction of its cruise speed the truck manages while slowed
 * @param detourProbability chance per truck per simulated hour of leaving the planned line
 * @param detourDuration how long before it turns back
 * @param detourBearingOffsetDegrees how sharply it turns away. Forty-five degrees for half an hour
 *     at highway speed puts a truck a long way outside a twelve-kilometre corridor
 * @param reeferFailureProbability chance per <em>refrigerated</em> truck per simulated hour that
 *     the unit stops holding temperature. Applied nowhere else, because a dry van has nothing to
 *     fail
 * @param reeferFailureDuration how long before it recovers. Must exceed the excursion tolerance on
 *     the manifest — MediVault's is thirty minutes — or the load warms and cools without ever
 *     breaching
 * @param blackoutProbability chance per truck per simulated hour that its device stops transmitting
 * @param blackoutDuration how long the silence lasts. Must exceed the signal-loss threshold, and
 *     the threshold is measured in event time, so this is directly comparable to it
 */
@ConfigurationProperties(prefix = "fleet.simulator.disruptions")
public record DisruptionProperties(
    Boolean enabled,
    double breakdownProbability,
    Duration breakdownDuration,
    double slowdownProbability,
    Duration slowdownDuration,
    double slowdownSpeedRatio,
    double detourProbability,
    Duration detourDuration,
    double detourBearingOffsetDegrees,
    double reeferFailureProbability,
    Duration reeferFailureDuration,
    double blackoutProbability,
    Duration blackoutDuration) {

  public DisruptionProperties {
    enabled = enabled == null || enabled;
    breakdownDuration = orDefault(breakdownDuration, Duration.ofMinutes(75));
    slowdownDuration = orDefault(slowdownDuration, Duration.ofMinutes(90));
    slowdownSpeedRatio = slowdownSpeedRatio <= 0 || slowdownSpeedRatio >= 1 ? 0.25 : slowdownSpeedRatio;
    detourDuration = orDefault(detourDuration, Duration.ofMinutes(40));
    detourBearingOffsetDegrees =
        detourBearingOffsetDegrees == 0 ? 45.0 : detourBearingOffsetDegrees;
    reeferFailureDuration = orDefault(reeferFailureDuration, Duration.ofMinutes(90));
    blackoutDuration = orDefault(blackoutDuration, Duration.ofMinutes(60));
  }

  private static Duration orDefault(Duration value, Duration fallback) {
    return value == null || value.isZero() || value.isNegative() ? fallback : value;
  }

  /** A healthy fleet: nothing goes wrong with any truck. */
  public static DisruptionProperties none() {
    return new DisruptionProperties(false, 0, null, 0, null, 0, 0, null, 0, 0, null, 0, null);
  }

  /** True if anything at all can go wrong, which is worth logging at startup. */
  public boolean anyActive() {
    return enabled
        && (breakdownProbability > 0
            || slowdownProbability > 0
            || detourProbability > 0
            || reeferFailureProbability > 0
            || blackoutProbability > 0);
  }

  /** The chance and the duration for one kind, so the scheduler needs no switch of its own. */
  public double probabilityOf(Disruption.Kind kind) {
    return switch (kind) {
      case BREAKDOWN -> breakdownProbability;
      case SLOWDOWN -> slowdownProbability;
      case DETOUR -> detourProbability;
      case REEFER_FAILURE -> reeferFailureProbability;
      case BLACKOUT -> blackoutProbability;
    };
  }

  /** How long one kind lasts. */
  public Duration durationOf(Disruption.Kind kind) {
    return switch (kind) {
      case BREAKDOWN -> breakdownDuration;
      case SLOWDOWN -> slowdownDuration;
      case DETOUR -> detourDuration;
      case REEFER_FAILURE -> reeferFailureDuration;
      case BLACKOUT -> blackoutDuration;
    };
  }
}
