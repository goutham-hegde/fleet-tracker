package com.fleettracking.archiver.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.EventJson;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class BatcherTest {

  private static final String POS = "position.events.v1";
  private static final String STATUS = "status.events.v1";
  private static final Instant H14 = Instant.parse("2026-09-11T14:00:00Z");
  private static final Duration QUIET = Duration.ofMinutes(1);

  private final Batcher batcher = new Batcher(QUIET, 32L << 20, 96L << 20);

  private static ArchivedRecord rec(String topic, int partition, long offset, Instant ts) {
    return new ArchivedRecord(
        topic, partition, offset, ts, "SHP-" + partition, "{\"eventId\":\"" + topic + partition + "-" + offset + "\"}");
  }

  @Test
  void oneFilePerTopicPerHourWhateverThePartition() {
    Instant now = H14.plusSeconds(600);
    batcher.append(rec(POS, 0, 10, H14.plusSeconds(1)), now);
    batcher.append(rec(POS, 7, 99, H14.plusSeconds(2)), now);
    batcher.append(rec(STATUS, 1, 5, H14.plusSeconds(3)), now);

    assertThat(batcher.openFiles()).isEqualTo(2);
    assertThat(batcher.ready(now)).isEmpty();
  }

  @Test
  void aFileClosesOnlyOnceItsHourHasEndedAndGoneQuiet() {
    Instant lastRecord = H14.plus(Duration.ofMinutes(59)).plusSeconds(59);
    batcher.append(rec(POS, 0, 1, lastRecord), lastRecord);

    Instant justAfterTheHour = H14.plus(Duration.ofHours(1)).plusSeconds(10);
    assertThat(batcher.ready(justAfterTheHour))
        .as("hour over, but a record stamped 59:59 on another partition could still be in flight")
        .isEmpty();

    assertThat(batcher.ready(lastRecord.plus(QUIET))).hasSize(1);
  }

  @Test
  void aTopicThatPausesMidHourDoesNotCloseItsFile() {
    Instant t = H14.plusSeconds(60);
    batcher.append(rec(POS, 0, 1, t), t);
    assertThat(batcher.ready(t.plus(Duration.ofMinutes(20)))).isEmpty();
  }

  @Test
  void catchingUpOnAnOldHourKeepsItInOneFile() {
    // Every hour being read ended long ago, so only the silence can say a file is finished. A
    // rule on the hour alone would write a file per poll here: hundreds per backlog hour.
    Instant now = H14.plus(Duration.ofHours(10));
    for (int poll = 0; poll < 50; poll++) {
      Instant pollAt = now.plusMillis(poll * 200L);
      batcher.append(rec(POS, poll % 12, poll, H14.plusSeconds(poll)), pollAt);
      assertThat(batcher.ready(pollAt)).isEmpty();
    }
    assertThat(batcher.ready(now.plus(QUIET).plusSeconds(10))).hasSize(1);
  }

  @Test
  void offsetsAreCommittableOnlyBehindTheOldestUnwrittenRecord() {
    Instant now = H14.plusSeconds(10);
    batcher.append(rec(POS, 0, 100, H14.plusSeconds(1)), now);
    batcher.append(rec(POS, 0, 101, H14.plusSeconds(2)), now);
    batcher.append(rec(POS, 3, 40, H14.plusSeconds(3)), now);

    assertThat(batcher.committable())
        .containsEntry(new TopicPartition(POS, 0), 100L)
        .containsEntry(new TopicPartition(POS, 3), 40L);

    Batch file = batcher.all().getFirst();
    file.finish();
    batcher.written(file);

    assertThat(batcher.committable())
        .containsEntry(new TopicPartition(POS, 0), 102L)
        .containsEntry(new TopicPartition(POS, 3), 41L);
  }

  @Test
  void anUnwrittenOlderHourHoldsBackItsPartitionEvenAfterANewerHourIsWritten() {
    Instant now = H14.plus(Duration.ofHours(2));
    batcher.append(rec(POS, 0, 10, H14.plusSeconds(1)), now);
    batcher.append(rec(POS, 0, 11, H14.plus(Duration.ofHours(1)).plusSeconds(1)), now);

    Batch newer = batcher.all().getLast();
    newer.finish();
    batcher.written(newer);

    assertThat(batcher.committable()).containsEntry(new TopicPartition(POS, 0), 10L);
  }

  @Test
  void aFileWhoseWriteFailedKeepsItsBytesAndAStragglerStartsAnother() {
    Instant now = H14.plusSeconds(10);
    batcher.append(rec(POS, 0, 1, H14.plusSeconds(1)), now);
    Batch first = batcher.all().getFirst();
    byte[] attempted = first.finish(); // the write that S3 refused

    batcher.append(rec(POS, 0, 2, H14.plusSeconds(2)), now);

    assertThat(batcher.openFiles()).isEqualTo(2);
    assertThat(first.finish()).isEqualTo(attempted);
    assertThat(batcher.ready(now)).as("the refused file is retried at once").containsExactly(first);
    assertThat(batcher.committable()).containsEntry(new TopicPartition(POS, 0), 1L);
  }

  @Test
  void aFileClosesEarlyWhenItOutgrowsTheCap() {
    Batcher small = new Batcher(QUIET, 1, 96L << 20);
    Instant now = H14.plusSeconds(10);
    small.append(rec(POS, 0, 1, H14.plusSeconds(1)), now);
    assertThat(small.ready(now)).hasSize(1);
  }

  @Test
  void whenTooMuchIsBufferedTheOldestHourGoesFirst() {
    Batcher tight = new Batcher(QUIET, 32L << 20, 1);
    Instant now = H14.plus(Duration.ofHours(5));
    tight.append(rec(POS, 0, 1, H14.plus(Duration.ofHours(2))), now);
    tight.append(rec(POS, 1, 1, H14), now);
    tight.append(rec(POS, 2, 1, H14.plus(Duration.ofHours(1))), now);

    List<Batch> ready = tight.ready(now);
    assertThat(ready).isNotEmpty();
    assertThat(ready.getFirst().hour()).isEqualTo(H14);
  }

  @Test
  void aPassedOverRecordStillCountsAsDone() {
    batcher.passOver(POS, 4, 70);
    assertThat(batcher.committable()).containsEntry(new TopicPartition(POS, 4), 71L);
  }

  @Test
  void aFileIsGzippedNdjsonHoldingEachValueByteForByte() throws IOException {
    String odd = "{\"eventId\":\"e1\",  \"note\":\"spacing and key order kept\"}";
    Instant now = H14.plusSeconds(10);
    batcher.append(new ArchivedRecord(POS, 2, 7, H14.plusSeconds(1), "SHP-1", odd), now);
    batcher.append(rec(POS, 2, 8, H14.plusSeconds(2)), now);

    byte[] file = batcher.all().getFirst().finish();
    List<String> lines;
    try (var reader =
        new BufferedReader(
            new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(file)), StandardCharsets.UTF_8))) {
      lines = reader.lines().toList();
    }

    assertThat(lines).hasSize(2);
    ArchivedRecord back = EventJson.mapper().readValue(lines.getFirst(), ArchivedRecord.class);
    assertThat(back.value()).isEqualTo(odd);
    assertThat(back.key()).isEqualTo("SHP-1");
    assertThat(back.timestamp()).isEqualTo(H14.plusSeconds(1));
    assertThat(Map.of("p", back.partition(), "o", back.offset())).containsEntry("p", 2).containsEntry("o", 7L);
  }
}
