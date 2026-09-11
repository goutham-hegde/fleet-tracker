package com.fleettracking.archiver.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.archiver.ArchiverProperties;
import com.fleettracking.archiver.archive.ArchiveKeys;
import com.fleettracking.archiver.archive.ArchivedRecord;
import com.fleettracking.archiver.archive.Batch;
import com.fleettracking.archiver.archive.Batcher;
import com.fleettracking.archiver.store.ArchiveStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ReplayTest {

  private static final String POS = "position.events.v1";
  private static final Instant H14 = Instant.parse("2026-09-11T14:00:00Z");

  /** The archive as a sorted map, which is all S3 is for these purposes. */
  static final class MemoryStore implements ArchiveStore {
    final TreeMap<String, byte[]> objects = new TreeMap<>();

    @Override
    public void put(String key, byte[] body) {
      objects.put(key, body);
    }

    @Override
    public List<String> list(String prefix) {
      return objects.keySet().stream().filter(k -> k.startsWith(prefix)).toList();
    }

    @Override
    public byte[] get(String key) {
      return objects.get(key);
    }

    @Override
    public String describe() {
      return "memory";
    }
  }

  private final MemoryStore store = new MemoryStore();
  private final Replay replay = new Replay(store, "archive", null);

  private void writeFile(Instant hour, Instant writtenAt, ArchivedRecord... records) {
    Batcher batcher = new Batcher(Duration.ofMinutes(1), 1L << 30, 1L << 30);
    for (ArchivedRecord r : records) {
      batcher.append(r, writtenAt);
    }
    Batch batch = batcher.all().getFirst();
    store.put(
        ArchiveKeys.objectKey("archive", POS, hour, batch.firstPartition(), batch.firstOffset(), writtenAt),
        batch.finish());
  }

  private static ArchivedRecord rec(String eventId, int partition, long offset, Instant ts) {
    return new ArchivedRecord(POS, partition, offset, ts, "SHP-1", "{\"type\":\"position\",\"eventId\":\"" + eventId + "\"}");
  }

  @Test
  void aRecordArchivedTwiceIsCountedOnceByEventId() {
    writeFile(H14, H14.plusSeconds(4000), rec("a", 0, 1, H14.plusSeconds(1)), rec("b", 0, 2, H14.plusSeconds(2)));
    // The same records again, as a restart that wrote but had not committed would produce.
    writeFile(H14, H14.plusSeconds(4100), rec("a", 0, 1, H14.plusSeconds(1)), rec("b", 0, 2, H14.plusSeconds(2)),
        rec("c", 0, 3, H14.plusSeconds(3)));

    Replay.Report report = replay.run(new ArchiverProperties.Replay(POS, H14, null, null));

    assertThat(report.files()).isEqualTo(2);
    assertThat(report.lines()).isEqualTo(5);
    assertThat(report.distinct()).isEqualTo(3);
    assertThat(report.duplicates()).isEqualTo(2);
    assertThat(report.ok()).isTrue();
  }

  @Test
  void aRecordUnderTheWrongHourFailsVerification() {
    // Stamped 15:05 but filed under 14:00.
    writeFile(H14, H14.plusSeconds(4000), rec("a", 0, 1, H14.plus(Duration.ofMinutes(65))));

    Replay.Report report = replay.run(new ArchiverProperties.Replay(POS, H14, null, null));

    assertThat(report.misplaced()).isEqualTo(1);
    assertThat(report.ok()).isFalse();
  }

  @Test
  void anEmptyRangeIsAFailureNotASuccess() {
    Replay.Report report = replay.run(new ArchiverProperties.Replay(POS, H14, null, null));
    assertThat(report.files()).isZero();
    assertThat(report.ok()).isFalse();
  }

  @Test
  void aRangeCoversEachHourItNamesAndNoOther() {
    writeFile(H14, H14.plusSeconds(4000), rec("a", 0, 1, H14.plusSeconds(1)));
    Instant h15 = H14.plus(Duration.ofHours(1));
    writeFile(h15, h15.plusSeconds(4000), rec("b", 0, 2, h15.plusSeconds(1)));
    Instant h16 = H14.plus(Duration.ofHours(2));
    writeFile(h16, h16.plusSeconds(4000), rec("c", 0, 3, h16.plusSeconds(1)));

    Replay.Report report = replay.run(new ArchiverProperties.Replay(POS, H14.plusSeconds(1800), h16, null));

    assertThat(report.from()).isEqualTo(H14);
    assertThat(report.distinct()).isEqualTo(2);
  }

  @Test
  void filesAreOrderedByWhenTheyWereWrittenNotByName() {
    // Offsets 9000.. were written first, 18422.. second.
    Map<String, Long> named =
        Map.of(
            "archive/x/dt=2026-09-11/hour=14/p0-o9000-1000.ndjson.gz", 1000L,
            "archive/x/dt=2026-09-11/hour=14/p0-o18422-2000.ndjson.gz", 2000L);
    named.forEach((key, millis) -> assertThat(Replay.writtenAt(key)).isEqualTo(millis));
    assertThat(named.keySet().stream().sorted().toList().getFirst())
        .as("lexical order would read the later file first")
        .contains("o18422");
  }
}
