package com.fleettracking.simulator.emit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.SourceSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMessageSinkTest {

  private static final Instant AT = Instant.parse("2026-08-31T14:12:03Z");

  private static SourceMessage json(int n) {
    return SourceMessage.live(SourceSystem.TELEMATICS, "VEH-0001", AT, "{\"seq\":" + n + "}");
  }

  private static SourceMessage edi() {
    return SourceMessage.delayed(
        SourceSystem.EDI_214, "SHP-0001", AT, AT.plusSeconds(3600), "ISA*00*~\nST*214*0001~\n");
  }

  @Test
  @DisplayName("writes one JSON object per line, so a capture can be tailed or split")
  void writesLineDelimitedJson(@TempDir Path dir) throws IOException {
    try (FileMessageSink sink = new FileMessageSink(dir, 100)) {
      sink.accept(json(1));
      sink.accept(json(2));
    }

    assertThat(Files.readAllLines(dir.resolve("telematics.jsonl")))
        .containsExactly("{\"seq\":1}", "{\"seq\":2}");
  }

  @Test
  @DisplayName("gives each EDI interchange its own file, because an interchange is a document")
  void writesOneFilePerInterchange(@TempDir Path dir) throws IOException {
    try (FileMessageSink sink = new FileMessageSink(dir, 100)) {
      sink.accept(edi());
      sink.accept(edi());
    }

    assertThat(dir.resolve("edi-214/interchange-0001.edi")).exists();
    assertThat(dir.resolve("edi-214/interchange-0002.edi")).exists();
    assertThat(Files.readString(dir.resolve("edi-214/interchange-0001.edi"))).startsWith("ISA*");
  }

  @Test
  @DisplayName("caps each feed so a fixture set stays a sample rather than a transcript")
  void capsPerFeed(@TempDir Path dir) throws IOException {
    try (FileMessageSink sink = new FileMessageSink(dir, 3)) {
      for (int i = 1; i <= 10; i++) {
        sink.accept(json(i));
      }
      assertThat(sink.counts()).containsEntry(SourceSystem.TELEMATICS, 3L);
    }

    assertThat(Files.readAllLines(dir.resolve("telematics.jsonl"))).hasSize(3);
  }

  @Test
  @DisplayName("counts each feed separately")
  void countsFeedsSeparately(@TempDir Path dir) {
    try (FileMessageSink sink = new FileMessageSink(dir, 2)) {
      sink.accept(json(1));
      sink.accept(edi());
      sink.accept(edi());
      sink.accept(edi());

      assertThat(sink.counts())
          .containsEntry(SourceSystem.TELEMATICS, 1L)
          .containsEntry(SourceSystem.EDI_214, 2L);
    }
  }

  @Test
  @DisplayName("survives a run that is killed without closing, which is how capture runs end")
  void flushesWithoutClosing(@TempDir Path dir) throws IOException {
    // Deliberately never closed: a JVM killed by Ctrl-C or a timeout runs no shutdown hook, and a
    // capture that only reached disk on close would leave an empty file behind.
    FileMessageSink sink = new FileMessageSink(dir, 100);
    sink.accept(json(1));
    sink.accept(json(2));

    assertThat(Files.readAllLines(dir.resolve("telematics.jsonl"))).hasSize(2);
  }

  @Test
  @DisplayName("creates the capture directory rather than failing on a fresh checkout")
  void createsDirectory(@TempDir Path dir) {
    Path nested = dir.resolve("docs/samples");
    try (FileMessageSink sink = new FileMessageSink(nested, 10)) {
      sink.accept(json(1));
    }
    assertThat(nested).isDirectory();
  }
}
