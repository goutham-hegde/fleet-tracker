package com.fleettracking.archiver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fleettracking.archiver.archive.ArchiveKeys;
import com.fleettracking.archiver.archive.ArchiveLoop;
import com.fleettracking.archiver.replay.Replay;
import com.fleettracking.archiver.store.ArchiveStore;
import com.fleettracking.events.Topics;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The whole archiver against a real broker and an S3 emulator: records in, hour-partitioned files
 * out, offsets committed no further than what is in S3, and replay reading it all back.
 *
 * <p>This is M8's second exit criterion in miniature. The emulator stands in for S3 only; what it
 * cannot stand in for is IAM, which is proven against the real account instead.
 */
@SpringBootTest(
    properties = {
      "fleet.archiver.bucket=" + ArchiverIT.BUCKET,
      "fleet.archiver.quiet-period=2s",
      "fleet.archiver.poll-timeout=200ms",
      "fleet.archiver.retry-backoff=1s",
    })
@Testcontainers
class ArchiverIT {

  static final String BUCKET = "fleet-archive-it";

  @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

  @Container
  static final GenericContainer<?> S3 =
      new GenericContainer<>("adobe/s3mock:5.2.0")
          .withExposedPorts(9090)
          .waitingFor(Wait.forHttp("/").forPort(9090).forStatusCode(200));

  static {
    // The emulator accepts any credentials, but the SDK refuses to sign a request without some.
    System.setProperty("aws.accessKeyId", "it");
    System.setProperty("aws.secretAccessKey", "it");
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry registry) {
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("fleet.archiver.endpoint", ArchiverIT::s3Endpoint);
  }

  static String s3Endpoint() {
    return "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
  }

  /** Two hours ago, so its hour has certainly ended. */
  static final Instant PAST_HOUR = ArchiveKeys.hourOf(Instant.now()).minus(Duration.ofHours(2));

  @BeforeAll
  static void topicsAndBucket() throws Exception {
    Properties admin = new Properties();
    admin.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    try (Admin a = Admin.create(admin)) {
      a.createTopics(
              List.of(
                  new NewTopic(Topics.POSITION, 12, (short) 1),
                  new NewTopic(Topics.STATUS, 3, (short) 1),
                  new NewTopic(Topics.DERIVED, 6, (short) 1),
                  new NewTopic(Topics.EXCEPTIONS, 3, (short) 1),
                  new NewTopic("replayed.it", 12, (short) 1)))
          .all()
          .get();
    }
    try (S3Client s3 =
        S3Client.builder()
            .region(software.amazon.awssdk.regions.Region.AP_SOUTH_1)
            .endpointOverride(URI.create(s3Endpoint()))
            .forcePathStyle(true)
            .build()) {
      s3.createBucket(b -> b.bucket(BUCKET));
    }
  }

  @Autowired ArchiveStore store;
  @Autowired S3Client s3;
  @Autowired Replay replay;

  private static String event(String eventId, String shipmentId) {
    return "{\"type\":\"position\",\"eventId\":\"" + eventId + "\",\"shipmentId\":\"" + shipmentId + "\"}";
  }

  @Test
  void archivesEachTopicUnderItsHourCommitsNoFurtherAndReplaysItBack() throws Exception {
    // The destination is asserted, not assumed: a test that writes and reads back is satisfied by
    // any store at all, which is how S8's integration test once passed against the wrong database.
    assertThat(s3.serviceClientConfiguration().endpointOverride()).contains(URI.create(s3Endpoint()));

    Map<String, Long> sent = new HashMap<>();
    List<ProducerRecord<String, String>> records = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      String shipment = "SHP-IT-" + (i % 5);
      records.add(record(Topics.POSITION, null, shipment, event("pos-" + i, shipment), PAST_HOUR.plusSeconds(i * 60L)));
    }
    for (int i = 0; i < 6; i++) {
      records.add(record(Topics.STATUS, null, "SHP-IT-1", event("st-" + i, "SHP-IT-1"), PAST_HOUR.plusSeconds(i)));
    }
    for (int i = 0; i < 4; i++) {
      records.add(record(Topics.DERIVED, null, "SHP-IT-2", event("dv-" + i, "SHP-IT-2"), PAST_HOUR.plusSeconds(i)));
    }
    records.add(record(Topics.EXCEPTIONS, null, "SHP-IT-3", event("ex-0", "SHP-IT-3"), PAST_HOUR.plusSeconds(5)));
    records.forEach(r -> sent.merge(r.topic(), 1L, Long::sum));

    // Something a replay already published: carries the header, and must not be archived again.
    ProducerRecord<String, String> replayed =
        record(Topics.POSITION, null, "SHP-IT-0", event("already-archived", "SHP-IT-0"), PAST_HOUR.plusSeconds(3));
    replayed.headers().add(ArchiveLoop.REPLAYED_HEADER, "some/key".getBytes(StandardCharsets.UTF_8));

