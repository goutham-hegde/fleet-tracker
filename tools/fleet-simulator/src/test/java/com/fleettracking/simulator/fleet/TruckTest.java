package com.fleettracking.simulator.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.GeoPoint;
import com.fleettracking.simulator.route.Geo;
import com.fleettracking.simulator.route.Route;
import com.fleettracking.simulator.route.Stop;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The movement model, driven over complete routes.
 *
 * <p>These are trajectory tests rather than single-call tests: almost nothing interesting about a
 * physics model shows up in one step. The pattern throughout is to run a whole route to completion,
 * collect every snapshot and transition, and then assert properties over the run — that speed never
 * jumps, that each stop is arrived at exactly once, that the odometer agrees with the route.
 */
class TruckTest {

  private static final Instant START = Instant.parse("2026-08-29T08:00:00Z");
  private static final Duration TICK = Duration.ofSeconds(1);

  private Route route;

  /** A three-stop route with short legs, so a full run is a few hundred ticks rather than hours. */
  @BeforeEach
  void setUp() {
    Stop origin = stop("dc-del", 28.5355, 77.2730, Duration.ofMinutes(1), Stop.StopKind.PICKUP);
    // Roughly 5 km north-east, then another 4 km east.
    Stop middle = stop("stop-a", 28.5680, 77.3210, Duration.ofSeconds(90), Stop.StopKind.DELIVERY);
    Stop last = stop("stop-b", 28.5730, 77.3740, Duration.ofSeconds(30), Stop.StopKind.DELIVERY);
    route = Route.of("test-lane", "Test lane", List.of(origin, middle, last));
  }

  private static Stop stop(String id, double lat, double lon, Duration dwell, Stop.StopKind kind) {
    return new Stop(id, id, "Delhi", "DL", new GeoPoint(lat, lon), 150, dwell, kind);
  }

  private Truck truck() {
    return truck(DriverProfile.LOADED_SEMI, 42L);
  }

  private Truck truck(DriverProfile profile, long seed) {
    return new Truck(
        "VEH-001",
        "SHP-001",
        "DEV-001",
        route,
        profile,
        128_400.0,
        4.0,
        new java.util.Random(seed));
  }

  /** Runs a truck to completion, or until the tick budget runs out. */
  private Run run(Truck truck, int maxTicks) {
    List<VehicleSnapshot> snapshots = new ArrayList<>();
    List<TruckTransition> transitions = new ArrayList<>();
    Instant now = START;

    for (int i = 0; i < maxTicks && !truck.isFinished(); i++) {
      now = now.plus(TICK);
      Truck.TickResult result = truck.tick(now, TICK);
      snapshots.add(result.snapshot());
      transitions.addAll(result.transitions());
    }
    return new Run(snapshots, transitions, now);
  }

  private record Run(List<VehicleSnapshot> snapshots, List<TruckTransition> transitions, Instant end) {}

  @Test
  @DisplayName("starts stationary at the origin, being loaded")
  void startsDwellingAtOrigin() {
    Truck truck = truck();
    VehicleSnapshot initial = truck.snapshot(START);

    assertThat(initial.phase()).isEqualTo(TruckPhase.DWELLING);
    assertThat(initial.speedKph()).isZero();
    assertThat(initial.currentStopId()).isEqualTo("dc-del");
    assertThat(initial.nextStopId()).isEqualTo("stop-a");
    assertThat(Geo.distanceMeters(initial.position(), route.origin().location())).isZero();
    // Already facing the first leg rather than pointing due north in the yard.
    assertThat(initial.headingDegrees()).isBetween(0.0, 90.0);
  }

  @Test
  @DisplayName("does not move until the origin dwell elapses, then departs exactly once")
  void waitsOutTheOriginDwell() {
    Truck truck = truck();
    Run run = run(truck, 2000);

    // The origin dwell is 60 s, so the first 60 ticks are stationary.
    assertThat(run.snapshots().subList(0, 59))
        .allSatisfy(s -> assertThat(s.phase()).isEqualTo(TruckPhase.DWELLING))
        .allSatisfy(s -> assertThat(s.speedKph()).isZero());

    List<TruckTransition.Departed> departures =
        run.transitions().stream().filter(TruckTransition.Departed.class::isInstance)
            .map(TruckTransition.Departed.class::cast)
            .toList();

    assertThat(departures).hasSize(2); // origin and the middle stop; not the final one
    assertThat(departures.getFirst().stop().id()).isEqualTo("dc-del");
    assertThat(departures.getFirst().at()).isEqualTo(START.plusSeconds(60));
  }

