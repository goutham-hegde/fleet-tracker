package com.fleettracking.simulator.emit;

import com.fleettracking.events.SourceSystem;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures emitted messages to disk, so that a run produces the contract fixtures M2 is tested
 * against.
 *
 * <p>This is how {@code docs/samples/} gets filled, and the reason it matters is sequencing: the
 * ingest gateway is written in S6, before it has ever seen a live feed. Without captured samples
 * its normalizers would be tested against payloads invented by the same person writing the parser,
 * which tests only self-consistency. Fixtures produced by the simulator are at least an independent
 * statement of what the wire looks like.
 *
 * <h2>Layout</h2>
 *
 * <p>The three JSON feeds append one object per line to {@code <source>.jsonl}. Line-delimited JSON
 * is used rather than one array because a capture can then be truncated, tailed or split without
 * being reparsed, and because it is what the feeds themselves would look like streamed to a file.
 *
 * <p>EDI 214 gets a numbered file per interchange instead. An interchange is a self-contained
 * document with its own envelope, and concatenating several into one file would produce something
 * no EDI parser would accept — the exact opposite of a useful fixture.
 *
 * <p>Capture is <b>capped per feed</b>, with a separate and much lower cap for EDI. The asymmetry
 * is real rather than arbitrary: the three JSON feeds append a line to one file, so a large sample
 * costs nothing but bytes, while EDI writes a whole file per interchange and a large sample would
 * bury the repository in hundreds of near-identical documents. The JSON cap has to be generous
 * enough for the sample to contain the mobile app's reconnect bursts, which only appear over a
 * long enough run and are the single most important behaviour in that fixture set.
 */
public class FileMessageSink implements MessageSink {

  private static final Logger log = LoggerFactory.getLogger(FileMessageSink.class);

  private final Path directory;
  private final long maxPerSource;
  private final long maxInterchanges;
  private final Map<SourceSystem, Long> counts = new EnumMap<>(SourceSystem.class);
  private final Map<SourceSystem, BufferedWriter> writers = new EnumMap<>(SourceSystem.class);
  private boolean cappedAnnounced;

  public FileMessageSink(Path directory, long maxPerSource) {
    this(directory, maxPerSource, Math.min(maxPerSource, 15));
  }

  public FileMessageSink(Path directory, long maxPerSource, long maxInterchanges) {
    this.directory = directory;
    this.maxPerSource = maxPerSource;
    this.maxInterchanges = maxInterchanges;
    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot create capture directory " + directory, e);
    }
    log.info(
        "Capturing up to {} messages per feed ({} EDI interchanges) to {}",
        maxPerSource,
        maxInterchanges,
        directory.toAbsolutePath());
  }

  @Override
  public void accept(SourceMessage message) {
    long written = counts.getOrDefault(message.source(), 0L);
    long cap = message.source() == SourceSystem.EDI_214 ? maxInterchanges : maxPerSource;
    if (written >= cap) {
      announceCapOnce();
      return;
    }
    counts.put(message.source(), written + 1);

    try {
      if (message.source() == SourceSystem.EDI_214) {
        writeInterchange(message, written + 1);
      } else {
        writeJsonLine(message);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("capture failed for " + message.source(), e);
    }
  }

  /** One self-contained document per file: interchange-0001.edi, interchange-0002.edi, ... */
  private void writeInterchange(SourceMessage message, long number) throws IOException {
    Path ediDir = directory.resolve("edi-214");
    Files.createDirectories(ediDir);
    Files.writeString(
        ediDir.resolve("interchange-%04d.edi".formatted(number)),
        message.body(),
        StandardCharsets.UTF_8);
  }

  /**
   * Appends one line. The body must already be a single line of JSON — the emitters write compact
   * JSON precisely so that a capture is line-delimited without any reformatting here.
   */
  private void writeJsonLine(SourceMessage message) throws IOException {
    BufferedWriter writer =
        writers.computeIfAbsent(
            message.source(),
            source -> {
              try {
                return Files.newBufferedWriter(
                    directory.resolve(fileName(source)),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
              } catch (IOException e) {
                throw new UncheckedIOException("cannot open capture file for " + source, e);
              }
            });
    writer.write(message.body());
    writer.newLine();
    // Flushed per line, deliberately. A capture run is normally ended by killing the process --
    // Ctrl-C, or a timeout -- and a JVM terminated that way never runs its shutdown hook, so a
    // buffer that is only flushed on close is simply lost. The failure is silent and looks like a
    // feed that produced nothing: a capped run writes a few kilobytes, never overflows the 8 KB
    // buffer, and leaves an empty file behind. Capture is capped at a few hundred messages and is
    // not a hot path, so the cost of flushing every line is irrelevant next to losing the fixtures.
    writer.flush();
  }

  private static String fileName(SourceSystem source) {
    return switch (source) {
      case TELEMATICS -> "telematics.jsonl";
      case MOBILE_APP -> "mobile-app.jsonl";
      case REEFER_SENSOR -> "reefer-sensor.jsonl";
      case EDI_214 -> throw new IllegalStateException("EDI is written one interchange per file");
    };
  }

  private void announceCapOnce() {
    if (!cappedAnnounced) {
      cappedAnnounced = true;
      log.info("A capture cap has been reached; further messages from that feed are not written");
    }
  }

  @Override
  public void close() {
    for (Map.Entry<SourceSystem, BufferedWriter> entry : writers.entrySet()) {
      try {
        entry.getValue().close();
      } catch (IOException e) {
        log.warn("Failed closing capture file for {}", entry.getKey(), e);
      }
    }
    writers.clear();
    if (!counts.isEmpty()) {
      log.info("Captured {} to {}", counts, directory.toAbsolutePath());
    }
  }

  /** How many messages have been captured per feed. Test seam. */
  public Map<SourceSystem, Long> counts() {
    return Map.copyOf(counts);
  }
}
