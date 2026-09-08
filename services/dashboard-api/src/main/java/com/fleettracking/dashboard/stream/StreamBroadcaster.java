package com.fleettracking.dashboard.stream;

import com.fleettracking.events.EventJson;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans one event out to every open browser, without letting any of them hurt the others.
 *
 * <h2>The failure this class is shaped around</h2>
 *
 * <p>The naive version of this is four lines: keep a list of connections, and when a Kafka record
 * arrives, loop over the list and write to each. It works until one viewer is slow — a laptop that
 * has gone to sleep, a phone on a train, a browser tab in the background that the operating system
 * has throttled. Writing to a socket whose receiver has stopped reading blocks once the kernel's
 * buffer fills, and that write is happening on the <em>Kafka listener thread</em>. A blocked
 * listener stops polling; a consumer that stops polling is evicted from its group; and the whole
 * service goes silent for everybody because one person closed their lid.
 *
 * <p>So nothing about a subscriber is allowed to touch the thread that produces. Each subscriber has
 * a bounded queue and its own writer, and {@link #publish} does nothing but offer into those queues
 * and return. A queue that is full discards its oldest entry rather than waiting — the newest
 * position of a truck is worth more than the one before it, and a viewer who has fallen behind wants
 * to catch up to the present rather than replay the past.
 *
 * <p>This is the same rule the simulator's HTTP sink follows for the same reason: the tick thread
 * moves every truck, so it must never block on a network write. Both are cases of a producer that
 * has other work to do and a consumer that cannot be trusted to keep up.
 *
 * <h2>One writer per subscriber, on a virtual thread</h2>
 *
 * <p>A single shared writer thread would put one slow socket back in the way of everybody else, one
 * step further along. A platform thread per viewer would cost a megabyte of stack each and cap the
 * service at a few thousand. A virtual thread parks for free while a write blocks, which is exactly
 * the shape of this work: mostly waiting, occasionally writing a few hundred bytes.
 *
 * <h2>Rendered once, sent many times</h2>
 *
 * <p>An update is serialized to JSON in {@link #publish}, before it reaches any queue, so a fleet
 * being watched by ten people is serialized once rather than ten times. The queues hold rendered
 * bytes rather than objects, which also means a subscriber's writer thread does no work that could
 * fail in an interesting way — it writes a string or it discovers the connection is gone.
 */
public class StreamBroadcaster implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(StreamBroadcaster.class);

  /**
   * The emitter's own timeout, effectively never.
   *
   * <p>A dashboard connection is meant to stay open for as long as somebody is looking at the map,
   * which may be all day. Detecting a viewer who has vanished is the keep-alive's job — a comment
   * down a dead socket fails and the subscriber is dropped — rather than a timeout's, because a
   * timeout long enough not to disconnect a healthy viewer is far too long to notice a dead one.
   */
  private static final long NEVER = Long.MAX_VALUE;

  private final int queueCapacity;
  private final int maxSubscribers;
  private final Duration keepAlive;

  private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
  private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();

  private final AtomicLong published = new AtomicLong();
  private final AtomicLong delivered = new AtomicLong();
  private final AtomicLong dropped = new AtomicLong();
  private final AtomicLong refused = new AtomicLong();
  private final AtomicBoolean closed = new AtomicBoolean();

  public StreamBroadcaster(int queueCapacity, int maxSubscribers, Duration keepAlive) {
    this.queueCapacity = queueCapacity;
    this.maxSubscribers = maxSubscribers;
    this.keepAlive = keepAlive;
  }

  /**
   * Opens a connection for one viewer.
   *
   * @return the emitter to return from a controller, or null when this instance is already serving
   *     as many viewers as it is configured to. Refusing the next connection outright is better than
   *     accepting it and degrading every existing one — a viewer who is told no can retry or connect
   *     elsewhere, while a viewer whose map has quietly become jerky has no idea anything is wrong
   */
  public SseEmitter subscribe() {
    return subscribe(new SseEmitter(NEVER));
  }

  /**
   * The same, against an emitter supplied by the caller.
   *
   * <p>A seam for the tests, and a narrow one. The behaviour worth proving here is what happens when
   * a viewer stops reading, and the only way to arrange that is to hand in a connection whose writes
   * block on command. Every other way of testing it — a real socket, a container, a sleep — either
   * cannot produce the case reliably or produces it so slowly that nobody runs the test.
   */
  SseEmitter subscribe(SseEmitter emitter) {
    if (closed.get() || subscribers.size() >= maxSubscribers) {
      refused.incrementAndGet();
      return null;
    }

    Subscriber subscriber = new Subscriber(emitter, new ArrayBlockingQueue<>(queueCapacity));
    subscribers.add(subscriber);

    // All three fire on the container's thread, not the writer's, so they only mark the subscriber
    // finished; the writer notices and exits on its own. Removing the subscriber from inside a
    // callback while its writer is mid-send is the race this avoids.
    emitter.onCompletion(() -> retire(subscriber, "completed"));
    emitter.onTimeout(() -> retire(subscriber, "timed out"));
    emitter.onError(error -> retire(subscriber, "errored: " + error.getMessage()));

    writers.execute(() -> pump(subscriber));
    log.info("stream: a viewer connected, {} now watching", subscribers.size());
    return emitter;
  }

  /**
   * Offers an update to every viewer. Never blocks, and never throws.
   *
   * <p>Called from the Kafka listener threads. Anything that could go wrong with a particular
   * viewer's connection is that viewer's problem and is handled on their own writer thread; from
   * here, a subscriber is a queue that either accepts an entry or loses its oldest one.
   */
  public void publish(LiveUpdate update) {
    if (subscribers.isEmpty()) {
      // Nobody is watching. Serializing for an audience of none is the commonest state of this
      // service, and skipping it here is why an unwatched instance costs almost nothing.
      return;
    }

    Rendered rendered;
    try {
      rendered = new Rendered(update.type(), EventJson.mapper().writeValueAsString(update));
    } catch (RuntimeException unserializable) {
      // Cannot happen for the records this service builds, and if it somehow did, one bad update
      // must not take down the listener that produced it.
      log.warn("stream: could not render a {} update: {}", update.type(), unserializable.toString());
      return;
    }

    published.incrementAndGet();
    for (Subscriber subscriber : subscribers) {
      if (!subscriber.queue.offer(rendered)) {
        // Full. Discard the oldest and take the newest: a viewer who has fallen behind wants to
        // catch up with where the trucks are now, not to be walked through where they were.
        subscriber.queue.poll();
        subscriber.queue.offer(rendered);
        dropped.incrementAndGet();
      }
    }
  }

  /** How many viewers are connected to this instance. */
  public int subscriberCount() {
    return subscribers.size();
  }

  /** Updates offered to the subscribers. */
  public long publishedCount() {
    return published.get();
  }

  /** Updates actually written to a connection. One update to three viewers counts three. */
  public long deliveredCount() {
    return delivered.get();
  }

  /** Updates discarded because a viewer could not keep up. */
  public long droppedCount() {
    return dropped.get();
  }

  /** Connections refused because this instance was already full. */
  public long refusedCount() {
    return refused.get();
  }

  /** A one-line summary for the heartbeat. */
  public String summary() {
    return "viewers=" + subscribers.size()
        + " published=" + published.get()
        + " delivered=" + delivered.get()
        + " dropped=" + dropped.get()
        + " refused=" + refused.get();
  }

  @Override
  public void close() {
    closed.set(true);
    for (Subscriber subscriber : subscribers) {
      subscriber.finished.set(true);
      try {
        subscriber.emitter.complete();
      } catch (RuntimeException alreadyGone) {
        // A connection that has already failed does not need to be told the service is stopping.
      }
    }
    subscribers.clear();
    writers.shutdownNow();
  }

  /**
   * One viewer's writer loop.
   *
   * <p>The wait on the queue doubles as the keep-alive timer: when nothing has arrived within the
   * interval, a comment goes down the connection instead. That is a single mechanism doing two jobs
   * — it stops proxies closing an idle stream, and it is how a viewer who has disappeared without
   * closing the socket is eventually noticed, because the comment is the write that fails.
   */
  private void pump(Subscriber subscriber) {
    try {
      while (!subscriber.finished.get() && !closed.get()) {
        Rendered next = subscriber.queue.poll(keepAlive.toMillis(), TimeUnit.MILLISECONDS);
        if (next == null) {
          subscriber.emitter.send(SseEmitter.event().comment("keep-alive"));
          continue;
        }
        subscriber.emitter.send(
            SseEmitter.event().name(next.event()).data(next.json(), org.springframework.http.MediaType.APPLICATION_JSON));
        delivered.incrementAndGet();
      }
    } catch (InterruptedException stopping) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException gone) {
      // The viewer closed the tab, the network went away, or the container has already completed
      // the response. All of them mean the same thing and none of them is worth a stack trace.
      retire(subscriber, "write failed: " + gone.getClass().getSimpleName());
      return;
    }
    retire(subscriber, "stopped");
  }

  private void retire(Subscriber subscriber, String why) {
    if (subscriber.finished.compareAndSet(false, true) && subscribers.remove(subscriber)) {
      log.info("stream: a viewer {}, {} still watching", why, subscribers.size());
    }
  }

  /** An update already turned into bytes, with the SSE event name to send it under. */
  private record Rendered(String event, String json) {}

  private record Subscriber(
      SseEmitter emitter, BlockingQueue<Rendered> queue, AtomicBoolean finished) {

    Subscriber(SseEmitter emitter, BlockingQueue<Rendered> queue) {
      this(emitter, queue, new AtomicBoolean());
    }
  }
}