  @Test
  @DisplayName("visits every stop exactly once, in order, and then completes")
  void traversesTheWholeRoute() {
    Truck truck = truck();
    Run run = run(truck, 5000);

    assertThat(truck.isFinished()).isTrue();

    List<String> arrivals =
        run.transitions().stream()
            .filter(TruckTransition.Arrived.class::isInstance)
            .map(t -> ((TruckTransition.Arrived) t).stop().id())
            .toList();

    // Exactly one arrival per non-origin stop, in itinerary order. This is the ground truth M3's
    // geofencing has to rediscover from noisy positions alone.
    assertThat(arrivals).containsExactly("stop-a", "stop-b");

    List<TruckTransition> completions =
        run.transitions().stream()
            .filter(TruckTransition.RouteCompleted.class::isInstance)
            .toList();
    assertThat(completions).hasSize(1);
    assertThat(((TruckTransition.RouteCompleted) completions.getFirst()).finalStop().id())
        .isEqualTo("stop-b");
  }

  @Test
  @DisplayName("dwells at each stop for exactly the scheduled time")
  void dwellsForTheScheduledDuration() {
    Truck truck = truck();
    Run run = run(truck, 5000);

    Instant arrivedAtA =
        run.transitions().stream()
            .filter(t -> t instanceof TruckTransition.Arrived a && a.stop().id().equals("stop-a"))
            .findFirst()
            .orElseThrow()
            .at();
    Instant departedA =
        run.transitions().stream()
            .filter(t -> t instanceof TruckTransition.Departed d && d.stop().id().equals("stop-a"))
            .findFirst()
            .orElseThrow()
            .at();

    // Dwell is 90 s; the countdown is resolved at tick granularity, so allow one tick of slack.
    Duration actual = Duration.between(arrivedAtA, departedA);
    assertThat(actual).isBetween(Duration.ofSeconds(90), Duration.ofSeconds(91));
  }

  @Test
  @DisplayName("accelerates and brakes within the profile's limits, never teleporting")
  void respectsAccelerationLimits() {
    DriverProfile profile = DriverProfile.LOADED_SEMI;
    Truck truck = truck(profile, 7L);
    Run run = run(truck, 5000);

    double maxGainKph = profile.accelerationMps2() * 3.6 * 1.001;
    double maxLossKph = profile.decelerationMps2() * 3.6 * 1.001;

    List<VehicleSnapshot> s = run.snapshots();
    for (int i = 1; i < s.size(); i++) {
      double delta = s.get(i).speedKph() - s.get(i - 1).speedKph();
      boolean pulledUp =
          s.get(i).phase() == TruckPhase.DWELLING && s.get(i - 1).phase() == TruckPhase.DRIVING;
      if (pulledUp) {
        // Arriving snaps the last of the speed to zero as the truck comes to rest at the stop.
        // The braking curve has already taken it down to the crawl floor by then, so the snap is
        // walking pace - if this ever exceeds it, the truck reached the dock too fast.
        assertThat(-delta).isLessThanOrEqualTo(Truck.CRAWL_SPEED_MPS * 3.6 + maxLossKph);
        continue;
      }
      assertThat(delta).isLessThanOrEqualTo(maxGainKph);
      assertThat(-delta).isLessThanOrEqualTo(maxLossKph);
    }
  }

  @Test
  @DisplayName("brakes into a stop rather than arriving at cruise speed")
  void decceleratesOnApproach() {
    Truck truck = truck();
    Run run = run(truck, 5000);

    List<VehicleSnapshot> s = run.snapshots();
    int firstArrival = -1;
    for (int i = 1; i < s.size(); i++) {
      if (s.get(i).phase() == TruckPhase.DWELLING && s.get(i - 1).phase() == TruckPhase.DRIVING) {
        firstArrival = i;
        break;
      }
    }
    assertThat(firstArrival).isPositive();

    double cruise = DriverProfile.LOADED_SEMI.cruiseSpeedKph();
    // 10 s out it should already be well off cruise, and in the final second nearly stopped.
    assertThat(s.get(firstArrival - 10).speedKph()).isLessThan(cruise * 0.6);
    assertThat(s.get(firstArrival - 1).speedKph()).isLessThan(20.0);
  }

  @Test
  @DisplayName("keeps speed and heading inside the ranges PositionEvent will validate")
  void staysInsideTheCanonicalModelsRanges() {
    Truck truck = truck();
    Run run = run(truck, 5000);

    assertThat(run.snapshots())
        .allSatisfy(s -> assertThat(s.speedKph()).isBetween(0.0, 250.0))
        .allSatisfy(s -> assertThat(s.headingDegrees()).isGreaterThanOrEqualTo(0.0).isLessThan(360.0))
        .allSatisfy(s -> assertThat(s.position().latitude()).isBetween(-90.0, 90.0))
        .allSatisfy(s -> assertThat(s.position().longitude()).isBetween(-180.0, 180.0));
  }

