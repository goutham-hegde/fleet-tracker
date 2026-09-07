package com.fleettracking.exceptions;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every threshold the five rules use, in one place.
 *
 * <p>Grouped per rule rather than flattened, because a threshold only means anything against the
 * others in its own group: how long a truck may be stopped and how slow counts as stopped are one
 * decision made twice, and reading them a screen apart is how they drift.
 *
 * <h2>These are all measured in event time</h2>
 *
 * <p>With one exception, called out where it appears. Event time is what the source said happened,
 * as opposed to when this service heard about it, and using it is what makes every rule behave
 * identically whether the simulator is running in real time or at three hundred times speed. A rule
 * that measured "stopped for twenty minutes" against the wall clock would never fire during a
 * fast run, because a simulated four-hour breakdown goes past in under a minute of real time.
 *
 * @param retryBackoff how long to wait before retrying a record that failed for a reason that might
 *     come out differently — MongoDB restarting, most likely. Unbounded attempts, as in the
 *     tracking processor: giving up on an exception is silently deciding not to raise it
 * @param sendTimeout how long to wait for the broker to acknowledge before treating a publish as
 *     failed
 * @param heartbeatInterval how often to log what the service has done. Wall-clock, and purely
 *     operational: the healthy state of this service is near-total silence, which is exactly what a
 *     wedged one looks like
 */
@ConfigurationProperties(prefix = "fleet.exceptions")
public record ExceptionProperties(
    Duration retryBackoff,
    Duration sendTimeout,
    Duration heartbeatInterval,
    Temperature temperature,
    UnplannedStop unplannedStop,
    RouteDeviation routeDeviation,
    LateArrival lateArrival,
    SignalLoss signalLoss) {

  public ExceptionProperties {
    retryBackoff = retryBackoff == null ? Duration.ofSeconds(5) : retryBackoff;
    sendTimeout = sendTimeout == null ? Duration.ofSeconds(15) : sendTimeout;
    heartbeatInterval = heartbeatInterval == null ? Duration.ofSeconds(30) : heartbeatInterval;
    temperature = temperature == null ? new Temperature(null, null) : temperature;
    unplannedStop = unplannedStop == null ? new UnplannedStop(null, null, null, null) : unplannedStop;
    routeDeviation = routeDeviation == null ? new RouteDeviation(null, null, null) : routeDeviation;
    lateArrival = lateArrival == null ? new LateArrival(null) : lateArrival;
    signalLoss = signalLoss == null ? new SignalLoss(null, null) : signalLoss;
  }

  /**
   * When a reefer counts as having broken its cold chain.
   *
   * @param defaultTolerance how long a load may sit outside its band when the customer's manifest
   *     did not say. A stated {@code excursionToleranceMinutes} always wins — this is what applies
   *     when the customer committed to a range but not to a grace period
   * @param setpointToleranceCelsius the band to use when there is <em>no manifest at all</em>: the
   *     unit's own reported setpoint, plus and minus this. A weaker check on purpose, and the
   *     exception it raises is a warning rather than a critical, because the platform is comparing
   *     the equipment against its own dial rather than against anything a customer agreed to
   */
  public record Temperature(Duration defaultTolerance, Double setpointToleranceCelsius) {
    public Temperature {
      defaultTolerance = defaultTolerance == null ? Duration.ofMinutes(20) : defaultTolerance;
      setpointToleranceCelsius =
          setpointToleranceCelsius == null ? 3.0 : setpointToleranceCelsius;
    }
  }

  /**
   * When a stationary truck stops being traffic and starts being a problem.
   *
   * @param movingSpeedKph below this, a vehicle counts as stopped. Not zero: a parked truck's
   *     reported speed wanders around a couple of km/h because its position does, and a threshold of
   *     zero would see it moving
   * @param threshold how long it must be stopped, away from anywhere it is scheduled to be. Long
   *     enough to sit above a fuel stop, a meal break and a queue at a toll plaza
   * @param stopMarginRatio how much wider than a stop's own geofence still counts as "at" that
   *     stop. A truck queueing outside a busy yard's gate is not making an unscheduled stop, and the
   *     geofence radius is drawn for arrival detection rather than for this question
   * @param accuracyGateMeters a fix less accurate than this is not consulted. The same defence the
   *     geofencer uses: a bad fix can put a parked truck half a kilometre from the yard it is
   *     actually sitting in
   */
  public record UnplannedStop(
      Double movingSpeedKph, Duration threshold, Double stopMarginRatio, Double accuracyGateMeters) {
    public UnplannedStop {
      movingSpeedKph = movingSpeedKph == null ? 5.0 : movingSpeedKph;
      threshold = threshold == null ? Duration.ofMinutes(20) : threshold;
      stopMarginRatio = stopMarginRatio == null ? 2.0 : stopMarginRatio;
      accuracyGateMeters = accuracyGateMeters == null ? 150.0 : accuracyGateMeters;
    }
  }

  /**
   * How far off the planned corridor is too far.
   *
   * @param corridorKm the half-width of the corridor around the planned path. Generous, because the
   *     planned path is a straight line between stops and a real road is not: a highway that swings
   *     wide around a range of hills is not a deviation, and this platform has no route geometry to
   *     tell the difference. Tightening it without a routing engine would raise an exception for
   *     every truck that follows the actual road
   * @param threshold how long the position must stay outside before it counts. This is the rule the
   *     enum itself calls the one most easily fooled by a poor fix, and duration is the defence: a
   *     reflected signal puts a truck in the wrong place for one fix, not for a quarter of an hour
   * @param accuracyGateMeters as above
   */
  public record RouteDeviation(Double corridorKm, Duration threshold, Double accuracyGateMeters) {
    public RouteDeviation {
      corridorKm = corridorKm == null ? 12.0 : corridorKm;
      threshold = threshold == null ? Duration.ofMinutes(15) : threshold;
      accuracyGateMeters = accuracyGateMeters == null ? 150.0 : accuracyGateMeters;
    }
  }

  /**
   * How late is late.
   *
   * @param grace how far past a booked window an arrival may be projected before it is worth
   *     telling anyone. Not zero: an estimate that has crept one minute past the deadline will
   *     probably creep back, and raising on it would produce an exception that clears itself before
   *     anybody reads it
   */
  public record LateArrival(Duration grace) {
    public LateArrival {
      grace = grace == null ? Duration.ofMinutes(15) : grace;
    }
  }

  /**
   * When silence becomes an exception.
   *
   * @param threshold how long a shipment may say nothing at all. Measured in event time against the
   *     newest event time the service has seen from anywhere — see {@code SignalLossRule} for why
   *     that watermark is necessary and why the wall clock will not do
   * @param sweepInterval how often to look. The one wall-clock setting in this file, and
   *     unavoidably so: the rule fires on the absence of events, and nothing but a timer can notice
   *     that nothing has happened
   */
  public record SignalLoss(Duration threshold, Duration sweepInterval) {
    public SignalLoss {
      threshold = threshold == null ? Duration.ofMinutes(30) : threshold;
      sweepInterval = sweepInterval == null ? Duration.ofSeconds(10) : sweepInterval;
    }
  }
}
