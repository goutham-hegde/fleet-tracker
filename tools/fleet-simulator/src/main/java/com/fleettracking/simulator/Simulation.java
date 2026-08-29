package com.fleettracking.simulator;

import com.fleettracking.simulator.fleet.DriverProfile;
import com.fleettracking.simulator.fleet.Truck;
import com.fleettracking.simulator.fleet.TruckTransition;
import com.fleettracking.simulator.fleet.VehicleSnapshot;
import com.fleettracking.simulator.route.Lanes;
import com.fleettracking.simulator.route.Route;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The whole fleet, and the clock they all share.
 *
 * <p>Plain Java with no Spring in it, which is what lets a test run a thousand ticks in
 * milliseconds without starting a context. {@link SimulationRunner} is the thin Spring wrapper that
 * calls {@link #tick()} on a schedule.
 *
 * <h2>Simulated time</h2>
 *
 * <p>The simulation keeps its own clock rather than reading the system one. Each tick advances it
 * by a fixed amount, and every event is stamped from it. That is what makes a run at
 * {@code timeScale: 60} coherent: the trucks experience an hour of driving in a minute of real
 * time, and the timestamps they emit are an hour apart, so anything downstream computing a speed or
 * a dwell from those timestamps gets the right answer. Reading {@code Instant.now()} anywhere in
 * here would produce events a minute apart describing an hour of travel — trucks apparently doing
 * 6 000 km/h.
 *
 * <p>Not thread-safe: one loop steps it.
 */
public final class Simulation {

  private final List<Truck> trucks;
  private final List<Route> lanes;
  private final RandomGenerator random;
  private final boolean repeatRoutes;
  private final Duration tickDelta;

  private Instant now;
  private long tickCount;
  private int nextVehicleNumber;

  /**
   * Builds a fleet and places it at the start of its routes.
   *
   * @param startAt the simulated instant the run begins at
   * @param tickDelta how much simulated time each tick covers
   * @param truckCount how many trucks to create
   * @param lanes routes to spread them across, round-robin
   * @param seed master seed; each truck derives its own from this and its index
   * @param repeatRoutes whether a finished truck is replaced by a fresh one on the next lane
   */
  public Simulation(
      Instant startAt,
      Duration tickDelta,
      int truckCount,
      List<Route> lanes,
      long seed,
      boolean repeatRoutes) {
    if (lanes == null || lanes.isEmpty()) {
      throw new IllegalArgumentException("a simulation needs at least one lane");
    }
    if (truckCount <= 0) {
      throw new IllegalArgumentException("truckCount must be positive: " + truckCount);
    }
    this.now = java.util.Objects.requireNonNull(startAt, "startAt");
    this.tickDelta = java.util.Objects.requireNonNull(tickDelta, "tickDelta");
    this.lanes = List.copyOf(lanes);
    this.random = new java.util.Random(seed);
    this.repeatRoutes = repeatRoutes;
    this.trucks = new ArrayList<>(truckCount);

    for (int i = 0; i < truckCount; i++) {
      trucks.add(newTruck(seed, i));
    }
  }

  /** Creates one truck on the lane its index selects. */
  private Truck newTruck(long seed, int index) {
    Route lane = lanes.get(index % lanes.size());
    int number = ++nextVehicleNumber;

    // Each truck gets its own generator seeded from the master seed and its own number, so the
    // fleet is reproducible as a whole *and* one truck's randomness does not shift depending on
    // how many other trucks happen to be running. Sharing one generator across the fleet would
    // make a run with 8 trucks produce a different first truck than a run with 4.
    RandomGenerator truckRandom = new java.util.Random(seed * 31 + number);

    // Cold-chain lanes run a reefer at 4 C; everything else is ambient and reports the weather.
    double setPoint = lane.id().contains("cold") ? 4.0 : 18.0;

    return new Truck(
        "VEH-%04d".formatted(number),
        "SHP-%s-%04d".formatted(lane.id().substring(0, 3).toUpperCase(java.util.Locale.ROOT), number),
        "DEV-%04d".formatted(number),
        lane,
        DriverProfile.LOADED_SEMI,
        // Spread starting odometers over a plausible fleet age rather than starting everything at
        // a suspiciously round number.
        80_000 + truckRandom.nextDouble() * 600_000,
        setPoint,
        truckRandom);
  }

  /**
   * Advances simulated time by one tick and steps every truck.
   *
   * @return the readings and transitions the whole fleet produced this tick
   */
  public TickReport tick() {
    now = now.plus(tickDelta);
    tickCount++;

    List<VehicleSnapshot> snapshots = new ArrayList<>(trucks.size());
    List<TruckTransition> transitions = new ArrayList<>();

    for (int i = 0; i < trucks.size(); i++) {
      Truck truck = trucks.get(i);
      Truck.TickResult result = truck.tick(now, tickDelta);
      snapshots.add(result.snapshot());
      transitions.addAll(result.transitions());

      if (truck.isFinished() && repeatRoutes) {
        // Put a fresh truck on the next lane along, so a long-running demo keeps moving and the
        // fleet drifts across every lane rather than sitting on the one it started on.
        trucks.set(i, newTruck(random.nextLong(), nextVehicleNumber));
      }
    }

    return new TickReport(now, tickCount, List.copyOf(snapshots), List.copyOf(transitions));
  }

  /** Current simulated time. */
  public Instant now() {
    return now;
  }

  /** How many ticks have been run. */
  public long tickCount() {
    return tickCount;
  }

  /** The live fleet. Sized as configured; contents change as trucks finish and are replaced. */
  public List<Truck> trucks() {
    return List.copyOf(trucks);
  }

  /** True when every truck has finished and none will be replaced. */
  public boolean isFinished() {
    return !repeatRoutes && trucks.stream().allMatch(Truck::isFinished);
  }

  /** Builds a simulation from bound configuration, starting at the given instant. */
  public static Simulation from(SimulatorProperties properties, Instant startAt) {
    return new Simulation(
        startAt,
        properties.simulatedTickDelta(),
        properties.trucks(),
        Lanes.ALL,
        properties.seed(),
        properties.repeatRoutes());
  }

  /**
   * Everything one tick produced.
   *
   * @param at simulated time at the end of the tick
   * @param tickNumber how many ticks have run, counting this one
   * @param snapshots one reading per truck, in fleet order
   * @param transitions the discrete things that happened, across the whole fleet
   */
  public record TickReport(
      Instant at, long tickNumber, List<VehicleSnapshot> snapshots, List<TruckTransition> transitions) {}
}
