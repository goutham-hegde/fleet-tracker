package com.fleettracking.simulator.fault;

import com.fleettracking.simulator.emit.MessageSink;
import com.fleettracking.simulator.emit.SourceMessage;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies transport faults on the way to the real sink: losing messages, repeating them, and
 * corrupting them.
 *
 * <p>These three sit here rather than inside the emitters because they are not properties of a
 * format. A message is not dropped because it was JSON; it is dropped because something between the
 * truck and the platform failed. Putting them at the boundary means one implementation covers all
 * four feeds, and adding a fifth feed later inherits the faults for free.
 *
 * <p>GPS noise is the exception and stays in the emitters, because a wrong coordinate <em>is</em> a
 * property of the device that measured it, and it has to be applied before the payload is
 * formatted — by the time a message reaches this class the position is already a string.
 *
 * <p>Wrapping the capture sink rather than sitting beside it is deliberate: what gets captured to
 * {@code docs/samples/} is what the platform would actually receive, corruption included. Fixtures
 * of messages that were dropped in transit would be fixtures of events that never happened.
 */
public class FaultInjectingSink implements MessageSink {

  private static final Logger log = LoggerFactory.getLogger(FaultInjectingSink.class);

  private final MessageSink delegate;
  private final FaultProfile faults;

  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong duplicated = new AtomicLong();
  private final AtomicLong malformed = new AtomicLong();

  public FaultInjectingSink(MessageSink delegate, FaultProfile faults) {
    this.delegate = delegate;
    this.faults = faults;
  }

  @Override
  public void accept(SourceMessage message) {
    if (faults.drops()) {
      dropped.incrementAndGet();
      return;
    }

    SourceMessage toSend = message;
    if (faults.malforms()) {
      malformed.incrementAndGet();
      toSend =
          new SourceMessage(
              message.source(),
              message.contentType(),
              message.routingKey(),
              message.occurredAt(),
              message.emittedAt(),
              faults.malform(message.body()));
    }

    delegate.accept(toSend);

    if (faults.duplicates()) {
      duplicated.incrementAndGet();
      // The same message again, byte for byte -- which is what makes it indistinguishable from the
      // original and forces dedup to work off the payload's own identifiers.
      delegate.accept(toSend);
    }
  }

  @Override
  public void close() {
    if (dropped.get() + duplicated.get() + malformed.get() > 0) {
      log.info(
          "Faults injected: {} dropped, {} duplicated, {} malformed",
          dropped.get(),
          duplicated.get(),
          malformed.get());
    }
    delegate.close();
  }

  /** How many messages were lost. Test seam. */
  public long dropped() {
    return dropped.get();
  }

  /** How many were sent twice. Test seam. */
  public long duplicated() {
    return duplicated.get();
  }

  /** How many were corrupted. Test seam. */
  public long malformed() {
    return malformed.get();
  }
}
