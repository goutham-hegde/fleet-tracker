package com.fleettracking.publicview.index;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fleettracking.events.Event;
import com.fleettracking.events.EventJson;
import com.fleettracking.events.Topics;
import com.fleettracking.publicview.Clients;
import com.fleettracking.publicview.index.ArchiveFiles.Line;
import com.fleettracking.publicview.store.PublicTable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The indexer: woken by S3 each time the archiver finishes a file, it reads that file once and
 * folds it into the public table.
 *
 * <h2>Why this shape, when the lookup could read the archive itself</h2>
 *
 * <p>Because of what each design makes the bill depend on. A lookup that read the archive would pay
 * for S3 requests on every cache miss, listing included, and a listing costs twelve and a half times
 * what a read does. So the cost would rise with the number of people looking, which on a public
 * address is not something this project controls. Indexing on arrival reads each archive file
 * exactly once, so S3 requests rise only with what the laptop produced. Visitors cost CloudFront,
 * Lambda and DynamoDB requests, all inside allowances that do not expire. And the table's capacity is
 * provisioned, so a flood of visitors is throttled rather than billed.
 *
 * <h2>Which files</h2>
 *
 * <p>Positions, derived events and exceptions. The S3 notification is filtered to those three
 * prefixes, so a status file never wakes this function; the check here is a second line for a file
 * reaching it some other way, such as the backfill script given a wrong prefix.
 *
 * <h2>Two ways in</h2>
 *
 * <p>An S3 notification, which states each key URL-encoded, and a backfill request from
 * {@code scripts/public-backfill.sh}, which states keys as S3 stores them:
 *
 * <pre>
 * {"Records": [{"s3": {"bucket": {"name": "..."}, "object": {"key": "archive%2F..."}}}]}
 * {"bucket": "...", "keys": ["archive/position.events.v1/dt=.../hour=.../p3-o1-17.ndjson.gz"]}
 * </pre>
 *
 * <p>A failure throws, and Lambda retries an asynchronous invocation twice. Retrying is safe because
 * every write the table makes is conditional or disjoint (see {@link PublicTable}).
 */
public final class IndexHandler implements RequestStreamHandler {

  static final Set<String> TOPICS = Set.of(Topics.POSITION, Topics.DERIVED, Topics.EXCEPTIONS);

  /**
   * What one file came to. Printed by the backfill script and logged per invocation.
   *
   * @param written rows the file changed
   * @param older rows the file would have changed, had the table not already held something newer
   * @param unreadable lines whose value was not a canonical event, counted rather than fatal so one
   *     line cannot stop an hour being indexed. On this platform the count should always be zero:
   *     everything archived was validated before it was published
   */
  public record Result(
      String key,
      String topic,
      int lines,
      int events,
      int skipped,
      int unreadable,
      int written,
      int older) {}

  private final S3Client s3;
  private final PublicTable table;
  private final ObjectMapper mapper = EventJson.mapper();

  /** What the Lambda runtime calls: everything from the function's environment. */
  public IndexHandler() {
    this(
        Clients.s3(),
        new PublicTable(Clients.dynamo(), Clients.env("TABLE_NAME"), Clock.systemUTC()));
  }

  public IndexHandler(S3Client s3, PublicTable table) {
    this.s3 = s3;
    this.table = table;
  }

  @Override
  public void handleRequest(InputStream input, OutputStream output, Context context)
      throws IOException {
    JsonNode request = mapper.readTree(input);
    List<Result> results = new ArrayList<>();

    for (JsonNode record : request.path("Records").values()) {
      JsonNode s3 = record.path("s3");
      results.add(
          index(
              s3.path("bucket").path("name").asString(),
              ArchiveFiles.decodeKey(s3.path("object").path("key").asString())));
    }
    for (JsonNode key : request.path("keys").values()) {
      results.add(index(request.path("bucket").asString(), key.asString()));
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("files", results);
    mapper.writeValue(output, response);
  }

  /** Reads one archive file and writes what it folds down to. */
  public Result index(String bucket, String key) {
    String topic = ArchiveFiles.topicOf(key);
    if (!TOPICS.contains(topic)) {
      log("Skipped %s: %s is not indexed", key, topic.isEmpty() ? "no topic" : topic);
      return new Result(key, topic, 0, 0, 0, 0, 0, 0);
    }

    List<Line> lines =
        ArchiveFiles.read(s3.getObjectAsBytes(get -> get.bucket(bucket).key(key)).asByteArray());

    Fold fold = new Fold();
    int unreadable = 0;
    for (Line line : lines) {
      fold.received(line.timestamp());
      try {
        fold.add(mapper.readValue(line.value(), Event.class));
      } catch (JacksonException notAnEvent) {
        unreadable++;
      }
    }

    int written = 0;
    int older = 0;
    for (var p : fold.positions().values()) {
      if (table.position(p)) written++; else older++;
    }
    for (var a : fold.arrivals().values()) {
      if (table.arrival(a)) written++; else older++;
    }
    for (var d : fold.departures().values()) {
      if (table.departure(d)) written++; else older++;
    }
    for (Fold.Incident incident : fold.incidents().values()) {
      // Raise before clear only for tidiness; the two write disjoint attributes, so either order
      // leaves the same row.
      if (incident.raised() != null) {
        written += table.raised(incident.raised()) ? 1 : 0;
      }
      if (incident.cleared() != null) {
        written += table.cleared(incident.cleared()) ? 1 : 0;
      }
    }
    table.watermark(topic, fold.newestReceived(), key);

    Result result =
        new Result(key, topic, lines.size(), fold.events(), fold.skipped(), unreadable, written, older);
    log(
        "Indexed %s: %d line(s), %d event(s), %d skipped, %d unreadable -> %d row(s) written, %d"
            + " already newer",
        key,
        result.lines(),
        result.events(),
        result.skipped(),
        unreadable,
        written,
        older);
    return result;
  }

  /** Standard output is the function's log: the runtime ships each line to CloudWatch. */
  static void log(String format, Object... args) {
    System.out.println(String.format(format, args));
  }
}
