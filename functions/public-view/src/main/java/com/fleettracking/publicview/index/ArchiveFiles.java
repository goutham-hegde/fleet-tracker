package com.fleettracking.publicview.index;

import com.fleettracking.events.EventJson;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * How an archive file is laid out, as far as this function needs to know.
 *
 * <p>The writer is S21's archiver. This is a reader's declaration of the same format rather than a
 * dependency on the archiver's classes, for the reason {@code Views.java} gives in the dashboard
 * API: the archiver is a Spring Boot service, and depending on it would compile this function
 * against a service's internals and ship its framework in a Lambda that has no use for it. The
 * format is six fields and a path, and {@code PublicViewIT} writes files the way the archiver does.
 */
public final class ArchiveFiles {

  private ArchiveFiles() {}

  /**
   * One line of an archive file.
   *
   * @param value the exact string Kafka held: a canonical event, which is what gets parsed
   * @param timestamp the Kafka record timestamp, which is when the platform received it. Wall-clock
   *     time, unlike every instant inside {@code value}, and so the right answer to "how recent is
   *     this archive"
   */
  public record Line(
      String topic, int partition, long offset, Instant timestamp, String key, String value) {}

  /** Every line of one gzipped NDJSON file. */
  public static List<Line> read(byte[] gzipped) {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                new GZIPInputStream(new ByteArrayInputStream(gzipped)), StandardCharsets.UTF_8))) {
      return reader.lines()
          .filter(line -> !line.isEmpty())
          .map(line -> EventJson.mapper().readValue(line, Line.class))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The object key as S3 stored it, from the key as an S3 notification states it.
   *
   * <p><b>A notification URL-encodes the key</b>, and this layout guarantees there is something to
   * decode: {@code dt=2026-09-11} arrives as {@code dt%3D2026-09-11}. Asking S3 for the encoded
   * form is a {@code NoSuchKey} for a file that plainly exists, which reads like a race with the
   * writer rather than a string that was never decoded. A space would arrive as {@code +}, which is
   * why this is form decoding and not only percent decoding.
   */
  public static String decodeKey(String notified) {
    return URLDecoder.decode(notified, StandardCharsets.UTF_8);
  }

  /** {@code archive/position.events.v1/dt=.../hour=.../p3-o1-17.ndjson.gz} is a file of that topic. */
  public static String topicOf(String key) {
    String[] parts = key.split("/");
    return parts.length >= 3 ? parts[1] : "";
  }
}
