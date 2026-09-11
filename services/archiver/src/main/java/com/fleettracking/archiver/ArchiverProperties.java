package com.fleettracking.archiver;

import com.fleettracking.events.Topics;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Where the archive goes and when a file of it is closed.
 *
 * @param mode {@code archive} (consume and write) or {@code replay} (read back and exit)
 * @param bucket the S3 bucket. Required: there is no sensible default, and a service that started
 *     without one would consume the topics and write them nowhere
 * @param prefix the key prefix everything goes under, so the bucket can hold other things later
 * @param region the AWS region of the bucket
 * @param endpoint an S3 endpoint override, for an S3 emulator in tests. Null means real AWS
 * @param topics which topics are archived
 * @param quietPeriod how long an hour's file must have received nothing, after that hour has ended
 *     on the wall clock, before it is closed. Absorbs records timestamped just before the hour that
 *     arrive just after it
 * @param maxObjectSize the compressed size at which an hour's file is closed early. A safety valve,
 *     not the normal trigger
 * @param maxBufferedSize the compressed size of all open files together above which the oldest is
 *     closed early. Bounds memory while catching up on a backlog spanning many hours
 * @param pollTimeout how long one Kafka poll waits for records
 * @param retryBackoff how long to wait before retrying a write S3 refused
 * @param replay what a replay run reads, and where it sends it
 */
@ConfigurationProperties(prefix = "fleet.archiver")
public record ArchiverProperties(
    String mode,
    String bucket,
    String prefix,
    String region,
    URI endpoint,
    List<String> topics,
    Duration quietPeriod,
    DataSize maxObjectSize,
    DataSize maxBufferedSize,
    Duration pollTimeout,
    Duration retryBackoff,
    Replay replay) {

  public ArchiverProperties {
    mode = mode == null ? "archive" : mode;
    prefix = prefix == null ? "archive" : prefix;
    region = region == null ? "ap-south-1" : region;
    topics =
        topics == null || topics.isEmpty()
            ? List.of(Topics.POSITION, Topics.STATUS, Topics.DERIVED, Topics.EXCEPTIONS)
            : List.copyOf(topics);
    quietPeriod = quietPeriod == null ? Duration.ofMinutes(1) : quietPeriod;
    maxObjectSize = maxObjectSize == null ? DataSize.ofMegabytes(32) : maxObjectSize;
    maxBufferedSize = maxBufferedSize == null ? DataSize.ofMegabytes(96) : maxBufferedSize;
    pollTimeout = pollTimeout == null ? Duration.ofSeconds(1) : pollTimeout;
    retryBackoff = retryBackoff == null ? Duration.ofSeconds(30) : retryBackoff;
    replay = replay == null ? new Replay(null, null, null, null) : replay;
  }

  /**
   * One replay run.
   *
   * @param topic the archived topic to read
   * @param from the first hour to read, inclusive; truncated to the hour
   * @param to the hour to stop before, exclusive. Defaults to the hour after {@code from}
   * @param target a Kafka topic to republish onto. Null reads and verifies without publishing
   */
  public record Replay(String topic, Instant from, Instant to, String target) {}
}
