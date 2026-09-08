package com.fleettracking.dashboard.read;

import com.fleettracking.events.GeoPoint;
import com.fleettracking.reference.Distance;
import com.fleettracking.reference.ScheduledStop;
import java.util.List;

/**
 * How much road is left, measured now rather than remembered.
 *
 * <h2>The bug this class exists to not have</h2>
 *
 * <p>The tracking processor already computes a remaining distance and stores it on the ETA
 * document. Reading that field here would have been one line, and it would have been wrong. S11
 * writes that document <em>only when an estimate is published</em> — a handful of times per leg,
 * because publishing on every fix would make a dashboard flicker and cost a write per position. So
 * between publishes the stored distance is however far the truck was the last time the estimate
 * moved by more than two minutes. A shipment five kilometres from its stop can be carrying a
 * document that says a hundred, and it is not stale in any way the document admits to: the
 * {@code updatedAt} beside it is the instant that hundred was written, and it is entirely accurate
 * about when a wrong number was recorded.
 *
 * <p>M3 left that written down as a known limitation and M5 had to settle it. Three ways were open:
 * write the ETA state on every fix, republish an estimate on a slow heartbeat, or measure the
 * distance here. The first adds a database write to the busiest path in the platform to keep a field
 * fresh for a reader that may not exist; the second adds events to a topic every consumer would then
 * have to ignore, and weakens the property S11 was proud of — that silence is the normal output of
 * the ETA. The third costs a haversine per shipment per request, and needs nothing from anybody.
 *
 * <h2>Why this is not a duplicate of the ETA's own arithmetic</h2>
 *
 * <p>It looks like one, and S11's design note explains why it is not. Convergence and stability come
 * from different quantities: the <em>distance</em> is measured afresh on every fix and never
 * smoothed, and the <em>speed</em> is a property of the truck that a single reading is never trusted
 * to state. This class recomputes only the first of those. The estimate itself — the part that
 * depends on a learned speed and would be nonsense to guess at from one position — still comes from
 * the tracking processor, and this service never attempts to form its own opinion about when a truck
 * will arrive.
 *
 * <h2>Road distance is the straight line times a constant</h2>
 *
 * <p>Which is a stated assumption of this platform rather than a measurement, and is where a routing
 * engine would go. The number must be the same 1.30 the tracking processor and the simulator use: a
 * dashboard measuring the crow-fly distance beside an ETA computed against a road 30% longer would
 * show a truck 100 km away arriving in an hour and a half, and the two numbers would disagree by
 * exactly the circuity for the whole journey.
 */
public class RemainingDistance {

  private final double roadCircuity;

  public RemainingDistance(double roadCircuity) {
    if (roadCircuity < 1.0) {
      // A road shorter than the straight line between its ends does not exist, and a value below
      // one is much more likely to be a misconfiguration than an intention.
      throw new IllegalArgumentException("road circuity must be at least 1.0: " + roadCircuity);
    }
    this.roadCircuity = roadCircuity;
  }

  /** The ratio this instance bills distances against. */
  public double roadCircuity() {
    return roadCircuity;
  }

  /** Road kilometres from a position to one stop. */
  public double toStopKm(GeoPoint from, ScheduledStop stop) {
    return roadKm(Distance.metersBetween(from, stop.location()));
  }

  /**
   * Road kilometres from a position to the last stop in the plan, by way of every stop in between.
   *
   * <p>Through the stops in plan order rather than straight to the destination, because the plan is
   * the journey: a load that collects in Pune and delivers in Delhi via Ahmedabad has a good deal
   * more road ahead of it than the line from where it is to Delhi suggests. Same reason the next
   * stop is chosen by plan order and not by proximity.
   *
   * @param from where the truck is now
   * @param remaining the stops not yet arrived at, in plan order. Empty for a finished shipment,
   *     which has no road left and is reported as zero rather than as unknown
   */
  public double throughRemainingKm(GeoPoint from, List<ScheduledStop> remaining) {
    if (remaining.isEmpty()) {
      return 0.0;
    }
    double meters = Distance.metersBetween(from, remaining.get(0).location());
    for (int i = 1; i < remaining.size(); i++) {
      meters += Distance.metersBetween(remaining.get(i - 1).location(), remaining.get(i).location());
    }
    return roadKm(meters);
  }

  /**
   * How far through its planned road a shipment is, from 0 to 1.
   *
   * <p>Measured against the plan's own total length — the sum of its legs — rather than against
   * distance travelled, which nothing records. A truck that has driven in a circle has made no
   * progress by this measure, which is the honest answer.
   *
   * <p>Returns null rather than zero when the plan has no length to divide by. A one-stop itinerary
   * is not 0% complete, it is a question with no answer, and a zero would draw an empty progress bar
   * that looks like a stalled truck.
   */
  public Double fractionComplete(GeoPoint from, List<ScheduledStop> all, List<ScheduledStop> remaining) {
    if (all.size() < 2) {
      return null;
    }
    double total = throughRemainingKm(all.get(0).location(), all.subList(1, all.size()));
    if (total <= 0) {
      return null;
    }
    double left = throughRemainingKm(from, remaining);
    return Math.max(0.0, Math.min(1.0, 1.0 - (left / total)));
  }

  private double roadKm(double straightLineMeters) {
    return straightLineMeters / 1000.0 * roadCircuity;
  }

  /** A stop's centre, as a {@link GeoPoint}, for callers holding a position rather than a stop. */
  public static GeoPoint pointOf(double latitude, double longitude) {
    return new GeoPoint(latitude, longitude);
  }
}
