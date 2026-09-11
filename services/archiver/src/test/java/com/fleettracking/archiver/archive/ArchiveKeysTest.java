package com.fleettracking.archiver.archive;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ArchiveKeysTest {

  @Test
  void partitionsAreHiveStyleAndUtc() {
    // 19:40 in India is 14:10 UTC. The partition must say 14 whatever zone the pod runs in.
    Instant t = Instant.parse("2026-09-11T14:10:00Z");
    assertThat(ArchiveKeys.hourPrefix("archive", "position.events.v1", t))
        .isEqualTo("archive/position.events.v1/dt=2026-09-11/hour=14/");
  }

  @Test
  void theLastMillisecondOfADayBelongsToThatDay() {
    Instant t = Instant.parse("2026-09-11T23:59:59.999Z");
    assertThat(ArchiveKeys.hourPrefix("archive", "exceptions.v1", t))
        .isEqualTo("archive/exceptions.v1/dt=2026-09-11/hour=23/");
    assertThat(ArchiveKeys.hourPrefix("archive", "exceptions.v1", t.plusMillis(1)))
        .isEqualTo("archive/exceptions.v1/dt=2026-09-12/hour=00/");
  }

  @Test
  void aFileNamesItsFirstRecordAndWhenItWasWritten() {
    Instant hour = Instant.parse("2026-09-11T14:00:00Z");
    Instant writtenAt = Instant.ofEpochMilli(1_789_999_999_123L);
    assertThat(ArchiveKeys.objectKey("archive", "status.events.v1", hour, 3, 18422, writtenAt))
        .isEqualTo("archive/status.events.v1/dt=2026-09-11/hour=14/p3-o18422-1789999999123.ndjson.gz");
  }
}
