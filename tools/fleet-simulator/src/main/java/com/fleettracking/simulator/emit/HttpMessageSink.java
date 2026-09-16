package com.fleettracking.simulator.emit;

import com.fleettracking.events.SourceSystem;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Posts each message to the ingest gateway, the way a real device would.
 *
 * <p>This is the sink the {@link MessageSink} interface was built for. Not one line of any emitter
 * changed to add it: the four feeds still format their own payloads and hand them over, and where
 * those payloads go is now a deployment question rather than a code question.
 *
 * <h2>Why it has threads of its own</h2>
 *
 * <p>Emitters run on the tick thread, and that thread also moves every truck. An HTTP round trip
 * takes a few milliseconds at best, so posting inline would mean a fleet of eight trucks spending
 * most of a tick waiting on sockets — and at {@code time-scale: 3000} a tick is supposed to cover
 * fifty simulated minutes. The simulation would slow to whatever the gateway could absorb, and
 * every timestamp it produced would still claim otherwise.
 *
 * <p>So messages go into bounded queues and workers drain them. Bounded, and it <em>drops</em> when
 * full rather than blocking, which is the same decision a real device makes: a telematics unit with
 * no signal does not stop the truck. Dropping is counted and logged, because a silent drop would
 * look exactly like a feed that had nothing to say.
 *
 * <h2>One worker per device, never one device per several workers</h2>
 *
 * <p>A single worker awaits the gateway's answer, which waits on the broker's, before sending the
 * next message: about a hundred messages a second, which is plenty for a demonstration and far too
 * few to find out where the platform stops keeping up. Several workers pulling from one queue would
 * fix the rate and break something else: they would reorder messages in flight, and the mobile
 * app's out-of-order delivery is a property that feed is supposed to own on purpose — having the
 * transport add its own disorder to every feed would make it impossible to tell which was which
 * downstream.
 *
 * <p>So each worker has its own queue, and a message goes to the worker its routing key hashes to.
 * One device's messages always travel through one worker, in the order they were emitted, however
 * many workers there are. Different devices were never ordered relative to each other in the first
 * place: they are different radios. The default is one worker, which is exactly the sink this was
 * before S23 added the others.
 *
 * <h2>What it measures</h2>
 *
 * <p>Every answered request's round trip is counted into a {@link LatencyHistogram}, and with
 * {@code reportEvery} set, one line per interval gives the rate and percentiles of the interval
 * just ended. Round trip here is the gateway's whole job — parse, normalize, resolve identity against
 * MongoDB, and wait for the broker to acknowledge — because the gateway answers only once the event
 * is durable. It is measured with {@link System#nanoTime()}, a stopwatch rather than a clock: the
 * rule that nothing in the simulator reads the wall clock is about simulated time, which this is
 * not.
 */
public class HttpMessageSink implements MessageSink {

  private static final Logger log = LoggerFactory.getLogger(HttpMessageSink.class);

  private static final Map<SourceSystem, String> PATHS = new EnumMap<>(SourceSystem.class);

  static {
    PATHS.put(SourceSystem.TELEMATICS, "/ingest/telematics");
    PATHS.put(SourceSystem.MOBILE_APP, "/ingest/mobile");
    PATHS.put(SourceSystem.EDI_214, "/ingest/edi214");
    PATHS.put(SourceSystem.REEFER_SENSOR, "/ingest/reefer");
  }

  private final String baseUrl;
  private final Duration timeout;
  private final HttpClient client;
  private final List<BlockingQueue<SourceMessage>> queues = new ArrayList<>();
  private final List<Thread> workers = new ArrayList<>();
  private final ScheduledExecutorService reporter;

  private final AtomicLong sent = new AtomicLong();
  private final AtomicLong deadLettered = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong refused = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();

  private final LatencyHistogram total = new LatencyHistogram();
  private final AtomicReference<LatencyHistogram> window =
      new AtomicReference<>(new LatencyHistogram());
  private final long startedNanos = System.nanoTime();
  private volatile long windowStartedNanos = startedNanos;

  private volatile boolean running = true;

  public HttpMessageSink(String baseUrl, Duration timeout, int queueCapacity) {
    this(baseUrl, timeout, queueCapacity, 1, null);
  }

  /**
   * @param queueCapacity messages that may wait in total, shared evenly between the workers
   * @param workerCount how many requests may be in flight at once
   * @param reportEvery how often to log the interval's rate and latency; null or zero for never
   */
  public HttpMessageSink(
      String baseUrl, Duration timeout, int queueCapacity, int workerCount, Duration reportEvery) {
    if (workerCount < 1) {
      throw new IllegalArgumentException("workerCount must be at least 1: " + workerCount);
    }
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.timeout = timeout;
    this.client = HttpClient.newBuilder().connectTimeout(timeout).build();

    int perWorker = Math.max(1, Math.ceilDiv(queueCapacity, workerCount));
    for (int i = 0; i < workerCount; i++) {
      BlockingQueue<SourceMessage> queue = new ArrayBlockingQueue<>(perWorker);
      queues.add(queue);
      // Daemon threads: the simulation's own tick thread is what keeps the JVM alive, and a sink
      // that could outlive it would stop Ctrl-C from ending the process.
      Thread worker = new Thread(() -> drain(queue), "http-sink-" + i);
      worker.setDaemon(true);
      worker.start();
      workers.add(worker);
    }

    if (reportEvery != null && reportEvery.isPositive()) {
      reporter =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "http-sink-report");
                t.setDaemon(true);
                return t;
              });
      long millis = reportEvery.toMillis();
      reporter.scheduleWithFixedDelay(this::reportWindow, millis, millis, TimeUnit.MILLISECONDS);
    } else {
      reporter = null;
    }
    log.info("posting emitted messages to {} with {} worker(s)", this.baseUrl, workerCount);
  }

  @Override
  public void accept(SourceMessage message) {
    if (!running) {
      return;
    }
    if (!queueFor(message).offer(message)) {
      long total = dropped.incrementAndGet();
      // Logged sparsely: if the gateway is down, every message drops, and a line each would bury
      // the simulation's own output entirely.
      if (total == 1 || total % 100 == 0) {
        log.warn("http sink queue full; {} messages dropped so far", total);
      }
    }
  }

  private BlockingQueue<SourceMessage> queueFor(SourceMessage message) {
    return queues.get(Math.floorMod(Objects.hashCode(message.routingKey()), queues.size()));
  }

  /**
   * Drains one queue until shutdown is requested <em>and</em> nothing is left waiting in it.
   *
   * <p>A timed poll rather than a blocking take, and a flag rather than a poison pill in the queue.
   * A pill has to be enqueued to be seen, and the one moment shutdown matters most — a gateway that
   * has stopped answering, so the queue is full — is exactly the moment there is no room to enqueue
   * it. The worker would then wait on a queue nobody drains while {@code close()} waited on the
   * worker. Polling costs a wakeup every 200 ms and cannot deadlock.
   */
  private void drain(BlockingQueue<SourceMessage> queue) {
    while (running || !queue.isEmpty()) {
      SourceMessage message;
      try {
        message = queue.poll(200, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (message != null) {
        post(message);
      }
    }
  }

  private void post(SourceMessage message) {
    String path = PATHS.get(message.source());
    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(baseUrl + path))
              .timeout(timeout)
              .header("Content-Type", message.contentType())
              .POST(HttpRequest.BodyPublishers.ofString(message.body()))
              .build();
      long began = System.nanoTime();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      long elapsed = System.nanoTime() - began;

      if (response.statusCode() == 202) {
        sent.incrementAndGet();
        total.record(elapsed);
        window.get().record(elapsed);
        // Still a 202: the gateway has done its whole job, which for a message it could not use is
        // putting the original durably on the dead-letter topic. Counted apart all the same, because
        // a load run that reports its throughput as "accepted" while every message is being turned
        // away has measured the speed of the rejection path.
        if (response.body() != null && response.body().contains("\"DEAD_LETTERED\"")) {
          deadLettered.incrementAndGet();
        }
      } else {
        // Includes the 503 a gateway returns for a feed whose normalizer is not written yet, which
        // is expected rather than alarming while M2 is still in progress.
        long total = refused.incrementAndGet();
        if (total == 1 || total % 200 == 0) {
          log.warn(
              "gateway answered {} for {} ({} so far): {}",
              response.statusCode(),
              message.source(),
              total,
              abbreviate(response.body()));
        }
      }
    } catch (java.io.IOException e) {
      long total = failed.incrementAndGet();
      if (total == 1 || total % 100 == 0) {
        log.warn("could not reach gateway ({} failures so far): {}", total, e.getMessage());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * One line for the interval just ended. Key=value pairs, so a script can read it back.
   *
   * <p>The window's histogram is swapped for a fresh one rather than cleared, so a worker recording
   * into it at that moment writes into one or the other and never into a half-cleared array.
   */
  private void reportWindow() {
    long now = System.nanoTime();
    LatencyHistogram ended = window.getAndSet(new LatencyHistogram());
    double seconds = (now - windowStartedNanos) / 1e9;
    windowStartedNanos = now;
    log.info("load {}", line("window", seconds, ended));
  }

  private String line(String label, double seconds, LatencyHistogram histogram) {
    int queued = queues.stream().mapToInt(BlockingQueue::size).sum();
    return String.format(
        Locale.ROOT,
        "%s=%.1fs accepted=%d rate=%.1f/s p50=%s p99=%s max=%s"
            + " deadLettered=%d refused=%d unreachable=%d dropped=%d queued=%d",
        label,
        seconds,
        histogram.count(),
        seconds > 0 ? histogram.count() / seconds : 0.0,
        millis(histogram.percentile(0.50)),
        millis(histogram.percentile(0.99)),
        millis(histogram.max()),
        deadLettered.get(),
        refused.get(),
        failed.get(),
        dropped.get(),
        queued);
  }

  private static String millis(Duration duration) {
    return String.format(Locale.ROOT, "%.1fms", duration.toNanos() / 1e6);
  }

  @Override
  public void close() {
    if (!running) {
      return;
    }
    running = false;
    if (reporter != null) {
      reporter.shutdownNow();
    }
    // Let whatever is already queued go out before the JVM exits, but do not wait forever for a
    // gateway that is not answering. Anything still queued after this is counted as dropped.
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    for (Thread worker : workers) {
      try {
        worker.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      worker.interrupt();
    }
    dropped.addAndGet(queues.stream().mapToInt(BlockingQueue::size).sum());
    log.info(
        "http sink closed: {} accepted, {} refused, {} unreachable, {} dropped unsent",
        sent.get(),
        refused.get(),
        failed.get(),
        dropped.get());
    log.info("load {}", line("total", (System.nanoTime() - startedNanos) / 1e9, total));
  }

  /** Messages accepted by the gateway with a 202. Test seam. */
  public long sent() {
    return sent.get();
  }

  /** Of those, the ones the gateway accepted by dead-lettering them. Test seam. */
  public long deadLettered() {
    return deadLettered.get();
  }

  /** Messages the gateway answered with anything else. Test seam. */
  public long refused() {
    return refused.get();
  }

  /** Messages never offered to the gateway because the queue was full. Test seam. */
  public long dropped() {
    return dropped.get();
  }

  /** Round trip below which this share of accepted requests completed. Test seam. */
  Duration percentile(double quantile) {
    return total.percentile(quantile);
  }

  private static String abbreviate(String body) {
    if (body == null) {
      return "";
    }
    String flat = body.replace('\n', ' ');
    return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
  }
}
