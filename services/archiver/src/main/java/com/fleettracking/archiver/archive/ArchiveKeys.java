package com.fleettracking.archiver.archive;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * The object layout, which is the archive's only schema.
 *
 * <pre>
 * archive/position.events.v1/dt=2026-09-11/hour=14/p3-o18422-1757599322117.ndjson.gz
 * </pre>
 *
 * <p><b>{@code dt=} and {@code hour=} are the Hive convention</b>, which Athena, Spark and every other
 * tool that reads a data lake recognise as partition columns without being told. Nothing reads them
 * that way yet, but it costs nothing to name them the way a future reader expects.
 *
 * <p><b>The hour is UTC and is the hour Kafka recorded the record, not the hour it describes.</b>
 * That is ingestion time, chosen over event time in S21 because the simulator's clock runs 150
 * times faster than the wall's: one real hour of a time-scaled fleet spans about 150 simulated
 * hours, and partitioning by the event's own instant would open 150 files per topic per real hour
 * and spend a month's S3 request allowance in an afternoon. It is also the conventional choice for
 * a raw archive, since a partition that has closed never receives late data. Every archived event
 * still carries its own {@code occurredAt}. UTC because a partition name must not move when the
 * clocks do; for this project's lanes, hour 14 is 19:30 in India.
 *
 * <p><b>The file name is never reused.</b> It names the first record in the file, which makes a file
 * traceable to an offset, and the wall-clock instant it was written, which makes it unique. A name
 * derived from content alone would let a redelivered batch overwrite the file it duplicates, and
 * the two batches need not hold the same records: an overwrite can quietly drop records whose
 * offsets were already committed. A duplicate file loses nothing, and replay removes duplicates by
 * event id.
 */
public final class ArchiveKeys {

  private static final DateTimeFormatter DAY =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter HOUR =
      DateTimeFormatter.ofPattern("HH").withZone(ZoneOffset.UTC);

  private ArchiveKeys() {}

  /** The start of the UTC hour containing this instant. */
  public static Instant hourOf(Instant instant) {
    return instant.truncatedTo(ChronoUnit.HOURS);
  }

  /** Everything archived from one topic during one hour sits under this prefix. */
  public static String hourPrefix(String prefix, String topic, Instant hour) {
    Instant h = hourOf(hour);
    return prefix + "/" + topic + "/dt=" + DAY.format(h) + "/hour=" + HOUR.format(h) + "/";
  }

  public static String objectKey(
      String prefix, String topic, Instant hour, int partition, long offset, Instant writtenAt) {
    return hourPrefix(prefix, topic, hour)
        + "p" + partition + "-o" + offset + "-" + writtenAt.toEpochMilli() + ".ndjson.gz";
  }
}
