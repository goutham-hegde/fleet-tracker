package com.fleettracking.simulator.fleet;

import com.fleettracking.events.GeoPoint;
import com.fleettracking.simulator.route.Geo;
import com.fleettracking.simulator.route.Leg;
import com.fleettracking.simulator.route.Route;
import com.fleettracking.simulator.route.Stop;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * One truck running one route: the mutable, moving part of the simulation.
 *
 * <p>Advanced by repeated calls to {@link #tick(Instant, Duration)}, each of which moves it a small
 * step and reports what happened. Not thread-safe, and deliberately so — a truck is stepped by
 * exactly one loop.
 *
 * <h2>How it moves</h2>
 *
 * <p>Each tick, the truck picks a target speed, moves its current speed toward that target within
 * its acceleration and braking limits, and then travels at the resulting speed for the length of
 * the tick. It re-aims at its destination stop every single tick rather than setting a course once,
 * which is what keeps it on the great circle (see {@link Geo#initialBearingDegrees}) and what makes
 * S5's injected GPS noise self-correcting instead of cumulative.
 *
 * <h2>The straight-line / road-distance split</h2>
 *
 * <p>The truck's <em>path</em> is the straight great-circle line between stops, but its
 * <em>speed and odometer</em> are billed against the longer real road (see
 * {@link Route#ROAD_CIRCUITY}). So a truck cruising at a genuine 100 km/h advances along the
 * simplified path at only about 85 km/h of projected progress. The consequence is that a leg takes
 * as long as it really would, and the speed on the wire is a speed a truck really drives — only the
 * shape of the path is simplified. Had the truck instead driven the short line at full speed, every
 * ETA the platform computes in M3 would look flawless, because the trucks would be cheating in
 * precisely the way a naive ETA assumes they can.
 *
 * <h2>Braking</h2>
 *
 * <p>Approaching a stop, the fastest speed from which the truck can still pull up in the distance
 * remaining is {@code sqrt(2 * deceleration * distance)} — the constant-deceleration result from
 * {@code v² = u² + 2as} with a final speed of zero. Taking the smaller of that and the driver's
 * desired speed produces a smooth, automatic deceleration curve that starts of its own accord about
 * 480 m out at 100 km/h, with no explicit "am I nearly there" branch anywhere.
 */
public final class Truck {

  /**
   * How close to a stop's coordinates counts as having pulled up, in metres.
   *
   * <p>Not the stop's geofence radius, which is a much larger and separate idea: the geofence is
   * what M3 has to <em>infer</em> arrival from, hundreds of metres wide. This is just the numerical
   * tolerance at which the simulator stops stepping and snaps to the stop, because a truck
   * decelerating asymptotically would otherwise creep toward its destination forever.
   */
  static final double ARRIVAL_TOLERANCE_METERS = 2.0;

  /**
   * The slowest a truck moves while it still has ground to cover, in m/s — about 3.6 km/h, a
   * walking pace.
   *
   * <p>Without a floor the braking curve asymptotes: the target speed goes to zero as the distance
   * does, so the truck would ease toward the stop in ever smaller steps and never actually reach
   * it. A crawl speed is both the fix and what really happens — the last few metres onto a dock are
   * done at walking pace, not at cruise.
   */
  static final double CRAWL_SPEED_MPS = 1.0;

  private final String vehicleId;
  private final String shipmentId;
  private final String deviceId;
  private final Route route;
  private final DriverProfile profile;
  private final RandomGenerator random;
  private final double setPointCelsius;

  private TruckPhase phase;

  /**
   * Doubles as both cursors, and the phase says which: while {@link TruckPhase#DRIVING} it is the
   * index of the leg being driven, and while {@link TruckPhase#DWELLING} it is the index of the
   * stop being sat at. They line up because leg <i>n</i> runs from stop <i>n</i> to stop <i>n+1</i>.
   */
  private int cursor;

  private GeoPoint position;
  private double speedMps;
  private double headingDegrees;
  private double odometerMeters;
  private Duration dwellRemaining;
  private double speedNoiseMps;
  private double temperatureCelsius;

  /**
   * Creates a truck standing at its route's origin, already dwelling — which is to say waiting to
   * be loaded. It starts moving once that first stop's dwell elapses, so a run naturally begins
   * with a pickup rather than with a truck materialising at speed.
   *
   * @param startingOdometerKm lifetime distance already on the vehicle. Non-zero on purpose: a
   *     brand-new truck reading exactly 0.0 is the kind of tidy value that hides an off-by-one in
   *     anything downstream that diffs consecutive odometer readings
   */
  public Truck(
      String vehicleId,
      String shipmentId,
      String deviceId,
      Route route,
      DriverProfile profile,
      double startingOdometerKm,
      double setPointCelsius,
      RandomGenerator random) {
    this.vehicleId = Objects.requireNonNull(vehicleId, "vehicleId");
    this.shipmentId = Objects.requireNonNull(shipmentId, "shipmentId");
    this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
    this.route = Objects.requireNonNull(route, "route");
    this.profile = Objects.requireNonNull(profile, "profile");
    this.random = Objects.requireNonNull(random, "random");
    this.setPointCelsius = setPointCelsius;

    this.phase = TruckPhase.DWELLING;
    this.cursor = 0;
    this.position = route.origin().location();
    this.speedMps = 0;
    this.odometerMeters = startingOdometerKm * 1000;
    this.dwellRemaining = route.origin().dwell();
    this.temperatureCelsius = setPointCelsius;
    // Face the first leg from the outset, so the truck is not reported pointing due north while
    // it sits in the yard.
    this.headingDegrees = route.legs().getFirst().bearingFrom(position);
  }

  /**
   * Advances the truck by one time step.
   *
   * @param now simulated wall-clock time at the <em>end</em> of this step
   * @param delta how much simulated time this step covers
   * @return what happened during the step: always a snapshot, plus any discrete transitions
   */
  public TickResult tick(Instant now, Duration delta) {
    double seconds = delta.toNanos() / 1_000_000_000.0;
    if (seconds <= 0) {
      throw new IllegalArgumentException("tick delta must be positive: " + delta);
    }

    List<TruckTransition> transitions = new ArrayList<>(2);

    switch (phase) {
      case DWELLING -> dwell(now, delta, transitions);
      case DRIVING -> drive(now, seconds, transitions);
      case COMPLETED -> {
        // Terminal. A completed truck holds its final position and reports nothing further.
      }
    }

    driftTemperature(seconds);
    return new TickResult(snapshot(now), List.copyOf(transitions));
  }

  /** Counts down the dwell at the current stop and pulls away when it runs out. */
  private void dwell(Instant now, Duration delta, List<TruckTransition> transitions) {
    dwellRemaining = dwellRemaining.minus(delta);
    if (dwellRemaining.isPositive()) {
      return;
    }
    dwellRemaining = Duration.ZERO;

    Stop stop = route.stops().get(cursor);
    if (cursor >= route.legs().size()) {
      // Dwell finished at the final stop: there is no leg to depart onto.
      phase = TruckPhase.COMPLETED;
      transitions.add(new TruckTransition.RouteCompleted(vehicleId, shipmentId, stop, now));
      return;
    }

    phase = TruckPhase.DRIVING;
    transitions.add(new TruckTransition.Departed(vehicleId, shipmentId, stop, now));
  }

  /** Moves the truck along its current leg for one step. */
  private void drive(Instant now, double seconds, List<TruckTransition> transitions) {
    Leg leg = route.legs().get(cursor);

    // Distances the truck experiences are road distances; the path it walks is the short line.
    double remainingStraight = leg.remainingMeters(position);
    double remainingRoad = remainingStraight * Route.ROAD_CIRCUITY;

    speedMps = nextSpeed(seconds, remainingRoad);
    headingDegrees = leg.bearingFrom(position);

    double roadStep = speedMps * seconds;
    double straightStep = roadStep / Route.ROAD_CIRCUITY;

    // Close enough to call it, or this step would carry the truck past the stop.
    if (remainingStraight <= ARRIVAL_TOLERANCE_METERS || straightStep >= remainingStraight) {
      arrive(now, leg, remainingRoad, transitions);
      return;
    }

    position = Geo.destination(position, headingDegrees, straightStep);
    odometerMeters += roadStep;
  }

  /** Pulls up at the leg's destination stop and begins its dwell. */
  private void arrive(Instant now, Leg leg, double remainingRoad, List<TruckTransition> transitions) {
    position = leg.to().location();
    // Bill the remaining road distance rather than the step that overshot it, so the odometer
    // matches the route's road total exactly rather than drifting a few metres per stop.
    odometerMeters += remainingRoad;
    speedMps = 0;

    cursor++;
    Stop arrivedAt = route.stops().get(cursor);
    phase = TruckPhase.DWELLING;
    dwellRemaining = arrivedAt.dwell();
    transitions.add(new TruckTransition.Arrived(vehicleId, shipmentId, arrivedAt, now));
  }

  /**
   * Chooses this step's speed: aim for cruise plus a slow wander, cap that by what the truck can
   * still stop from, then move toward it within the acceleration and braking limits.
   */
  private double nextSpeed(double seconds, double remainingRoadMeters) {
    double desired = profile.cruiseSpeedMps() + wanderSpeedNoise(seconds);

    // v² = u² + 2as with a final speed of zero: the fastest the truck may be going and still pull
    // up in the distance left. Below this line no explicit braking logic is needed anywhere.
    //
    // The distance used is what will be left at the *end* of this step, not what is left now.
    // That one-tick lookahead is load-bearing rather than a refinement: braking against the
    // current distance lets the truck cross the curve during the very step that discovers it,
    // and once it is above the curve it cannot get back — the excess speed consumes extra
    // ground, which lowers the curve again. Measured, that error compounds into arriving at a
    // dock at 21 km/h after thirty seconds of full braking. Looking one step ahead means the
    // truck starts braking a tick early and tracks the curve to within a centimetre per second.
    double afterThisStep = remainingRoadMeters - speedMps * seconds;
    double stoppable = Math.sqrt(2 * profile.decelerationMps2() * Math.max(0, afterThisStep));
    double target = Math.max(CRAWL_SPEED_MPS, Math.min(desired, stoppable));

    if (speedMps < target) {
      return Math.min(target, speedMps + profile.accelerationMps2() * seconds);
    }
    return Math.max(target, speedMps - profile.decelerationMps2() * seconds);
  }

  /**
   * A mean-reverting random walk (Ornstein-Uhlenbeck) around zero, used as the traffic-and-gradient
   * wobble on cruise speed.
   *
   * <p>Independent random draws per tick would be wrong twice over: at one draw per second the
   * speed would buzz between values no real vehicle passes through, and the average over any window
   * would be suspiciously exactly the cruise speed. A mean-reverting walk instead holds a value for
   * a while and then wanders back — a truck stuck behind someone slow for two minutes, not a truck
   * having a seizure. M3's ETA smoothing has to cope with the former; the latter it could trivially
   * average away, which would make the ETA look better than it deserves.
   */
  private double wanderSpeedNoise(double seconds) {
    double tau = profile.speedVariationPeriod().toNanos() / 1_000_000_000.0;
    double reversion = Math.min(1.0, seconds / tau);
    double sigma = profile.speedVariationMps();
    speedNoiseMps += -speedNoiseMps * reversion + sigma * Math.sqrt(reversion) * random.nextGaussian();
    // Keep the wander bounded: three sigma either way, so a tail draw cannot produce a truck
    // doing 190 km/h and failing PositionEvent's own validation downstream.
    return Math.max(-3 * sigma, Math.min(3 * sigma, speedNoiseMps));
  }

  /**
   * Reefer temperature wandering around its set point.
   *
   * <p>Same mean-reverting walk as the speed noise. A working refrigeration unit holds temperature
   * to within a degree or so and is never exactly on set point; the point of modelling it here
   * rather than inventing a number at emit time is that M4's cold-chain SLA rules need a reading
   * that drifts continuously, so that a breach builds over minutes and can then be seen to clear.
   */
  private void driftTemperature(double seconds) {
    double tau = 300.0;
    double reversion = Math.min(1.0, seconds / tau);
    double sigma = 0.4;
    double offset = temperatureCelsius - setPointCelsius;
    temperatureCelsius +=
        -offset * reversion + sigma * Math.sqrt(reversion) * random.nextGaussian();
  }

  /** An immutable reading of everything true about this truck right now. */
  public VehicleSnapshot snapshot(Instant at) {
    Stop current = phase == TruckPhase.DWELLING ? route.stops().get(cursor) : null;
    Stop next =
        switch (phase) {
          case DRIVING -> route.legs().get(cursor).to();
          case DWELLING -> cursor < route.legs().size() ? route.legs().get(cursor).to() : null;
          case COMPLETED -> null;
        };

    return new VehicleSnapshot(
        vehicleId,
        shipmentId,
        deviceId,
        route.id(),
        at,
        position,
        speedMps * 3.6,
        Geo.normalizeBearing(headingDegrees),
        odometerMeters / 1000.0,
        phase,
        current == null ? null : current.id(),
        next == null ? null : next.id(),
        temperatureCelsius);
  }

  public String vehicleId() {
    return vehicleId;
  }

  public String shipmentId() {
    return shipmentId;
  }

  public Route route() {
    return route;
  }

  public TruckPhase phase() {
    return phase;
  }

  /** True once the route is finished and further ticks would do nothing. */
  public boolean isFinished() {
    return phase == TruckPhase.COMPLETED;
  }

  /** What one tick produced: the new reading, plus anything discrete that happened during it. */
  public record TickResult(VehicleSnapshot snapshot, List<TruckTransition> transitions) {}
}
