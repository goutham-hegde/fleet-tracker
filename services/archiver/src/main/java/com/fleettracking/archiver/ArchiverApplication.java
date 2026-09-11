package com.fleettracking.archiver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The platform's first write to somewhere other than this laptop.
 *
 * <p>Every other service keeps what it learns in MongoDB or on a Kafka topic, both of which live in
 * one Kind node and go when it goes: Kafka keeps 24 hours, and recreating the cluster keeps
 * nothing. This service copies the four canonical topics into S3 as compressed, hour-partitioned
 * files, so what the platform received outlives the cluster that received it.
 *
 * <p>It runs in one of two modes, chosen by {@code fleet.archiver.mode}:
 *
 * <ul>
 *   <li>{@code archive} (the default): a long-running consumer, deployed like the others. See
 *       {@link com.fleettracking.archiver.archive.ArchiveLoop}.
 *   <li>{@code replay}: a run-to-completion job that reads an hour range back out of S3. See
 *       {@link com.fleettracking.archiver.replay.Replay}.
 * </ul>
 *
 * <p>One image for both, because the two must agree exactly on the object layout and the line
 * format, and the surest way to keep a writer and its reader in step is for them to be compiled
 * together. They run as <em>different identities</em>, though: the archiver may only write and the
 * replay may only read, so a compromised archiver cannot read the archive back out and a replay
 * cannot overwrite it.
 */
@SpringBootApplication
public class ArchiverApplication {

  public static void main(String[] args) {
    ConfigurableApplicationContext context = SpringApplication.run(ArchiverApplication.class, args);
    if ("replay".equals(context.getEnvironment().getProperty("fleet.archiver.mode"))) {
      // A replay is finished once its runner returns. Exit explicitly, with the code the runner
      // reported, so that a Kubernetes Job records failure as failure rather than as a pod that
      // happened to stop.
      System.exit(SpringApplication.exit(context));
    }
  }
}
