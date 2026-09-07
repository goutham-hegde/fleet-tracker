package com.fleettracking.simulator.fault;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.simulator.fleet.DriverProfile;
import com.fleettracking.simulator.fleet.Truck;
import com.fleettracking.simulator.fleet.TruckPhase;
import com.fleettracking.simulator.fleet.VehicleSnapshot;
import com.fleettracking.simulator.route.Lanes;
import com.fleettracking.simulator.route.Route;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * That each disruption actually changes the world in the way its rule will have to detect.
 *
 * <p>These are the other half of the exception service's rule tests. Those check that a rule fires
 * given a certain shape of input; these check that the simulator can actually produce that shape.
 * Both are needed, and the gap between them is where a demonstration silently fails: a rule that
 * works perfectly against a fault the simulator never manages to inject proves nothing.
 */
class DisruptionTest {

  private static final Instant START = Instant.parse("2026-09-01T06:00:00Z");
  private static final Duration TICK = Duration.ofSeconds(30);

  /** The refrigerated Hyderabad-Bengaluru lane. */
  private static final Route COLD = Lanes.ALL.stream()
      .filter(lane -> lane.id().contains("cold"))
      .findFirst()
      .orElseThrow();

  private static Truck truck(Route route, double setPoint) {
    return new Truck(
        "VEH-0001", "SHP-TST-0001", "DEV-0001", route, DriverProfile.LOADED_SEMI, 100_000, setPoint,
        new Random(42));
  }

  /** Runs a truck until it is out of its origin dwell and genuinely driving. */
  private static Instant driveUntilMoving(Truck truck) {
    Instant now = START;
    for (int i = 0; i < 1000 && truck.phase() != TruckPhase.DRIVING; i++) {
      now = now.plus(TICK);
      truck.tick(now, TICK);
    }
    // A few more ticks to get up to speed.
    for (int i = 0; i < 20; i++) {
      now = now.plus(TICK);
      truck.tick(now, TICK);
    }
    return now;
  }

  @Test
  void aBreakdownStopsTheTruckWhereItStands() {
    Truck truck = truck(COLD, 4.0);
    Instant now = driveUntilMoving(truck);

    VehicleSnapshot before = truck.snapshot(now);
    assertThat(before.speedKph()).isGreaterThan(40.0);

    truck.disrupt(new Disruption(Disruption.Kind.BREAKDOWN, now.plus(Duration.ofHours(1))), 1.0, 0.0);
    now = now.plus(TICK);
    VehicleSnapshot after = truck.tick(now, TICK).snapshot();

    assertThat(after.speedKph()).isZero();
    // Still where it was, and still not at a stop -- which is exactly what the rule looks for.
    assertThat(after.position()).isEqualTo(before.position());
    assertThat(after.phase()).isEqualTo(TruckPhase.DRIVING);
  }

  @Test
  void aBrokenDownTruckResumesTheSameLegWhenItRecovers() {
    Truck truck = truck(COLD, 4.0);
    Instant now = driveUntilMoving(truck);
    String heading = truck.snapshot(now).nextStopId();

    truck.disrupt(new Disruption(Disruption.Kind.BREAKDOWN, now.plus(Duration.ofMinutes(2))), 1.0, 0.0);
    for (int i = 0; i < 10; i++) {
      now = now.plus(TICK);
      truck.tick(now, TICK);
    }

    VehicleSnapshot after = truck.snapshot(now);
    assertThat(after.speedKph()).isGreaterThan(0.0);
    // The cursor never moved, so it is still going to the same place.
    assertThat(after.nextStopId()).isEqualTo(heading);
    assertThat(truck.isDisrupted()).isFalse();
  }

  @Test
  void aSlowdownKeepsTheTruckMovingWhichIsWhatSeparatesItFromABreakdown() {
    Truck truck = truck(COLD, 4.0);
    Instant now = driveUntilMoving(truck);
    double cruising = truck.snapshot(now).speedKph();

    truck.disrupt(new Disruption(Disruption.Kind.SLOWDOWN, now.plus(Duration.ofHours(2))), 0.25, 0.0);
    for (int i = 0; i < 30; i++) {
      now = now.plus(TICK);
      truck.tick(now, TICK);
    }

    double slowed = truck.snapshot(now).speedKph();
    assertThat(slowed).isLessThan(cruising * 0.5);
    // Still moving. A truck crawling at twenty km/h raises no unplanned stop, and the only symptom
    // is an estimate that slides -- which is the whole reason this is a separate fault.
    assertThat(slowed).isGreaterThan(5.0);
  }

  @Test
  void aDetourTakesTheTruckGenuinelyOffItsLine() {
    Truck onCourse = truck(COLD, 4.0);
    Instant now = driveUntilMoving(onCourse);
    Truck diverted = truck(COLD, 4.0);
    driveUntilMoving(diverted);

    diverted.disrupt(new Disruption(Disruption.Kind.DETOUR, now.plus(Duration.ofHours(2))), 1.0, 45.0);

    Instant t = now;
    for (int i = 0; i < 80; i++) {
      t = t.plus(TICK);
      onCourse.tick(t, TICK);
      diverted.tick(t, TICK);
    }

    double apartKm =
        com.fleettracking.simulator.route.Geo.distanceMeters(
                onCourse.snapshot(t).position(), diverted.snapshot(t).position())
            / 1000.0;

    // Forty minutes at highway speed, forty-five degrees off: well outside a twelve-kilometre
    // corridor. The odometer moved too -- this is a truck that really drove somewhere else, not a
    // reported position that lied about where an honest truck was.
    assertThat(apartKm).isGreaterThan(12.0);
    assertThat(diverted.snapshot(t).odometerKm()).isGreaterThan(100_000.0);
  }

