package com.fleettracking.archiver.archive;

import com.fleettracking.archiver.ArchiverProperties;
import com.fleettracking.archiver.store.ArchiveStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * The archiver: a Kafka consumer loop, written by hand.
 *
 * <p><b>Why not a {@code @KafkaListener} like every other consumer here.</b> A listener is handed a
 * record or a batch, processes it, and its offsets are committed when it returns. That fits a
 * service whose unit of work is one event. Here the unit of work is an hour: a record is not done
 * when it has been read, or when it has been compressed into a buffer, but when the file holding
 * it is in S3, perhaps fifty minutes later and several thousand polls on. Expressing "commit this
 * partition only up to the oldest record still in memory" through a listener means holding
 * acknowledgements across invocations, which Spring Kafka permits and which obscures the one
 * thing this class exists to get right. So the loop is written out: poll, append, write what is
 * finished, commit what is safe. Spring Boot still builds the consumer, from the same
 * {@code spring.kafka.consumer.*} properties as everywhere else.
 *
 * <p><b>When S3 refuses a write, the loop keeps polling and stops fetching.</b> Retrying inside the
 * loop without polling would hold the consumer silent past {@code max.poll.interval.ms}, and Kafka
 * would evict it from its group, which is a rebalance nobody asked for. Retrying while still
 * fetching would keep filling memory with records that have nowhere to go. {@code pause} is the
 * third way: polls continue, the consumer stays a member of its group, and no partition returns
 * anything until the write goes through. The cases this covers are real ones for a laptop: the
 * network drops, or the credentials cannot be renewed because the trust has changed.
 *
 * <p><b>Records a replay published are passed over</b>, identified by the
 * {@value #REPLAYED_HEADER} header. They are already in the archive, which is where the replay read
 * them from; archiving them again would double what is stored under a new hour, with nothing
 * gained.
 */
public final class ArchiveLoop implements SmartLifecycle {

  public static final String REPLAYED_HEADER = "fleet.replayed-from";

  private static final Logger log = LoggerFactory.getLogger(ArchiveLoop.class);
  private static final Duration STATUS_INTERVAL = Duration.ofMinutes(5);

  private final ConsumerFactory<String, String> consumers;
  private final ArchiveStore store;
  private final ArchiverProperties properties;
  private final ApplicationEventPublisher events;
  private final Clock clock;
  private final Batcher batcher;

  private final AtomicLong recordsWritten = new AtomicLong();
  private final AtomicLong filesWritten = new AtomicLong();
  private final AtomicLong bytesWritten = new AtomicLong();
  private final AtomicLong passedOver = new AtomicLong();

  private volatile boolean running;
  private Thread thread;
  private Consumer<String, String> consumer;
  private boolean paused;
  private Instant retryAt = Instant.MIN;
  private Instant lastStatusAt;

  public ArchiveLoop(
      ConsumerFactory<String, String> consumers,
      ArchiveStore store,
      ArchiverProperties properties,
      ApplicationEventPublisher events,
      Clock clock) {
    this.consumers = consumers;
    this.store = store;
    this.properties = properties;
    this.events = events;
    this.clock = clock;
    this.batcher =
        new Batcher(
            properties.quietPeriod(),
            properties.maxObjectSize().toBytes(),
            properties.maxBufferedSize().toBytes());
  }

  @Override
  public void start() {
    running = true;
    // Not a daemon: this thread is the service, and the JVM should not consider itself finished
    // while it runs. The servlet container would keep the process alive anyway; this does not rely
    // on that.
    thread = Thread.ofPlatform().name("archiver").daemon(false).start(this::run);
  }

  @Override
  public void stop() {
    running = false;
    try {
      thread.join(Duration.ofSeconds(60));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void run() {
    lastStatusAt = clock.instant();
    try (Consumer<String, String> c = consumers.createConsumer()) {
      consumer = c;
      c.subscribe(properties.topics(), new Rebalance());
      log.info(
          "Archiving {} to {} under {}/ as group {}",
          properties.topics(),
          store.describe(),
          properties.prefix(),
          c.groupMetadata().groupId());

      while (running) {
        ConsumerRecords<String, String> records = c.poll(properties.pollTimeout());
        Instant now = clock.instant();
        for (ConsumerRecord<String, String> r : records) {
          if (r.headers().lastHeader(REPLAYED_HEADER) != null) {
            batcher.passOver(r.topic(), r.partition(), r.offset());
            passedOver.incrementAndGet();
            continue;
          }
          batcher.append(
              new ArchivedRecord(
                  r.topic(),
                  r.partition(),
                  r.offset(),
                  Instant.ofEpochMilli(r.timestamp()),
                  r.key(),
                  r.value()),
              now);
        }
        if (!now.isBefore(retryAt)) {
          if (writeAll(batcher.ready(now), now)) {
            resume();
          }
        }
        status(now);
      }

      // Stopping: write what is open, once, so a restart does not re-read an hour it has already
      // buffered. If S3 refuses, nothing is lost: those offsets were never committed.
      Instant now = clock.instant();
      writeAll(batcher.all(), now);
      log.info("Archiver stopped; {} file(s) left unwritten", batcher.openFiles());
    } catch (RuntimeException e) {
      // The loop is the whole service. If it has died, the pod should be restarted rather than
      // left answering its health checks with nothing behind them.
      log.error("Archive loop failed; reporting liveness as broken", e);
      AvailabilityChangeEvent.publish(events, this, LivenessState.BROKEN);
    } finally {
      running = false;
    }
  }

  /**
   * Writes each batch in order, then commits whatever is now safe. Returns false as soon as one
   * write fails, having paused fetching and scheduled a retry.
   */
  private boolean writeAll(List<Batch> batches, Instant now) {
    boolean wroteAny = false;
    for (Batch batch : batches) {
      try {
        write(batch, now);
        wroteAny = true;
      } catch (RuntimeException e) {
        log.warn(
            "S3 refused {} for {}; pausing consumption and retrying in {}: {}",
            batch.topic(),
            batch.hour(),
            properties.retryBackoff(),
            e.toString());
        retryAt = now.plus(properties.retryBackoff());
        pause();
        if (wroteAny) {
          commit();
        }
        return false;
      }
    }
    if (wroteAny) {
      commit();
    }
    return true;
  }

  private void write(Batch batch, Instant now) {
    byte[] body = batch.finish();
    String key =
        ArchiveKeys.objectKey(
            properties.prefix(),
            batch.topic(),
            batch.hour(),
            batch.firstPartition(),
            batch.firstOffset(),
            now);
    store.put(key, body);
    batcher.written(batch);
    recordsWritten.addAndGet(batch.count());
    filesWritten.incrementAndGet();
    bytesWritten.addAndGet(body.length);
    log.info(
        "Archived {} record(s) of {} for hour {} as {} ({} KB from {} KB)",
        batch.count(),
        batch.topic(),
        batch.hour(),
        key,
        body.length / 1024,
        batch.uncompressedBytes() / 1024);
  }

  /** Commits, for every partition this consumer still owns, up to the oldest unwritten record. */
  private void commit() {
    Set<TopicPartition> owned = consumer.assignment();
    Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
    batcher.committable().forEach(
        (tp, offset) -> {
          if (owned.contains(tp)) {
            offsets.put(tp, new OffsetAndMetadata(offset));
          }
        });
    if (offsets.isEmpty()) {
      return;
    }
    try {
      consumer.commitSync(offsets);
    } catch (KafkaException e) {
      // Usually a rebalance in progress. The files are in S3 regardless; the worst outcome is that
      // the next owner re-archives those records into a second file.
      log.warn("Offset commit failed; records may be archived twice: {}", e.toString());
    }
  }

  private void pause() {
    if (!paused) {
      consumer.pause(consumer.assignment());
      paused = true;
    }
  }

  private void resume() {
    if (paused) {
      consumer.resume(consumer.paused());
      paused = false;
      log.info("S3 accepting writes again; consumption resumed");
    }
  }

  private void status(Instant now) {
    if (Duration.between(lastStatusAt, now).compareTo(STATUS_INTERVAL) < 0) {
      return;
    }
    lastStatusAt = now;
    log.info(
        "Archiver: {} record(s) in {} file(s), {} KB written; {} file(s) open holding {} KB;"
            + " {} replayed record(s) passed over{}",
        recordsWritten.get(),
        filesWritten.get(),
        bytesWritten.get() / 1024,
        batcher.openFiles(),
        batcher.bufferedBytes() / 1024,
        passedOver.get(),
        paused ? "; PAUSED, S3 refusing writes" : "");
  }

  /**
   * What happens when Kafka moves partitions between consumers. Runs on the loop's own thread,
   * inside {@code poll}, so it needs no locking.
   */
  private final class Rebalance implements ConsumerRebalanceListener {

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
      if (partitions.isEmpty()) {
        return;
      }
      // Write everything once, even files for partitions being kept: a file can hold records from
      // several partitions, and it is simpler to finish them all than to split one.
      boolean ok = writeAll(batcher.all(), clock.instant());
      if (!ok) {
        rewind(partitions);
      }
      batcher.forget(partitions);
    }

    @Override
    public void onPartitionsLost(Collection<TopicPartition> partitions) {
      // Lost, not revoked: another consumer may already own them, so nothing may be committed.
      rewind(partitions);
      batcher.forget(partitions);
    }

    @Override
    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
      if (paused) {
        consumer.pause(partitions);
      }
    }

    /**
     * Drops every unwritten file and moves each partition this consumer keeps back to the first
     * record those files held, so they are read again. Revoked partitions are reread by whoever
     * receives them, from the committed offset.
     */
    private void rewind(Collection<TopicPartition> leaving) {
      Map<TopicPartition, Long> safe = batcher.committable();
      int dropped = batcher.openFiles();
      batcher.discardAll();
      Set<TopicPartition> kept = new HashSet<>(consumer.assignment());
      kept.removeAll(leaving);
      for (TopicPartition tp : kept) {
        Long offset = safe.get(tp);
        if (offset != null) {
          consumer.seek(tp, offset);
        }
      }
      if (dropped > 0) {
        log.warn("Dropped {} unwritten file(s) at a rebalance; their records will be read again", dropped);
      }
    }
  }
}
