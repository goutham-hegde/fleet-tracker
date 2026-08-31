package com.fleettracking.simulator.emit;

/**
 * Where an emitted message goes.
 *
 * <p>The four emitters know how to <em>format</em> a feed and nothing about where its output ends
 * up. That split is what keeps S6 small: a Kafka producer arrives as one more implementation of
 * this interface, and not one line of formatting code changes to accommodate it. It is the same
 * move {@link com.fleettracking.simulator.TickObserver} made for the movement core, one level down.
 *
 * <p>Called on the tick thread, so an implementation that blocks delays the whole fleet. Anything
 * doing real I/O should buffer.
 */
@FunctionalInterface
public interface MessageSink extends AutoCloseable {

  /** Accepts one message. Must not throw for ordinary conditions. */
  void accept(SourceMessage message);

  /** Flushes and releases anything held. Safe to call twice. */
  @Override
  default void close() {}
}
