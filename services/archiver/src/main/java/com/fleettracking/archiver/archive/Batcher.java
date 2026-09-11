package com.fleettracking.archiver.archive;

import com.fleettracking.events.EventJson;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import tools.jackson.databind.ObjectMapper;

/**
 * Decides which records go in which file and when a file is finished. No Kafka client and no S3
 * client: it is handed records and instants, and answers questions, so every awkward case is a unit
 * test.
 *
 * <p><b>The write pattern is a cost decision before it is anything else.</b> Every file is one S3
 * request, and a request is what the free allowance runs out of first: about 2,000 uploads a
 * month, or a cent's worth. One file per event would spend that in seconds. So the normal case is
 * exactly <b>one file per topic per hour</b>, and a file is closed when:
 *
 * <ol>
 *   <li><b>its hour has ended on the wall clock, and it has received nothing for the quiet
 *       period.</b> Both halves matter. The hour alone would close a file while the records stamped
 *       59:59.9 on another partition were still in flight. Silence alone would close a file every
 *       time a topic paused. And during a catch-up, when every hour being read ended long ago, it is
 *       the silence that keeps a whole backlog hour in one file rather than one file per poll;
 *   <li><b>it has grown past {@code maxObjectSize}</b>, a safety valve rather than a trigger; or
 *   <li><b>all open files together hold more than {@code maxBufferedSize}</b>, when the oldest goes
 *       first. Catching up on a day's backlog interleaves partitions that are hours apart, and
 *       without this each of those hours would be held open at once.
 * </ol>
 *
 * <p>The caller also closes everything at shutdown and when partitions are taken away.
 *
 * <p><b>Offsets are committed only behind the oldest unwritten record.</b> {@link #committable()}
 * answers, per partition, "how far is everything safely in S3": the lowest offset any open file
 * holds for it, or the next unread offset when none does. Committing further would mean a crash
 * resumes past records that were only ever in memory. So a crash can repeat records, in a second
 * file, and can never lose one. That is the same trade every consumer here makes, and the repeat
 * is harmless for the same reason: event ids are derived, and replay drops duplicates by id.
 */
public final class Batcher {

  private record Key(String topic, Instant hour) {}

  private final ObjectMapper mapper = EventJson.mapper();
  private final Duration quietPeriod;
  private final long maxObjectBytes;
  private final long maxBufferedBytes;

  /** Every file not yet in S3, including any whose write is waiting to be retried. */
  private final List<Batch> open = new ArrayList<>();

  /** The file each topic-hour's new records go into. Never a finished one. */
  private final Map<Key, Batch> writable = new LinkedHashMap<>();
  private final Map<TopicPartition, Long> nextOffsets = new HashMap<>();

  public Batcher(Duration quietPeriod, long maxObjectBytes, long maxBufferedBytes) {
    this.quietPeriod = quietPeriod;
    this.maxObjectBytes = maxObjectBytes;
    this.maxBufferedBytes = maxBufferedBytes;
  }

  public void append(ArchivedRecord record, Instant now) {
    Key key = new Key(record.topic(), ArchiveKeys.hourOf(record.timestamp()));
    Batch batch = writable.get(key);
    if (batch == null || batch.finished()) {
      // A finished batch is one whose write is being retried; it must keep its exact bytes, so a
      // straggler for the same hour starts a second file rather than changing the first -- and the
      // first stays in `open` until it is written.
      batch = new Batch(key.topic(), key.hour());
      open.add(batch);
      writable.put(key, batch);
    }
    batch.append(record, mapper.writeValueAsBytes(record), now);
    nextOffsets.put(new TopicPartition(record.topic(), record.partition()), record.offset() + 1);
  }

  /**
   * Moves past a record without archiving it. Its offset still has to count as done, or the
   * committed position would stop behind it for ever.
   */
  public void passOver(String topic, int partition, long offset) {
    nextOffsets.put(new TopicPartition(topic, partition), offset + 1);
  }

  /** The files that should be written now, oldest hour first. */
  public List<Batch> ready(Instant now) {
    List<Batch> ready = new ArrayList<>();
    long retained = 0;
    for (Batch b : open) {
      boolean hourOver = !b.hour().plus(Duration.ofHours(1)).isAfter(now);
      boolean quiet = !b.lastAppendAt().plus(quietPeriod).isAfter(now);
      if ((hourOver && quiet) || b.compressedBytes() >= maxObjectBytes || b.finished()) {
        ready.add(b);
      } else {
        retained += b.compressedBytes();
      }
    }
    if (retained > maxBufferedBytes) {
      List<Batch> byAge =
          open.stream()
              .filter(b -> !ready.contains(b))
              .sorted(Comparator.comparing(Batch::hour))
              .toList();
      for (Batch b : byAge) {
        if (retained <= maxBufferedBytes) {
          break;
        }
        ready.add(b);
        retained -= b.compressedBytes();
      }
    }
    ready.sort(Comparator.comparing(Batch::hour));
    return ready;
  }

  /** Every open file, for shutdown and rebalance. */
  public List<Batch> all() {
    return open.stream().sorted(Comparator.comparing(Batch::hour)).toList();
  }

  /** Called once the file is in S3, and only then. */
  public void written(Batch batch) {
    open.remove(batch);
    writable.remove(new Key(batch.topic(), batch.hour()), batch);
  }

  /**
   * How far each partition may be committed: behind the oldest record still only in memory.
   * Includes every partition this instance has read since it was assigned.
   */
  public Map<TopicPartition, Long> committable() {
    Map<TopicPartition, Long> result = new HashMap<>(nextOffsets);
    for (Batch b : open) {
      for (Map.Entry<Integer, Long> e : b.lowestOffsets().entrySet()) {
        result.merge(new TopicPartition(b.topic(), e.getKey()), e.getValue(), Math::min);
      }
    }
    return result;
  }

  /** Forgets positions in partitions this instance no longer owns. */
  public void forget(Collection<TopicPartition> partitions) {
    partitions.forEach(nextOffsets::remove);
  }

  /** Drops every open file unwritten. The caller must rewind the consumer, or those records are lost. */
  public void discardAll() {
    open.clear();
    writable.clear();
  }

  public int openFiles() {
    return open.size();
  }

  public long bufferedBytes() {
    return open.stream().mapToLong(Batch::compressedBytes).sum();
  }
}
