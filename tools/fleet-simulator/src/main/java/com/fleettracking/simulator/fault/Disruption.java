package com.fleettracking.simulator.fault;

import java.time.Instant;

/**
 * Something going wrong with the truck itself, rather than with the message about it.
 *
 * <h2>Two completely different kinds of fault, and they belong in different places</h2>
 *
 * <p>{@link FaultProperties} describes what happens to a <em>message</em>: it is dropped, it is
 * duplicated, it is corrupted in transit, the receiver reports a wrong coordinate. Those are
 * properties of the wire and of the device, and they exist so that M2's normalizers and dead-letter
 * routing are tested against conditions a clean simulator never produces.
 *
 * <p>A disruption is different in kind. It happens to the <em>world</em>: the truck actually breaks
 * down, the refrigeration unit actually fails, the driver actually takes a wrong turn. The messages
 * describing it are perfectly formed and completely accurate — they simply describe something bad.
 * No amount of transport chaos can produce one, which is why M4's exit criterion could not be met
 * with the fault types S5 provided.
 *
 * <p>So these are modelled inside the movement core rather than at the sink, and the split falls out
 * of one question: would a perfect network still show this? A dropped message would not exist on a
 * perfect network. A stationary truck would.
 *
 * <h2>One disruption at a time, per truck</h2>
 *
 * <p>Deliberately, and not only for simplicity. The exit criterion is that each injected fault
 * raises exactly the expected exception, and a truck that is simultaneously broken down, diverted
 * and silent raises three — at which point the demonstration proves that something fired rather than
 * that the right thing fired. Combinations are what a chaos run is for.
 *
 * @param kind what is wrong
 * @param until when it stops, in simulated time. Every disruption ends: a fault that never resolved
 *     would exercise only half of what M4 is about, since an exception that cannot clear is an
 *     alarm nobody can act on
 */
public record Disruption(Kind kind, Instant until) {

  /** The five things that can go wrong, one per SLA rule. */
  public enum Kind {

    /**
     * The vehicle stops dead where it stands. Engine trouble, a puncture, an accident ahead.
     *
     * <p>Expected to raise an unplanned stop, and only that: the truck is still reporting, its
     * position is accurate, and it is stationary somewhere it was not scheduled to be.
     */
    BREAKDOWN,

    /**
     * The vehicle keeps moving but much more slowly. Weather, a diversion through a town, a driver
     * who has run out of hours.
     *
     * <p>Expected to raise a late arrival and nothing else. This is why it is a separate fault from
     * a breakdown rather than a milder version of one: a truck crawling at twenty km/h is still
     * moving, so no unplanned stop is raised, and the only symptom is an estimate that slides past
     * the delivery window.
     */
    SLOWDOWN,

    /**
     * The driver leaves the planned line and comes back to it.
     *
     * <p>Implemented as a deflection of the truck's heading rather than as an offset applied to the
     * reported position, and the difference matters. An offset would be a lie about where an
     * honest truck is — which is what a bad GPS fix already models. This is a truck that genuinely
     * drove somewhere else, so its odometer, its speed and its arrival time all move the way they
     * really would.
     */
    DETOUR,

    /**
     * The unit stops holding temperature. The box drifts toward ambient.
     *
     * <p>Only meaningful on a refrigerated lane, and applied only there. A dry van has nothing to
     * fail.
     */
    REEFER_FAILURE,

    /**
     * The device stops transmitting, while the truck carries on perfectly normally.
     *
     * <p>The one disruption that produces no messages at all rather than alarming ones, which is
     * exactly what makes the rule that detects it need a timer. Note that this is <em>not</em> the
     * same as {@link FaultProperties#dropProbability()}: a drop loses individual messages at
     * random, and this loses every message from one device for a stretch. A rule that detects total
     * silence cannot be exercised by a fault that thins traffic.
     */
    BLACKOUT
  }

  /** Whether this disruption is still in force at a given simulated instant. */
  public boolean activeAt(Instant now) {
    return now.isBefore(until);
  }
}
