package com.fleettracking.simulator.fault;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.GeoPoint;
import com.fleettracking.simulator.route.Geo;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FaultProfileTest {

  private static final GeoPoint CHICAGO = new GeoPoint(41.8781, -87.6298);

  private static FaultProfile profile(FaultProperties properties) {
    return new FaultProfile(properties, new Random(3));
  }

  @Test
  @DisplayName("scatters positions around the truth without moving the truth")
  void addsGpsNoise() {
    FaultProfile faults = profile(new FaultProperties(true, 6.0, 0, 1500, 0, 0, 0));

    double total = 0;
    double worst = 0;
    for (int i = 0; i < 500; i++) {
      double error = Geo.distanceMeters(CHICAGO, faults.perturb(CHICAGO));
      total += error;
      worst = Math.max(worst, error);
    }

    double mean = total / 500;
    // Half-normal with sigma 6 has a mean near 4.8 m; the tail should stay inside a few sigma.
    assertThat(mean).isBetween(3.0, 7.0);
    assertThat(worst).isLessThan(40.0);
  }

  @Test
  @DisplayName("a bad fix lands hundreds of metres away, not a few")
  void producesBadFixes() {
    FaultProfile faults = profile(new FaultProperties(true, 6.0, 1.0, 1500, 0, 0, 0));

    double error = Geo.distanceMeters(CHICAGO, faults.perturb(CHICAGO));

    assertThat(error).isBetween(700.0, 2300.0);
  }

  @Test
  @DisplayName("the master switch silences every fault, noise included")
  void masterSwitchDisablesEverything() {
    FaultProfile faults = profile(new FaultProperties(false, 25.0, 1.0, 1500, 1.0, 1.0, 1.0));

    assertThat(faults.perturb(CHICAGO)).isEqualTo(CHICAGO);
    assertThat(faults.drops()).isFalse();
    assertThat(faults.duplicates()).isFalse();
    assertThat(faults.malforms()).isFalse();
  }

  @Test
  @DisplayName("each fault is independent of the others")
  void faultsAreIndependent() {
    FaultProfile onlyDrops = profile(new FaultProperties(true, 0, 0, 1500, 1.0, 0, 0));
    assertThat(onlyDrops.drops()).isTrue();
    assertThat(onlyDrops.duplicates()).isFalse();
    assertThat(onlyDrops.malforms()).isFalse();
    assertThat(onlyDrops.perturb(CHICAGO)).isEqualTo(CHICAGO);

    FaultProfile onlyMalformed = profile(new FaultProperties(true, 0, 0, 1500, 0, 0, 1.0));
    assertThat(onlyMalformed.drops()).isFalse();
    assertThat(onlyMalformed.malforms()).isTrue();
  }

  @Test
  @DisplayName("corruption is partial, so it still looks like the format it claims to be")
  void corruptsPartially() {
    FaultProfile faults = profile(new FaultProperties(true, 0, 0, 1500, 0, 0, 1.0));
    String clean = "{\"sid\":\"SHP-CHI-0001\",\"lat\":41.8781,\"spd\":27.3}";

    java.util.Set<String> shapes = new java.util.HashSet<>();
    for (int i = 0; i < 40; i++) {
      shapes.add(faults.malform(clean));
    }

    // Several distinct corruptions, and none of them is the original.
    assertThat(shapes).hasSizeGreaterThan(2);
    assertThat(shapes).doesNotContain(clean);
  }

  @Test
  @DisplayName("reports a wider accuracy when the fixes really are noisier")
  void reportsAccuracyHonestly() {
    FaultProfile noisy = profile(new FaultProperties(true, 25.0, 0, 1500, 0, 0, 0));

    assertThat(noisy.reportedAccuracyMeters(10.0)).isEqualTo(35.0);
    assertThat(FaultProfile.none().reportedAccuracyMeters(10.0)).isEqualTo(10.0);
  }

  @Test
  @DisplayName("defaults are realistic rather than adversarial")
  void defaultsAreRealistic() {
    FaultProperties defaults = FaultProperties.defaults();

    assertThat(defaults.gpsNoiseMeters()).isPositive();
    assertThat(defaults.dropProbability()).isZero();
    assertThat(defaults.duplicateProbability()).isZero();
    assertThat(defaults.malformedProbability()).isZero();
    assertThat(defaults.anyActive()).isTrue();
  }
}
