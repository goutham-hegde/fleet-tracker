package com.fleettracking.simulator.fault;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The ways a feed is allowed to misbehave, each switchable on its own.
 *
 * <p>A feed that never misbehaves tests nothing that M2 is built to survive. The gateway's dedup,
 * its dead-letter routing and its out-of-order handling are all claims about behaviour under
 * conditions that a clean simulator never produces, and a claim that cannot fail is not a test.
 *
 * <p>Every probability is <b>per message</b> and defaults to zero, so a default run is realistic
 * rather than adversarial. The single exception is GPS noise, which is on by default at six metres
 * because that is not a fault at all — it is what GPS does, and M3's geofencing has to rediscover
 * arrivals from exactly that kind of position. A simulator emitting perfect coordinates would let a
 * geofence that cannot tolerate jitter look correct.
 *
 * <p>Switch a fault on at the command line, or use the bundled {@code chaos} profile to turn on all
 * of them at once:
 *
 * <pre>{@code
 * --fleet.simulator.faults.drop-probability=0.05
 * --spring.profiles.active=chaos
 * }</pre>
 *
 * @param enabled master switch. False forces every fault off including GPS noise, which is how you
 *     get a run with no randomness in the payloads at all
 * @param gpsNoiseMeters standard deviation of ordinary position jitter, in metres. Applied to the
 *     two feeds that report coordinates, independently, because two receivers on one truck do not
 *     share an error
 * @param badFixProbability chance of an outright wrong fix — a reflected signal in a city, or a
 *     receiver reacquiring after a tunnel. Not noise: a jump of hundreds of metres, which is what
 *     makes an accuracy-blind geofence fire at the wrong place
 * @param badFixRadiusMeters how far a bad fix lands from the truth
 * @param dropProbability chance a message never arrives. Silence is ambiguous downstream — a
 *     consumer cannot tell a dropped position from a truck that stopped reporting
 * @param duplicateProbability chance a message is delivered twice. At-least-once delivery makes
 *     this ordinary rather than exceptional
 * @param malformedProbability chance a payload is corrupted in transit. These are the messages that
 *     must land in the dead-letter topic and nowhere else, which is an M2 exit criterion
 */
@ConfigurationProperties(prefix = "fleet.simulator.faults")
public record FaultProperties(
    Boolean enabled,
    double gpsNoiseMeters,
    double badFixProbability,
    double badFixRadiusMeters,
    double dropProbability,
    double duplicateProbability,
    double malformedProbability) {

  public FaultProperties {
    enabled = enabled == null || enabled;
    if (gpsNoiseMeters < 0) {
      gpsNoiseMeters = 0;
    }
    if (badFixRadiusMeters <= 0) {
      badFixRadiusMeters = 1500;
    }
  }

  /** Defaults: realistic GPS noise, and no other fault. */
  public static FaultProperties defaults() {
    return new FaultProperties(true, 6.0, 0, 1500, 0, 0, 0);
  }

  /** True if any fault at all is active, which is worth logging at startup. */
  public boolean anyActive() {
    return enabled
        && (gpsNoiseMeters > 0
            || badFixProbability > 0
            || dropProbability > 0
            || duplicateProbability > 0
            || malformedProbability > 0);
  }
}
