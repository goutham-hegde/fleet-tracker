package com.fleettracking.dashboard;

import com.fleettracking.dashboard.stream.DashboardConsumers;
import com.fleettracking.dashboard.stream.StreamBroadcaster;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * One line every thirty seconds saying what this service has read and what it has sent.
 *
 * <p>The same argument the other three consumers make, with one addition that is specific to a read
 * API. A dashboard has three distinct ways of looking broken and they are indistinguishable from the
 * outside: nothing is being produced, nothing is being consumed, or nothing is being delivered
 * because no viewer is connected. This line separates all three — the positions counter says whether
 * events are arriving, the viewer count says whether anybody is watching, and the dropped counter
 * says whether the connection is keeping up.
 *
 * <p>A rising {@code dropped} is the interesting one. It means viewers are falling behind the fleet,
 * which is the point at which the sample interval wants raising rather than the queue.
 */
public class DashboardHeartbeat implements InitializingBean, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(DashboardHeartbeat.class);

  private final DashboardConsumers consumers;
  private final StreamBroadcaster broadcaster;
  private final Duration interval;

  private ScheduledExecutorService timer;

  public DashboardHeartbeat(
      DashboardConsumers consumers, StreamBroadcaster broadcaster, Duration interval) {
    this.consumers = consumers;
    this.broadcaster = broadcaster;
    this.interval = interval;
  }

  @Override
  public void afterPropertiesSet() {
    // A daemon thread: the servlet container and the listener containers are what hold this JVM
    // open, and a timer able to keep it alive on its own would turn a failed startup into a process
    // that never exits.
    timer =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "dashboard-heartbeat");
              thread.setDaemon(true);
              return thread;
            });
    timer.scheduleAtFixedRate(
        this::report, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void report() {
    try {
      log.info("dashboard: consumed [{}] streamed [{}]", consumers.summary(), broadcaster.summary());
    } catch (RuntimeException failed) {
      // A heartbeat that can bring down the thing it reports on is worse than no heartbeat.
      log.warn("dashboard: heartbeat failed: {}", failed.toString());
    }
  }

  @Override
  public void destroy() {
    if (timer != null) {
      timer.shutdownNow();
    }
  }
}
