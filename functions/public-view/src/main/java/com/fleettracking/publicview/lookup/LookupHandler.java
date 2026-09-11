package com.fleettracking.publicview.lookup;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fleettracking.events.EventJson;
import com.fleettracking.publicview.Clients;
import com.fleettracking.publicview.plan.Plans;
import com.fleettracking.publicview.store.PublicTable;
import com.fleettracking.publicview.store.ShipmentFacts;
import com.fleettracking.publicview.store.ShipmentFacts.Watermark;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The lookup: answers the public page's four questions from the public table.
 *
 * <p>The same four paths as the dashboard API, so the dashboard needs no second client:
 *
 * <pre>
 * GET /api/shipments              every marker
 * GET /api/shipments/{id}         one shipment in full: a query on its partition
 * GET /api/exceptions?open=false  incidents across the fleet
 * GET /api/meta                   what the public view can see, and how recent it is
 * </pre>
 *
 * <p>There is no {@code /api/stream}. The live stream is one connection per viewer held open for as
 * long as they watch, which a function billed by the invocation cannot be and a table updated once an
 * hour has no use for.
 *
 * <h2>Reached only through CloudFront</h2>
 *
 * <p>The function has a URL, and the URL requires AWS signatures. CloudFront signs its requests
 * with origin access control, and the function's resource policy admits that one distribution and
 * nothing else. A request to the bare URL is refused before this code runs. That is what makes the
 * cache in front of it a guarantee rather than a hope: nobody can go around it.
 *
 * <h2>Two caches, for two different floods</h2>
 *
 * <p>Every answer says {@code max-age=60}, so CloudFront answers repeats itself and the function sees
 * at most about one request per path per minute per edge. Behind that, the fleet scan is held in
 * memory for {@link #FLEET_TTL}, so the three fleet-wide paths share one scan between them. A single
 * shipment is looked up on each request, because that query is one partition and cheap; it is the
 * lookup the exit criterion names.
 */
public final class LookupHandler implements RequestStreamHandler {

  static final Duration FLEET_TTL = Duration.ofSeconds(30);
  static final int INCIDENT_LIMIT = 200;

  /** Browsers and CloudFront may reuse an answer for a minute. The table changes hourly. */
  static final String CACHEABLE = "public, max-age=60";

  /** The simulator's ids, and a little room. Anything else is not a shipment and is not queried. */
  private static final Pattern SHIPMENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,63}");

  private record Snapshot(
      Instant takenAt, Map<String, ShipmentFacts> fleet, List<Watermark> watermarks) {}

  /** What goes back to the function URL: its documented response shape. */
  record Response(int statusCode, Map<String, String> headers, String body) {}

  private final PublicTable table;
  private final PublicAssembler assembler;
  private final Clock clock;
  private final ObjectMapper mapper = EventJson.mapper();
  private volatile Snapshot snapshot;

  /** What the Lambda runtime calls: everything from the function's environment. */
  public LookupHandler() {
    this(
        new PublicTable(Clients.dynamo(), Clients.env("TABLE_NAME"), Clock.systemUTC()),
        Plans.packaged(),
        Clock.systemUTC());
  }

  public LookupHandler(PublicTable table, Plans plans, Clock clock) {
    this.table = table;
    this.assembler = new PublicAssembler(plans, clock);
    this.clock = clock;
  }

  @Override
  public void handleRequest(InputStream input, OutputStream output, Context context)
      throws IOException {
    JsonNode request = mapper.readTree(input);
    Response response =
        handle(
            request.path("requestContext").path("http").path("method").asString("GET"),
            request.path("rawPath").asString("/"),
            request.path("queryStringParameters"));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("statusCode", response.statusCode());
    out.put("headers", response.headers());
    out.put("body", response.body());
    out.put("isBase64Encoded", false);
    mapper.writeValue(output, out);
  }

  Response handle(String method, String path, JsonNode query) {
    if (!"GET".equals(method) && !"HEAD".equals(method)) {
      return problem(405, "the public view only answers GET");
    }
    try {
      if (path.equals("/api/shipments")) {
        return ok(assembler.fleet(fleet().fleet().values()));
      }
      if (path.startsWith("/api/shipments/")) {
        String id = URLDecoder.decode(path.substring("/api/shipments/".length()), StandardCharsets.UTF_8);
        if (!SHIPMENT_ID.matcher(id).matches()) {
          return problem(404, "not a shipment id");
        }
        return table.one(id)
            .flatMap(assembler::detail)
            .map(this::ok)
            .orElseGet(() -> problem(404, "the archive has no position for " + id));
      }
      if (path.equals("/api/exceptions")) {
        boolean openOnly = !"false".equals(query.path("open").asString("true"));
        return ok(assembler.incidents(fleet().fleet().values(), openOnly, INCIDENT_LIMIT));
      }
      if (path.equals("/api/meta")) {
        Snapshot s = fleet();
        return ok(assembler.meta(s.fleet().values(), s.watermarks()));
      }
      if (path.equals("/api/stream")) {
        return problem(404, "the public view is an archive and has no live stream");
      }
      return problem(404, "no such path");
    } catch (RuntimeException e) {
      // Logged in full, answered briefly, and never cached: a table that is throttled now can
      // answer in a second, and a cached failure would go on being served for a minute.
      e.printStackTrace(System.out);
      return new Response(
          503,
          headers("no-store"),
          mapper.writeValueAsString(new Wire.Problem(503, "the public table could not be read")));
    }
  }

  /** The whole table, from memory if it was scanned in the last {@link #FLEET_TTL}. */
  private Snapshot fleet() {
    Snapshot s = snapshot;
    Instant now = clock.instant();
    if (s == null || Duration.between(s.takenAt(), now).compareTo(FLEET_TTL) >= 0) {
      s = new Snapshot(now, table.all(), table.watermarks());
      snapshot = s;
    }
    return s;
  }

  private Response ok(Object body) {
    return new Response(200, headers(CACHEABLE), mapper.writeValueAsString(body));
  }

  /**
   * An error, which is cacheable when it is a 404. A request for a shipment that does not exist is
   * as repeatable as one that does, and caching the answer is what stops a stream of made-up ids from
   * turning into a stream of invocations.
   */
  private Response problem(int status, String error) {
    return new Response(
        status,
        headers(status == 404 ? CACHEABLE : "no-store"),
        mapper.writeValueAsString(new Wire.Problem(status, error)));
  }

  private static Map<String, String> headers(String cacheControl) {
    return Map.of("content-type", "application/json", "cache-control", cacheControl);
  }
}
