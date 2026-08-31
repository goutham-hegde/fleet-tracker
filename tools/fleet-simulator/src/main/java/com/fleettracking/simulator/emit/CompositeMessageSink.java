package com.fleettracking.simulator.emit;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fans one message out to several sinks.
 *
 * <p>Exists so that capturing fixtures to disk and watching payloads on the console are not an
 * either/or, and so that S6 can add Kafka alongside both rather than replacing them.
 *
 * <p>A failing sink is logged and skipped rather than allowed to propagate. The alternative — one
 * broken sink taking the tick thread down with it — would stop the fleet, and a simulator that
 * stops driving because a capture file filled up is a worse outcome than a gap in the capture.
 */
public class CompositeMessageSink implements MessageSink {

  private static final Logger log = LoggerFactory.getLogger(CompositeMessageSink.class);

  private final List<MessageSink> delegates;

  public CompositeMessageSink(List<MessageSink> delegates) {
    this.delegates = List.copyOf(delegates);
  }

  @Override
  public void accept(SourceMessage message) {
    for (MessageSink delegate : delegates) {
      try {
        delegate.accept(message);
      } catch (RuntimeException e) {
        log.error("Sink {} failed; message dropped from that sink only",
            delegate.getClass().getSimpleName(), e);
      }
    }
  }

  @Override
  public void close() {
    for (MessageSink delegate : delegates) {
      try {
        delegate.close();
      } catch (Exception e) {
        log.warn("Sink {} failed to close", delegate.getClass().getSimpleName(), e);
      }
    }
  }

  /** The sinks this fans out to. Test seam. */
  public List<MessageSink> delegates() {
    return delegates;
  }
}