  @Test
  @DisplayName("odometer only ever increases, and by the route's road distance overall")
  void odometerIsMonotonicAndMatchesTheRoute() {
    Truck truck = truck();
    Run run = run(truck, 5000);

    List<VehicleSnapshot> s = run.snapshots();
    for (int i = 1; i < s.size(); i++) {
      assertThat(s.get(i).odometerKm()).isGreaterThanOrEqualTo(s.get(i - 1).odometerKm());
    }

    double travelled = s.getLast().odometerKm() - 128_400.0;
    double expectedKm = route.totalRoadDistanceMeters() / 1000.0;
    // Within a metre: arrival bills the exact remaining road distance rather than the step that
    // overshot it, so error does not accumulate per stop.
    assertThat(travelled).isCloseTo(expectedKm, org.assertj.core.api.Assertions.within(0.001));
  }

  @Test
  @DisplayName("stays on the great circle between stops instead of wandering off it")
  void followsTheLegItIsOn() {
    Truck truck = truck();
    Run run = run(truck, 5000);

    // Every driving snapshot on the first leg should sit essentially on the line from the origin
    // to stop-a: the sum of the distances to both ends equals the leg length if it is on the line.
    double legLength = route.legs().getFirst().distanceMeters();
    run.snapshots().stream()
        .filter(s -> s.phase() == TruckPhase.DRIVING && "stop-a".equals(s.nextStopId()))
        .forEach(
            s -> {
              double viaTruck =
                  Geo.distanceMeters(route.origin().location(), s.position())
                      + Geo.distanceMeters(s.position(), route.stops().get(1).location());
              assertThat(viaTruck).isCloseTo(legLength, org.assertj.core.api.Assertions.within(1.0));
            });
  }

  @Test
  @DisplayName("speed wanders around cruise rather than sitting pinned to it")
  void speedVariesLikeTraffic() {
    Truck truck = truck();
    Run run = run(truck, 5000);

    List<Double> cruising =
        run.snapshots().stream()
            // Comfortably above the crawl and the braking curve, comfortably below the 60 km/h
            // cruise these trucks now settle at. This read 80 while cruise was 100.
            .filter(s -> s.phase() == TruckPhase.DRIVING && s.speedKph() > 45)
            .map(VehicleSnapshot::speedKph)
            .toList();

    assertThat(cruising).isNotEmpty();
    assertThat(cruising.stream().distinct().count()).isGreaterThan(10);
    double min = cruising.stream().mapToDouble(Double::doubleValue).min().orElseThrow();
    double max = cruising.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
    assertThat(max - min).isGreaterThan(1.0);
  }

  @Test
  @DisplayName("the same seed produces the identical run")
  void isDeterministicForAGivenSeed() {
    Run first = run(truck(DriverProfile.LOADED_SEMI, 99L), 5000);
    Run second = run(truck(DriverProfile.LOADED_SEMI, 99L), 5000);

    assertThat(first.snapshots()).isEqualTo(second.snapshots());
    assertThat(first.transitions()).isEqualTo(second.transitions());
  }

  @Test
  @DisplayName("a different seed produces a different run")
  void differentSeedsDiverge() {
    Run first = run(truck(DriverProfile.LOADED_SEMI, 1L), 5000);
    Run second = run(truck(DriverProfile.LOADED_SEMI, 2L), 5000);

    assertThat(first.snapshots()).isNotEqualTo(second.snapshots());
  }

  @Test
  @DisplayName("reefer temperature holds near its set point without sitting exactly on it")
  void temperatureDriftsAroundSetPoint() {
    Truck truck = truck();
    Run run = run(truck, 5000);

    List<Double> temps = run.snapshots().stream().map(VehicleSnapshot::temperatureCelsius).toList();

    assertThat(temps).allSatisfy(t -> assertThat(t).isBetween(1.0, 7.0));
    assertThat(temps.stream().distinct().count()).isGreaterThan(10);
  }

  @Test
  @DisplayName("a completed truck holds still no matter how many more ticks it gets")
  void completedTrucksStopMoving() {
    Truck truck = truck();
    run(truck, 5000);
    assertThat(truck.isFinished()).isTrue();

    VehicleSnapshot atCompletion = truck.snapshot(START);
    Truck.TickResult extra = truck.tick(START.plusSeconds(3600), TICK);

    assertThat(extra.transitions()).isEmpty();
    assertThat(extra.snapshot().position()).isEqualTo(atCompletion.position());
    assertThat(extra.snapshot().odometerKm()).isEqualTo(atCompletion.odometerKm());
    assertThat(extra.snapshot().speedKph()).isZero();
    assertThat(extra.snapshot().nextStopId()).isNull();
  }

  @Test
  @DisplayName("rejects a non-positive tick")
  void rejectsZeroTick() {
    Truck truck = truck();
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> truck.tick(START, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
  }
}
