package com.fleettracking.archiver;

import com.fleettracking.archiver.archive.ArchiveLoop;
import com.fleettracking.archiver.replay.Replay;
import com.fleettracking.archiver.store.ArchiveStore;
import com.fleettracking.archiver.store.S3ArchiveStore;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sts.StsClient;

@Configuration
@EnableConfigurationProperties(ArchiverProperties.class)
class ArchiverConfig {

  private static final Logger log = LoggerFactory.getLogger(ArchiverConfig.class);

  /**
   * The S3 client, with no credentials configured here at all.
   *
   * <p>The SDK's default chain looks for them in a fixed order, and in the cluster the one that
   * answers is the <b>web identity</b> provider: the pod's environment names a role
   * ({@code AWS_ROLE_ARN}) and a file ({@code AWS_WEB_IDENTITY_TOKEN_FILE}) where the kubelet keeps
   * a fresh, signed service-account token. The SDK hands that token to STS, receives credentials
   * that last an hour, and repeats before they expire, rereading the file each time because the
   * kubelet rotates it. On a laptop the same chain finds whatever {@code aws login} exported
   * instead. Nothing in this service knows or cares which.
   */
  @Bean(destroyMethod = "close")
  S3Client s3(ArchiverProperties properties) {
    if (properties.bucket() == null || properties.bucket().isBlank()) {
      // Refuse to start. An archiver with nowhere to write would consume the topics, look healthy,
      // and commit nothing, and Kafka keeps only a day: the gap would be discovered after it had
      // become permanent.
      throw new IllegalStateException(
          "fleet.archiver.bucket is not set (FLEET_ARCHIVER_BUCKET). In the cluster it comes from"
              + " the archive-destination ConfigMap, which scripts/aws-link.sh writes");
    }
    var builder =
        S3Client.builder()
            .region(Region.of(properties.region()))
            .httpClientBuilder(UrlConnectionHttpClient.builder());
    if (properties.endpoint() != null) {
      // An emulator. Path-style addressing (host/bucket/key) because an emulator on localhost has
      // no DNS for the virtual-hosted style AWS itself uses (bucket.host/key).
      builder.endpointOverride(properties.endpoint()).forcePathStyle(true);
    }
    return builder.build();
  }

  @Bean
  ArchiveStore archiveStore(S3Client s3, ArchiverProperties properties) {
    return new S3ArchiveStore(s3, properties.bucket());
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnProperty(prefix = "fleet.archiver", name = "mode", havingValue = "archive", matchIfMissing = true)
  ArchiveLoop archiveLoop(
      ConsumerFactory<String, String> consumers,
      ArchiveStore store,
      ArchiverProperties properties,
      ApplicationEventPublisher events,
      Clock clock) {
    return new ArchiveLoop(consumers, store, properties, events, clock);
  }

  /**
   * Asks AWS who this process is, at startup, and logs the answer.
   *
   * <p>The first write to S3 happens when the first hour closes, which may be fifty minutes after the
   * pod started, and a broken trust policy would stay invisible until then. {@code
   * GetCallerIdentity} needs no permission at all, so it tests exactly the half that can be tested
   * without writing anything: that the token was accepted and credentials came back. If it fails,
   * the service refuses to start and the pod's logs name the reason. Skipped against an emulator,
   * which has no STS.
   */
  @Bean
  ApplicationRunner identityCheck(ArchiverProperties properties, ArchiveStore store) {
    return args -> {
      if (properties.endpoint() != null) {
        log.info("Archive store {} at emulator {}; no AWS identity to check", store.describe(), properties.endpoint());
        return;
      }
      try (StsClient sts =
          StsClient.builder()
              .region(Region.of(properties.region()))
              .httpClientBuilder(UrlConnectionHttpClient.builder())
              .build()) {
        log.info("AWS identity: {}", sts.getCallerIdentity().arn());
      }
    };
  }

  @Bean
  Replay replay(ArchiveStore store, ArchiverProperties properties, KafkaTemplate<String, String> kafka) {
    return new Replay(store, properties.prefix(), kafka);
  }

  /** Replay mode: one run, and an exit code that says whether it verified. */
  @Bean
  @ConditionalOnProperty(prefix = "fleet.archiver", name = "mode", havingValue = "replay")
  ReplayRunner replayRunner(Replay replay, ArchiverProperties properties) {
    return new ReplayRunner(replay, properties);
  }

  static final class ReplayRunner implements ApplicationRunner, ExitCodeGenerator {
    private final Replay replay;
    private final ArchiverProperties properties;
    private int exitCode = 1;

    ReplayRunner(Replay replay, ArchiverProperties properties) {
      this.replay = replay;
      this.properties = properties;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
      exitCode = replay.run(properties.replay()).ok() ? 0 : 1;
    }

    @Override
    public int getExitCode() {
      return exitCode;
    }
  }
}
