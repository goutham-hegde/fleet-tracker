package com.fleettracking.archiver.replay;

import com.fleettracking.archiver.ArchiverProperties;
import com.fleettracking.archiver.archive.ArchiveKeys;
import com.fleettracking.archiver.archive.ArchiveLoop;
import com.fleettracking.archiver.archive.ArchivedRecord;
import com.fleettracking.archiver.store.ArchiveStore;
import com.fleettracking.events.EventJson;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads an hour range of one topic back out of the archive.
 *
 * <p>Always verifies; republishes only when given a target topic. Verifying means: every line
 * parses, every record sits under the hour partition its own Kafka timestamp names, and every
 * record carries an event id. Duplicates are expected, not failures, since the archiver writes a
 * record twice rather than risk writing it never; they are counted and dropped by <b>event id</b>.
 * Not by partition and offset, which are only unique within one cluster's lifetime: a recreated
 * cluster numbers its offsets from zero again, and two different events archived a week apart can
 * share a partition and an offset.
 *
 * <p><b>Files within an hour are read in the order they were written</b>, the instant at the end of
 * each name. A shipment's records all sit in one partition, and each file of an hour was finished
 * before the next was started, so write order is partition order. Reading in name order would not
 * be: {@code o18422} sorts before {@code o9000}.
 *
 * <p><b>Republishing keeps the original key</b>, the shipment id, so a shipment's events land on one
 * partition in order, exactly as the gateway put them there. Each carries the
 * {@value ArchiveLoop#REPLAYED_HEADER} header naming the file it came from, which is how the
 * archiver knows to pass it over. Replaying onto a canonical topic makes this the second writer to
 * those topics after the gateway; that is safe only because every event on them was validated
 * before it was ever archived, and it is an operator's decision rather than a default. Consumers
 * reading live traffic at the same time will see old events; the ones here refuse to let an older
 * event move state backwards, which is the same defence against the mobile app's backlogs.
 */
public final class Replay {

  private static final Logger log = LoggerFactory.getLogger(Replay.class);

  private final ArchiveStore store;
  private final String prefix;
  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper mapper = EventJson.mapper();

  public Replay(ArchiveStore store, String prefix, KafkaTemplate<String, String> kafka) {
    this.store = store;
    this.prefix = prefix;
    this.kafka = kafka;
  }

  /** The outcome of one run. {@code ok()} is what the process exit code reports. */
  public record Report(
      String topic,
      Instant from,
      Instant to,
      int files,
      long lines,
      long distinct,
      long duplicates,
      long misplaced,
      long withoutEventId,
      long published) {

    public boolean ok() {
      return files > 0 && misplaced == 0 && withoutEventId == 0;
    }
  }

  public Report run(ArchiverProperties.Replay request) {
    if (request.topic() == null || request.from() == null) {
      throw new IllegalArgumentException(
          "a replay needs fleet.archiver.replay.topic and fleet.archiver.replay.from");
    }
    Instant from = ArchiveKeys.hourOf(request.from());
    Instant to =
        request.to() == null ? from.plus(Duration.ofHours(1)) : ArchiveKeys.hourOf(request.to());
    String target = request.target();

    Set<String> seen = new HashSet<>();
    List<CompletableFuture<?>> sends = new ArrayList<>();
    int files = 0;
    long lines = 0;
    long duplicates = 0;
    long misplaced = 0;
    long withoutEventId = 0;

    for (Instant hour = from; hour.isBefore(to); hour = hour.plus(Duration.ofHours(1))) {
      List<String> keys = new ArrayList<>(store.list(ArchiveKeys.hourPrefix(prefix, request.topic(), hour)));
      keys.sort(Comparator.comparingLong(Replay::writtenAt).thenComparing(Comparator.naturalOrder()));
      for (String key : keys) {
        files++;
        for (String line : lines(store.get(key))) {
          lines++;
          ArchivedRecord record = mapper.readValue(line, ArchivedRecord.class);
          if (!ArchiveKeys.hourOf(record.timestamp()).equals(hour)
              || !request.topic().equals(record.topic())) {
            misplaced++;
          }
          String eventId = eventId(record.value());
          if (eventId == null) {
            withoutEventId++;
            continue;
          }
          if (!seen.add(eventId)) {
            duplicates++;
            continue;
          }
          if (target != null) {
            ProducerRecord<String, String> out =
                new ProducerRecord<>(target, record.key(), record.value());
            out.headers().add(ArchiveLoop.REPLAYED_HEADER, key.getBytes(StandardCharsets.UTF_8));
            sends.add(kafka.send(out));
          }
        }
      }
      log.info("Read hour {} of {}: {} file(s)", hour, request.topic(), keys.size());
    }

    // Every send must have been acknowledged before this run may report success.
    CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).join();

    Report report =
        new Report(
            request.topic(),
            from,
            to,
            files,
            lines,
            seen.size(),
            duplicates,
            misplaced,
            withoutEventId,
            sends.size());
    log.info(
        "Replay of {} [{}, {}): {} file(s), {} line(s), {} distinct event(s), {} duplicate(s),"
            + " {} misplaced, {} without an event id, {} published{}",
        report.topic(),
        from,
        to,
        files,
        lines,
        report.distinct(),
        duplicates,
        misplaced,
        withoutEventId,
        report.published(),
        target == null ? " (verify only; no target topic)" : " to " + target);
    if (files == 0) {
      log.error("No archive files under {} for that range", ArchiveKeys.hourPrefix(prefix, request.topic(), from));
    }
    return report;
  }

  private String eventId(String value) {
    JsonNode id = mapper.readTree(value).get("eventId");
    return id == null || !id.isString() ? null : id.stringValue();
  }

  /** The epoch millis at the end of a file name: {@code ...-1757599322117.ndjson.gz}. */
  static long writtenAt(String key) {
    String name = key.substring(key.lastIndexOf('/') + 1);
    int end = name.indexOf(".ndjson.gz");
    int start = name.lastIndexOf('-', end) + 1;
    return Long.parseLong(name.substring(start, end));
  }

  private static List<String> lines(byte[] gzipped) {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(gzipped)), StandardCharsets.UTF_8))) {
      return reader.lines().filter(l -> !l.isEmpty()).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
