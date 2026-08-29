package com.fleettracking.simulator;

/**
 * Something that wants to know what the fleet did each tick.
 *
 * <p>This interface is the entire reason S5 will be a formatting session rather than a rewrite. The
 * simulation core produces canonical truth and knows nothing about wire formats; the four source
 * emitters — telematics JSON, mobile-app JSON, EDI 214 text, reefer sensor readings — will each
 * arrive as an implementation of this, deciding for themselves what to emit, at what rate, with
 * what faults, and to where. In S6 a Kafka producer joins them the same way.
 *
 * <p>Observers are called in registration order on the tick thread, so an implementation that
 * blocks delays the whole fleet. Anything slow — a network write, in particular — should hand off
 * to its own queue.
 */
@FunctionalInterface
public interface TickObserver {

  /** Called once per tick, after every truck has been advanced. */
  void onTick(Simulation.TickReport report);
}
