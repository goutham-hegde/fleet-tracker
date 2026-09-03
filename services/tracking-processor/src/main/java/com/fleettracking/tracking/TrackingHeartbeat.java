package com.fleettracking.tracking;

import com.fleettracking.tracking.consume.PositionConsumer;
import com.fleettracking.tracking.store.PositionStore;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Logs what the processor has done, at a fixed interval.
 *
 * <p>A consumer that is working perfectly says nothing at all, which looks precisely like one that
 * has stalled on a partition or lost its assignment. One line every half minute is the difference
 * between watching a run and guessing at it, and it costs two counters and a query.
 *
 * <p>It counts two different things on purpose. The in-memory counters are what <em>this process</em>
 * has done since it started; the collection counts are what is <em>in the database</em>, including
 * everything earlier runs wrote. Seeing the first pair move while the second does not would mean
 * writes are going somewhere other than where the reads are looking, which is exactly the failure
 * the wrong-database trap in S8 produced.
 *
 * <p>A daemon thread, so it cannot by itself hold the JVM open — keeping the process alive is the
 * listener container's job, and a heartbeat that outlived the thing it reports on would be an
 * actively misleading one.
 */
public class TrackingHeartbeat implements InitializingBean, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(TrackingHeartbeat.class);

  private final PositionConsumer consumer;
  private final PositionStore store;
  private final Duration interval;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "tracking-heartbeat");
            thread.setDaemon(true);
            return thread;
          });

  public TrackingHeartbeat(PositionConsumer consumer, PositionStore store, Duration interval) {
    this.consumer = consumer;
    this.store = store;
    this.interval = interval;
  }

  @Override
  public void afterPropertiesSet() {
    long millis = interval.toMillis();
    scheduler.scheduleWithFixedDelay(this::report, millis, millis, TimeUnit.MILLISECONDS);
  }

  private void report() {
    try {
      log.info(
          "this run: {} | in mongo: {} measurements across {} shipments",
          consumer.summary(),
          store.historyCount(),
          store.trackedShipments());
    } catch (RuntimeException e) {
      // A heartbeat that kills its own schedule on one failed count is worse than no heartbeat:
      // scheduleWithFixedDelay cancels the task when it throws, so the silence would begin exactly
      // when the database started misbehaving and there was something to say.
      log.warn("heartbeat could not read the store: {}", e.toString());
    }
  }

  @Override
  public void destroy() {
    scheduler.shutdownNow();
  }
}