  @Test
  void aFailedReeferWarmsTheBoxTowardAmbient() {
    Truck truck = truck(COLD, 4.0);
    Instant now = driveUntilMoving(truck);
    assertThat(truck.snapshot(now).temperatureCelsius()).isCloseTo(4.0, org.assertj.core.api.Assertions.within(3.0));

    truck.disrupt(new Disruption(Disruption.Kind.REEFER_FAILURE, now.plus(Duration.ofHours(3))), 1.0, 0.0);
    for (int i = 0; i < 120; i++) {
      now = now.plus(TICK);
      truck.tick(now, TICK);
    }

    // An hour of failure. Warmed well past any band a customer contracts for, and gradually enough
    // that an excursion tolerance means something.
    assertThat(truck.snapshot(now).temperatureCelsius()).isGreaterThan(12.0);
  }

  @Test
  void arecoveredReeferComesBackDownToItsSetpoint() {
    Truck truck = truck(COLD, 4.0);
    Instant now = driveUntilMoving(truck);

    truck.disrupt(new Disruption(Disruption.Kind.REEFER_FAILURE, now.plus(Duration.ofMinutes(60))), 1.0, 0.0);
    for (int i = 0; i < 240; i++) {
      now = now.plus(TICK);
      truck.tick(now, TICK);
    }

    // Every disruption ends. An exception that can only ever be raised is half of what M4 is about.
    assertThat(truck.isDisrupted()).isFalse();
    assertThat(truck.snapshot(now).temperatureCelsius()).isCloseTo(4.0, org.assertj.core.api.Assertions.within(3.0));
  }

  @Test
  void aBlackoutSilencesTheDeviceWithoutStoppingTheTruck() {
    Truck truck = truck(COLD, 4.0);
    Instant now = driveUntilMoving(truck);

    truck.disrupt(new Disruption(Disruption.Kind.BLACKOUT, now.plus(Duration.ofHours(1))), 1.0, 0.0);
    now = now.plus(TICK);
    VehicleSnapshot during = truck.tick(now, TICK).snapshot();

    assertThat(during.transmitting()).isFalse();
    // The truck is fine. It is still moving, still being tracked as ground truth, and only the
    // outside world hears nothing -- which is what makes it a genuine test of a rule that fires on
    // absence rather than on a bad reading.
    assertThat(during.speedKph()).isGreaterThan(0.0);
  }

  @Test
  void aTruckAlreadyDisruptedIsNotOfferedASecondFault() {
    Truck truck = truck(COLD, 4.0);
    Instant now = driveUntilMoving(truck);

    truck.disrupt(new Disruption(Disruption.Kind.BREAKDOWN, now.plus(Duration.ofHours(1))), 1.0, 0.0);
    truck.disrupt(new Disruption(Disruption.Kind.BLACKOUT, now.plus(Duration.ofHours(1))), 1.0, 0.0);

    assertThat(truck.disruption().kind()).isEqualTo(Disruption.Kind.BREAKDOWN);
  }

  @Test
  void theSchedulerNeverOffersAReeferFailureToADryVan() {
    DisruptionProperties everything =
        new DisruptionProperties(
            true, 0, null, 0, null, 0, 0, null, 0, 1000.0, null, 0, null);
    DisruptionScheduler scheduler = new DisruptionScheduler(everything, new Random(1));

    // A rate of a thousand per hour: if a dry van could get one, it would get one immediately.
    for (int i = 0; i < 200; i++) {
      assertThat(scheduler.rollFor(false, START, Duration.ofMinutes(5))).isNull();
    }
    assertThat(scheduler.rollFor(true, START, Duration.ofMinutes(5))).isNotNull();
  }

  @Test
  void theDisruptionRateDoesNotDependOnHowOftenTheSimulationTicks() {
    // The trap this conversion exists to avoid: turning the tick rate up to run a demo faster
    // must not break down the whole fleet, and turning it down must not produce a flawless run.
    DisruptionProperties oncePerHour =
        new DisruptionProperties(true, 1.0, null, 0, null, 0, 0, null, 0, 0, null, 0, null);

    int coarse = countOverAnHour(new DisruptionScheduler(oncePerHour, new Random(7)), Duration.ofMinutes(5));
    int fine = countOverAnHour(new DisruptionScheduler(oncePerHour, new Random(7)), Duration.ofSeconds(10));

    // Both should land near one per simulated hour, whatever the tick length. Sampled over many
    // simulated hours so the comparison is about the rate rather than about one draw.
    assertThat(coarse).isBetween(50, 150);
    assertThat(fine).isBetween(50, 150);
  }

  /** How many disruptions a scheduler produces over a hundred simulated hours. */
  private static int countOverAnHour(DisruptionScheduler scheduler, Duration tick) {
    long ticks = Duration.ofHours(100).dividedBy(tick);
    int count = 0;
    Instant now = START;
    for (long i = 0; i < ticks; i++) {
      now = now.plus(tick);
      if (scheduler.rollFor(false, now, tick) != null) {
        count++;
      }
    }
    return count;
  }
}
