package com.fleettracking.exceptions;

import com.fleettracking.exceptions.consume.ExceptionConsumers;
import com.fleettracking.exceptions.incident.IncidentService;
import com.fleettracking.exceptions.incident.IncidentStore;
import com.fleettracking.exceptions.rule.RuleService;
import com.fleettracking.exceptions.rule.SignalLossRule;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Two timers: the one that says the service is alive, and the one the fifth rule cannot work
 * without.
 *
 * <h2>Why the sweep is here and not a {@code @Scheduled} annotation</h2>
 *
 * <p>It could be. Putting it beside the heartbeat instead keeps both of this service's clocks in one
 * file, and makes the sweep interval visibly a sibling of the reporting interval rather than an
 * annotation attribute somewhere in the rule package. It also avoids enabling Spring's scheduling
 * machinery for two tasks.
 *
 * <p>The sweep is the only reason this service needs a timer at all, and it exists because
 * {@link SignalLossRule} fires on the absence of events. Nothing will ever arrive to trigger it.
 *
 * <h2>Why a heartbeat is worth the log lines</h2>
 *
 * <p>The healthy state of this service is near-total silence: an exception service that is raising
 * things constantly is either badly tuned or attached to a fleet in serious trouble. Silence is also
 * exactly what a wedged consumer produces, and a stalled partition, an empty reference-data
 * collection and a broker that never delivered anything all look identical from outside. One line
 * every thirty seconds saying how many events have been read and how many incidents are open is the
 * cheapest way to tell "nothing is wrong" apart from "nothing is working".
 */
public class ExceptionHeartbeat implements InitializingBean, DisposableBean {

  private static final Logger log = LoggerFactory.getLogger(ExceptionHeartbeat.class);

  private final ExceptionConsumers consumers;
  private final IncidentService incidents;
  private final IncidentStore store;
  private final RuleService rules;
  private final SignalLossRule signalLoss;
  private final Duration heartbeatInterval;
  private final Duration sweepInterval;

  private ScheduledExecutorService timers;

  public ExceptionHeartbeat(
      ExceptionConsumers consumers,
      IncidentService incidents,
      IncidentStore store,
      RuleService rules,
      SignalLossRule signalLoss,
      Duration heartbeatInterval,
      Duration sweepInterval) {
    this.consumers = consumers;
    this.incidents = incidents;
    this.store = store;
    this.rules = rules;
    this.signalLoss = signalLoss;
    this.heartbeatInterval = heartbeatInterval;
    this.sweepInterval = sweepInterval;
  }

  @Override
  public void afterPropertiesSet() {
    // Daemon threads: the Kafka listener containers are what hold this JVM open, and a timer that
    // could keep it alive on its own would turn a failed startup into a process that never exits.
    timers =
        Executors.newScheduledThreadPool(
            2,
            runnable -> {
              Thread thread = new Thread(runnable, "exception-timer");
              thread.setDaemon(true);
              return thread;
            });

    timers.scheduleAtFixedRate(
        this::beat,
        heartbeatInterval.toMillis(),
        heartbeatInterval.toMillis(),
        TimeUnit.MILLISECONDS);

    timers.scheduleAtFixedRate(
        this::sweep, sweepInterval.toMillis(), sweepInterval.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void beat() {
    try {
      log.info(
          "consumed[{}] incidents[{}] open={} watching={} watermark={}",
          consumers.summary(),
          incidents.summary(),
          store.openCount(),
          signalLoss.watching(),
          signalLoss.watermark());
    } catch (RuntimeException e) {
      // A heartbeat that throws would cancel the scheduled task silently, taking the sweep's
      // sibling with it and leaving a service that looks healthy and reports nothing.
      log.warn("heartbeat failed", e);
    }
  }

  private void sweep() {
    try {
      rules.sweepForSilence();
    } catch (RuntimeException e) {
      // Same reasoning, and it matters more here: scheduleAtFixedRate cancels a task that throws,
      // so an unhandled exception would permanently disable the only rule that cannot be triggered
      // by an event -- and nothing would ever say so.
      log.warn("signal-loss sweep failed", e);
    }
  }

  @Override
  public void destroy() {
    if (timers != null) {
      timers.shutdownNow();
    }
  }
}
