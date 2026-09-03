package com.fleettracking.tracking.eta;

import com.fleettracking.events.EtaUpdated;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.tracking.geofence.DerivedEventIds;
import com.fleettracking.tracking.geofence.Distance;
import com.fleettracking.tracking.geofence.ShipmentProgress;
import com.fleettracking.tracking.itinerary.ScheduledStop;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Works out when a shipment will reach its next stop, and whether that is worth saying out loud.
 *
 * <h2>The two demands, and how they fight</h2>
 *
 * <p>M3 asks for an estimate that <b>converges on approach</b> and <b>does not thrash on GPS
 * noise</b>. Those pull in opposite directions. An estimate recomputed from the latest fix is
 * perfectly responsive and jitters by minutes while a truck idles at a gate; one smoothed heavily
 * enough to sit still is calm and is still claiming two hours out when the truck is at the fence.
 *
 * <p>The way out is to notice that the two demands are about different quantities. Convergence
 * comes from the <em>distance</em>, which shrinks to nothing as the truck arrives and is measured
 * afresh on every fix. Stability comes from the <em>speed</em>, which is a property of the truck
 * and the road rather than of any one measurement, and is therefore the only thing here that is
 * smoothed. Nothing damps the distance and nothing trusts a single speed reading, and the
 * estimate is calm and still lands on the gate.
 *
 * <h2>Distance is road distance, not the straight line</h2>
 *
 * <p>Roads bend. The simulator drives straight legs but bills its trucks for a road eighteen per
 * cent longer, precisely so that this calculation cannot cheat: an ETA that divided the straight
 * line by the reported speed would be short by that same eighteen per cent on every leg, and would
 * look excellent against a fleet that was cheating in exactly the way it assumed. So the straight
 * line is inflated by a circuity factor before anything is divided by anything.
 *
 * <p>That factor is a stated assumption of this platform, not knowledge. It is where a real
 * deployment would put a routing engine, and it is configurable for that reason.
 *
 * <h2>Speed is learned from movement only</h2>
 *
 * <p>The tempting model is a running average of every reported speed, and it is wrong. A truck
 * stopped at a light reports zero, which drags the average down, which inflates the estimate — so a
 * five-minute halt would add an hour to a three-hour ETA and take another twenty minutes to work
 * back out. That is thrash with a respectable-looking mechanism behind it.
 *
 * <p>What is wanted is the truck's <em>travel</em> speed: how fast it covers ground when it is
 * covering ground. Stops need no representation in that number at all, because they represent
 * themselves — the estimate is anchored to the instant of the fix, so a truck that stands still for
 * half an hour has its arrival slip by exactly half an hour, which is the truth. Fixes below a
 * walking pace are therefore not sampled; they only cost confidence.
 *
 * <h2>The smoothing is measured in time, not in fixes</h2>
 *
 * <p>Each new sample is blended in with a weight derived from how much event time has passed since
 * the last one, so the average has a half-life in minutes rather than in messages. This matters
 * because the two feeds that carry positions are nothing alike: telematics reports every ten
 * seconds and a driver's phone every couple of minutes. A fixed blend weight would make the same
 * setting mean two very different things — six fixes of history for one feed is a minute, and for
 * the other a quarter of an hour. With a half-life, both settle at the same rate on the clock,
 * which is the rate anybody reasoning about the behaviour actually has in mind.
 *
 * <h2>Silence is the normal output</h2>
 *
 * <p>Every fix advances the model; only a fix whose estimate has moved further than the publish
 * threshold produces an event. A truck holding its pace on a long leg therefore says nothing for
 * hours, which is correct — nothing has changed — and the events that do appear are all of them
 * news.
 */
public class EtaCalculator {

  /**
   * The speed below which a fix is treated as the vehicle not travelling, in km/h.
   *
   * <p>Five, which is a walking pace. Above a stationary truck's GPS jitter, and far below anything
   * that counts as making progress — a truck creeping through a yard at 5 km/h would take eleven
   * hours to cover a leg and is not doing the thing this is trying to measure.
   */
  public static final double MOVING_KPH = 5.0;

  /**
   * How much observed movement counts as a fully warmed-up speed estimate, in seconds.
   *
   * <p>A quarter of an hour of driving. Below that, the estimate is still substantially the nominal
   * speed it was seeded with, and the confidence on the event says so rather than the estimate
   * quietly presenting an assumption as a measurement.
   */
  public static final double WARM_SECONDS = 900;

  /** The floor a cold model's warmth contributes. A nominal speed is a weak answer, not no answer. */
  public static final double COLD_WARMTH = 0.2;

  /** The gap between fixes at which the estimate has lost most of its claim to being current. */
  public static final double FRESHNESS_SECONDS = 900;

  /** How long a truck must be stationary for the estimate to read as clearly provisional. */
  public static final double STILLNESS_SECONDS = 1800;

