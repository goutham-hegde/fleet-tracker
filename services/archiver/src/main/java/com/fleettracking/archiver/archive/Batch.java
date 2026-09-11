package com.fleettracking.archiver.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * One archive file while it is still being written: the records of one topic in one hour.
 *
 * <p><b>Compressed as it goes, and held in memory only in compressed form.</b> A position envelope is
 * several hundred bytes of JSON that is almost identical to the one before it, which is the best
 * case gzip has; the file an hour of positions makes is a fraction of the records it holds, and
 * that fraction is all that sits on the heap.
 *
 * <p>It remembers, per Kafka partition, the lowest offset it holds. Until this file is safely in S3,
 * no offset at or beyond that one may be committed, because a restart would resume after records
 * that exist nowhere but in this object's memory.
 */
public final class Batch {

  private final String topic;
  private final Instant hour;
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final GZIPOutputStream gzip;
  private final Map<Integer, Long> lowestOffsets = new HashMap<>();

  private int count;
  private long uncompressedBytes;
  private int firstPartition = -1;
  private long firstOffset = -1;
  private Instant lastAppendAt;
  private boolean finished;

  Batch(String topic, Instant hour) {
    this.topic = topic;
    this.hour = hour;
    try {
      this.gzip = new GZIPOutputStream(bytes, 64 * 1024);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  void append(ArchivedRecord record, byte[] line, Instant now) {
    if (finished) {
      throw new IllegalStateException("append to a finished batch");
    }
    try {
      gzip.write(line);
      gzip.write('\n');
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (count == 0) {
      firstPartition = record.partition();
      firstOffset = record.offset();
    }
    lowestOffsets.merge(record.partition(), record.offset(), Math::min);
    count++;
    uncompressedBytes += line.length + 1;
    lastAppendAt = now;
  }

  /**
   * Completes the gzip stream and returns the file. Idempotent, so a write S3 refused can be retried
   * with the identical bytes.
   */
  public byte[] finish() {
    if (!finished) {
      try {
        gzip.finish();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      finished = true;
    }
    return bytes.toByteArray();
  }

  public String topic() {
    return topic;
  }

  public Instant hour() {
    return hour;
  }

  public int count() {
    return count;
  }

  public long uncompressedBytes() {
    return uncompressedBytes;
  }

  /** Approximate until finished: the compressor holds back up to a few tens of kilobytes. */
  public long compressedBytes() {
    return bytes.size();
  }

  public int firstPartition() {
    return firstPartition;
  }

  public long firstOffset() {
    return firstOffset;
  }

  Instant lastAppendAt() {
    return lastAppendAt;
  }

  boolean finished() {
    return finished;
  }

  Map<Integer, Long> lowestOffsets() {
    return Collections.unmodifiableMap(lowestOffsets);
  }
}