    // And one record whose hour has not ended, on a partition of its own choosing, published after
    // everything else there. Its file must stay open, so its partition may not be committed past it.
    int openPartition = 5;
    ProducerRecord<String, String> current =
        new ProducerRecord<>(
            Topics.POSITION, openPartition, Instant.now().plus(Duration.ofMinutes(30)).toEpochMilli(),
            "SHP-IT-NOW", event("pos-now", "SHP-IT-NOW"));

    long currentOffset;
    try (KafkaProducer<String, String> producer = producer()) {
      for (ProducerRecord<String, String> r : records) {
        producer.send(r).get();
      }
      producer.send(replayed).get();
      currentOffset = producer.send(current).get().offset();
    }

    // Each topic's past hour becomes exactly one file, holding exactly what was sent.
    for (String topic : List.of(Topics.POSITION, Topics.STATUS, Topics.DERIVED, Topics.EXCEPTIONS)) {
      String prefix = ArchiveKeys.hourPrefix("archive", topic, PAST_HOUR);
      await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(store.list(prefix)).hasSize(1));
      Replay.Report report = replay.run(new ArchiverProperties.Replay(topic, PAST_HOUR, null, null));
      assertThat(report.ok()).as(topic).isTrue();
      assertThat(report.lines()).as(topic).isEqualTo(sent.get(topic));
      assertThat(report.distinct()).as(topic).isEqualTo(sent.get(topic));
      assertThat(report.misplaced()).isZero();
    }

    // The unfinished hour has no file yet.
    String currentPrefix =
        ArchiveKeys.hourPrefix("archive", Topics.POSITION, Instant.now().plus(Duration.ofMinutes(30)));
    assertThat(store.list(currentPrefix)).isEmpty();

    // Committed offsets: every partition fully, except the one whose record is still only in memory,
    // which stops exactly at that record.
    Map<TopicPartition, OffsetAndMetadata> committed = committedOffsets();
    assertThat(committed.get(new TopicPartition(Topics.POSITION, openPartition)).offset())
        .isEqualTo(currentOffset);
    // Offsets start at zero, so the committed offsets across a topic's partitions add up to the
    // records committed past. Positions: all 40, plus the passed-over replay, and not the open one.
    assertThat(committedTotal(committed, Topics.POSITION)).isEqualTo(41);
    assertThat(committedTotal(committed, Topics.STATUS)).isEqualTo(6);
    assertThat(committedTotal(committed, Topics.DERIVED)).isEqualTo(4);
    assertThat(committedTotal(committed, Topics.EXCEPTIONS)).isEqualTo(1);

    // Replay onto a topic, keys intact, marked as replayed.
    Replay.Report republished =
        replay.run(new ArchiverProperties.Replay(Topics.POSITION, PAST_HOUR, null, "replayed.it"));
    assertThat(republished.published()).isEqualTo(40);
    List<ConsumerRecord<String, String>> back = consumeAll("replayed.it", 40);
    assertThat(back).hasSize(40);
    assertThat(back).allSatisfy(r -> {
      assertThat(r.key()).startsWith("SHP-IT-");
      assertThat(r.value()).contains("\"shipmentId\":\"" + r.key() + "\"");
      assertThat(r.headers().lastHeader(ArchiveLoop.REPLAYED_HEADER)).isNotNull();
    });
    assertThat(back.stream().map(ConsumerRecord::value)).doesNotContain(event("already-archived", "SHP-IT-0"));
  }

  private static ProducerRecord<String, String> record(
      String topic, Integer partition, String key, String value, Instant timestamp) {
    return new ProducerRecord<>(topic, partition, timestamp.toEpochMilli(), key, value);
  }

  private static KafkaProducer<String, String> producer() {
    Properties p = new Properties();
    p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    return new KafkaProducer<>(p);
  }

  private static long committedTotal(Map<TopicPartition, OffsetAndMetadata> committed, String topic) {
    return committed.entrySet().stream()
        .filter(e -> e.getKey().topic().equals(topic))
        .mapToLong(e -> e.getValue().offset())
        .sum();
  }

  private Map<TopicPartition, OffsetAndMetadata> committedOffsets() throws Exception {
    Properties admin = new Properties();
    admin.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    try (Admin a = Admin.create(admin)) {
      return a.listConsumerGroupOffsets("archiver").partitionsToOffsetAndMetadata().get();
    }
  }

  private static List<ConsumerRecord<String, String>> consumeAll(String topic, int expected) {
    Properties p = new Properties();
    p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    p.put(ConsumerConfig.GROUP_ID_CONFIG, "it-reader");
    p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    List<ConsumerRecord<String, String>> out = new ArrayList<>();
    try (KafkaConsumer<String, String> c = new KafkaConsumer<>(p)) {
      c.subscribe(List.of(topic));
      Instant deadline = Instant.now().plusSeconds(30);
      while (out.size() < expected && Instant.now().isBefore(deadline)) {
        c.poll(Duration.ofMillis(500)).forEach(out::add);
      }
    }
    return Stream.of(out).flatMap(List::stream).toList();
  }
}