  /** The reported accuracy at which a fix contributes nothing to confidence, in metres. */
  public static final double ACCURACY_METERS_FLOOR = 500;

  /** Confidence never reaches zero: the platform is guessing, not refusing to answer. */
  public static final double MIN_CONFIDENCE = 0.05;

  private final Duration publishThreshold;
  private final double speedHalfLifeSeconds;
  private final double nominalSpeedKph;
  private final double roadCircuity;

  public EtaCalculator(
      Duration publishThreshold,
      Duration speedHalfLife,
      double nominalSpeedKph,
      double roadCircuity) {
    if (publishThreshold == null || publishThreshold.isNegative()) {
      throw new IllegalArgumentException("publishThreshold must not be negative: " + publishThreshold);
    }
    if (speedHalfLife == null || speedHalfLife.isNegative() || speedHalfLife.isZero()) {
      throw new IllegalArgumentException("speedHalfLife must be positive: " + speedHalfLife);
    }
    if (nominalSpeedKph <= 0) {
      throw new IllegalArgumentException("nominalSpeedKph must be positive: " + nominalSpeedKph);
    }
    if (roadCircuity < 1.0) {
      throw new IllegalArgumentException("roadCircuity must be at least 1: " + roadCircuity);
    }
    this.publishThreshold = publishThreshold;
    this.speedHalfLifeSeconds = speedHalfLife.toMillis() / 1000.0;
    this.nominalSpeedKph = nominalSpeedKph;
    this.roadCircuity = roadCircuity;
  }

  /**
   * Applies one position fix to one shipment's estimate.
   *
   * @param state what is currently believed and what has been learned. Never null
   * @param progress how far through its plan the shipment is, as of this fix
   * @param event a position event that has already been validated and stored
   * @return the advanced model, and an event if the estimate moved far enough to be worth saying
   */
  public EtaDecision evaluate(EtaState state, ShipmentProgress progress, PositionEvent event) {
    Instant at = event.occurredAt();

    // Out of order, or a repeat. Same rule as the geofencer's, for the same reason: the mobile app
    // dumps a buffered backlog in whatever order it comes out, and feeding a twenty-minute-old fix
    // into a smoothed average would drag the estimate backwards towards a speed the truck held a
    // long way back. The measurement is still in the history; it simply gets no vote here.
    if (state.lastFixAt() != null && !at.isAfter(state.lastFixAt())) {
      return EtaDecision.quiet(state);
    }

    EtaState advanced = learn(state, event, at);

    // At a stop. The plan carries no service times, so the platform does not know how long this
    // truck will be here, and an estimate for the next stop would be a real driving time added to
    // an invented wait. Better to say nothing until the departure, which is the first moment the
    // question has an honest answer. The model still advanced above: the fixes taken in a yard are
    // stationary ones and teach it nothing, but the clock has moved and it should know that.
    if (progress.atAStop()) {
      return EtaDecision.quiet(advanced);
    }

    Optional<ScheduledStop> target = progress.nextStop();
    if (target.isEmpty()) {
      // Every stop on the plan has been arrived at. A finished shipment; the last estimate stands.
      return EtaDecision.quiet(advanced);
    }
    ScheduledStop stop = target.get();

    double remainingKm =
        Distance.metersBetween(event.position(), stop.location()) / 1000.0 * roadCircuity;
    double speedKph = Math.max(MOVING_KPH, advanced.hasSpeed() ? advanced.expectedSpeedKph() : nominalSpeedKph);
    Instant estimate = at.plusSeconds(Math.round(remainingKm / speedKph * 3600));

    // A new leg. The estimate is for a different stop than the last one published, so there is
    // nothing to compare it against and nothing to suppress: this is the first word on it.
    boolean newLeg = !stop.stopId().equals(state.stopId()) || !state.hasEstimate();
    Instant previous = newLeg ? null : state.estimatedArrival();

    if (!newLeg && !movedEnough(previous, estimate)) {
      // The estimate stands. Note what is returned: the advanced model, carrying everything this
      // fix taught, with the published estimate untouched. Staying quiet must not also mean
      // forgetting, or a long steady leg would arrive at its end knowing nothing about the truck.
      return EtaDecision.quiet(advanced);
    }

    // The gap is measured against the state as it was BEFORE this fix was folded in. Taken from
    // the advanced state it would always be zero, because that state's newest fix is this one --
    // a silent way for one of the four confidence terms to stop contributing anything at all.
    double confidence = confidence(advanced, event, at, gapSeconds(state, at));

    EtaUpdated update =
        new EtaUpdated(
            // Derived from the fix that caused it, not from the estimate it states. Two consecutive
            // fixes can legitimately produce the same arrival time and are still two different
            // statements; and a replayed fix must regenerate the id it had the first time, which is
            // what makes it safe to publish before recording. Nothing from the wall clock appears.
            DerivedEventIds.eta(event.shipmentId(), stop.stopId(), event.eventId()),
            event.shipmentId(),
            at,
            event.eventId(),
            stop.stopId(),
            estimate,
            previous,
            round(remainingKm, 3),
            confidence);

    EtaState next =
        new EtaState(
            advanced.shipmentId(),
            stop.stopId(),
            estimate,
            round(remainingKm, 3),
            confidence,
            advanced.expectedSpeedKph(),
            advanced.movingSeconds(),
            advanced.lastMovingAt(),
            advanced.lastFixAt(),
            advanced.updatedAt());

    return new EtaDecision(next, update);
  }

