package com.fleettracking.simulator.emit;

import com.fleettracking.events.SourceSystem;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Posts each message to the ingest gateway, the way a real device would.
 *
 * <p>This is the sink the {@link MessageSink} interface was built for. Not one line of any emitter
 * changed to add it: the four feeds still format their own payloads and hand them over, and where
 * those payloads go is now a deployment question rather than a code question.
 *
 * <h2>Why it has a thread of its own</h2>
 *
 * <p>Emitters run on the tick thread, and that thread also moves every truck. An HTTP round trip
 * takes a few milliseconds at best, so posting inline would mean a fleet of eight trucks spending
 * most of a tick waiting on sockets — and at {@code time-scale: 3000} a tick is supposed to cover
 * fifty simulated minutes. The simulation would slow to whatever the gateway could absorb, and
 * every timestamp it produced would still claim otherwise.
 *
 * <p>So messages go into a bounded queue and a single worker drains it. Bounded, and it
 * <em>drops</em> when full rather than blocking, which is the same decision a real device makes: a
 * telematics unit with no signal does not stop the truck. Dropping is counted and logged, because a
 * silent drop would look exactly like a feed that had nothing to say.
 *
 * <p>One worker rather than a pool, deliberately. Several senders would reorder messages in flight,
 * and the mobile app's out-of-order delivery is a property that feed is supposed to own on purpose —
 * having the transport add its own disorder to every feed would make it impossible to tell which
 * was which downstream.
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
  private final BlockingQueue<SourceMessage> queue;
  private final Thread worker;

  private final AtomicLong sent = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong refused = new AtomicLong();
  private final AtomicLong failed = new AtomicLong();

  private volatile boolean running = true;

  public HttpMessageSink(String baseUrl, Duration timeout, int queueCapacity) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.timeout = timeout;
    this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    this.queue = new ArrayBlockingQueue<>(queueCapacity);

    // A daemon thread: the simulation's own tick thread is what keeps the JVM alive, and a sink
    // that could outlive it would stop Ctrl-C from ending the process.
    this.worker = new Thread(this::drain, "http-sink");
    this.worker.setDaemon(true);
    this.worker.start();
    log.info("posting emitted messages to {}", this.baseUrl);
  }

  @Override
  public void accept(SourceMessage message) {
    if (!running) {
      return;
    }
    if (!queue.offer(message)) {
      long total = dropped.incrementAndGet();
      // Logged sparsely: if the gateway is down, every message drops, and a line each would bury
      // the simulation's own output entirely.
      if (total == 1 || total % 100 == 0) {
        log.warn("http sink queue full; {} messages dropped so far", total);
      }
    }
  }

  /**
   * Drains until shutdown is requested <em>and</em> nothing is left waiting.
   *
   * <p>A timed poll rather than a blocking take, and a flag rather than a poison pill in the queue.
   * A pill has to be enqueued to be seen, and the one moment shutdown matters most — a gateway that
   * has stopped answering, so the queue is full — is exactly the moment there is no room to enqueue
   * it. The worker would then wait on a queue nobody drains while {@code close()} waited on the
   * worker. Polling costs a wakeup every 200 ms and cannot deadlock.
   */
  private void drain() {
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
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 202) {
        sent.incrementAndGet();
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

  @Override
  public void close() {
    if (!running) {
      return;
    }
    running = false;
    // Let whatever is already queued go out before the JVM exits, but do not wait forever for a
    // gateway that is not answering. Anything still queued after this is counted as dropped.
    try {
      worker.join(TimeUnit.SECONDS.toMillis(5));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    worker.interrupt();
    dropped.addAndGet(queue.size());
    log.info(
        "http sink closed: {} accepted, {} refused, {} unreachable, {} dropped unsent",
        sent.get(),
        refused.get(),
        failed.get(),
        dropped.get());
  }

  /** Messages accepted by the gateway with a 202. Test seam. */
  public long sent() {
    return sent.get();
  }

  /** Messages the gateway answered with anything else. Test seam. */
  public long refused() {
    return refused.get();
  }

  /** Messages never offered to the gateway because the queue was full. Test seam. */
  public long dropped() {
    return dropped.get();
  }

  private static String abbreviate(String body) {
    if (body == null) {
      return "";
    }
    String flat = body.replace('\n', ' ');
    return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
  }
}