  /**
   * Folds one fix into the learned travel speed.
   *
   * <p>The blend weight comes from elapsed event time: a sample arriving one half-life after the
   * last one is worth half the average, and one arriving moments later barely disturbs it. That is
   * what makes the same configured half-life mean the same thing whether the fixes are ten seconds
   * apart or two minutes.
   */
  private EtaState learn(EtaState state, PositionEvent event, Instant at) {
    double elapsedSeconds = gapSeconds(state, at);

    Double reported = event.speedKph();
    boolean moving = reported != null && reported >= MOVING_KPH;

    double speed = state.expectedSpeedKph();
    long movingSeconds = state.movingSeconds();
    Instant lastMovingAt = state.lastMovingAt();

    if (moving) {
      if (!state.hasSpeed()) {
        // Nothing learned yet, so the first sighting of a moving truck is the whole estimate.
        // Blending it into a zero would spend the first several minutes of every shipment claiming
        // a speed no vehicle has ever travelled at.
        speed = reported;
      } else {
        double weight = 1 - Math.pow(2, -elapsedSeconds / speedHalfLifeSeconds);
        speed = speed + weight * (reported - speed);
      }
      movingSeconds = (long) Math.min(WARM_SECONDS, movingSeconds + elapsedSeconds);
      lastMovingAt = at;
    }

    return new EtaState(
        state.shipmentId(),
        state.stopId(),
        state.estimatedArrival(),
        state.remainingKm(),
        state.confidence(),
        speed,
        movingSeconds,
        lastMovingAt,
        at,
        state.updatedAt());
  }

  /**
   * How much the platform trusts this estimate, from zero to one.
   *
   * <p>Four independent doubts, multiplied. Each one is a way the estimate could be wrong that the
   * arithmetic itself cannot see, and multiplying rather than averaging means any one of them being
   * bad is enough to make the answer provisional — which is the correct behaviour, since they are
   * not alternatives.
   *
   * <ul>
   *   <li><b>Warmth.</b> How much movement the speed has been learned from. A shipment whose first
   *       fix this is, is being estimated against a nominal speed rather than a measured one.
   *   <li><b>Freshness.</b> How long since the previous fix. A phone that has been dark for twenty
   *       minutes leaves an estimate built on where the truck used to be.
   *   <li><b>Stillness.</b> How long since the truck was last seen moving. A stationary truck's
   *       arrival time depends entirely on when it starts again, which nothing here knows.
   *   <li><b>Accuracy.</b> What the fix says about itself. The same field the geofencer gates on,
   *       used more gently: a poor fix still tells you roughly where a truck is on a long leg.
   * </ul>
   */
  private double confidence(
      EtaState state, PositionEvent event, Instant at, double gapSeconds) {
    double warmth = COLD_WARMTH + (1 - COLD_WARMTH) * Math.min(1, state.movingSeconds() / WARM_SECONDS);

    double freshness = Math.exp(-gapSeconds / FRESHNESS_SECONDS);

    double stillSeconds =
        state.lastMovingAt() == null
            ? 0
            : Math.max(0, Duration.between(state.lastMovingAt(), at).toMillis() / 1000.0);
    double stillness = Math.exp(-stillSeconds / STILLNESS_SECONDS);

    double accuracy =
        event.accuracyMeters() == null
            ? 1.0
            : Math.max(0, 1 - event.accuracyMeters() / ACCURACY_METERS_FLOOR);

    double product = warmth * freshness * stillness * accuracy;
    return round(Math.min(1.0, Math.max(MIN_CONFIDENCE, product)), 2);
  }

  /** Event time since the previous fix, or zero for the first one this shipment has ever had. */
  private static double gapSeconds(EtaState before, Instant at) {
    return before.lastFixAt() == null
        ? 0
        : Duration.between(before.lastFixAt(), at).toMillis() / 1000.0;
  }

  /** Whether the new estimate differs from the published one by more than the threshold. */
  private boolean movedEnough(Instant previous, Instant estimate) {
    return Duration.between(previous, estimate).abs().compareTo(publishThreshold) >= 0;
  }

  private static double round(double value, int places) {
    double factor = Math.pow(10, places);
    return Math.round(value * factor) / factor;
  }
}
