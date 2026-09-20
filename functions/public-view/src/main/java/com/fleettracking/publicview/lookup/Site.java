package com.fleettracking.publicview.lookup;

import com.fleettracking.publicview.lookup.LookupHandler.Response;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * The dashboard's own files, served by the lookup function.
 *
 * <h2>Why a function serves files at all</h2>
 *
 * <p>ADR 0003 puts CloudFront in front of two origins: the site bucket for the page, the lookup
 * function for its data. CloudFront is refused until AWS verifies the account, and that verification
 * has no date on it. A Lambda function URL is HTTPS on its own, with a certificate and a name, so
 * the function can answer for both origins and the public view exists today.
 *
 * <p>This is a smaller thing than the distribution it stands in for, and the difference is the
 * point of ADR 0003: with no cache in front, every page view is an invocation. What survives is the
 * property the ADR actually argues for -- an archive file is read once, when it lands, and never per
 * view -- because the table is still what answers questions. What is given up is the edge.
 *
 * <h2>The cache moves inside</h2>
 *
 * <p>An execution environment holds what it has fetched for {@link #TTL}, so a warm function serves
 * the page from memory and S3 sees roughly one GET per file per environment per five minutes rather
 * than one per view. That is a weaker guarantee than CloudFront's -- a cold environment refetches,
 * and there may be several at once -- but it is the difference between a few hundred GETs a day and
 * a few thousand, and S3 GETs are the one thing here billed per request.
 *
 * <p>The cache is bounded by bytes rather than entries, because the sizes are so unequal: an
 * index.html is a couple of kilobytes and MapLibre's worker is close to a megabyte. Past the bound
 * it is emptied rather than evicted one by one. A build is a few megabytes against 1 GB of function
 * memory, so in practice the bound is never reached; it exists so that a misconfiguration pointing
 * at a bucket full of something else cannot exhaust the heap.
 *
 * <h2>What it will not serve</h2>
 *
 * <p>Keys are taken from the request path, so the path is checked rather than trusted: no {@code ..}
 * segment, no backslash, no leading slash after the prefix is stripped. S3 keys are flat and a
 * traversal cannot escape a bucket, but the bucket holds one build and a request should not be able
 * to name anything outside it.
 *
 * <p>A path that misses and has no file extension is answered with index.html, which is what makes a
 * deep link work in a single-page app. A path that misses and looks like a file -- a stale asset
 * name from a cached page, say -- is a 404, because answering HTML to a request for JavaScript
 * produces a syntax error in the browser instead of a missing file in the network tab.
 */
final class Site {

  /** How long an execution environment may reuse a file it has already fetched. */
  static final Duration TTL = Duration.ofMinutes(5);

  /** Past this, the whole cache is dropped. A dashboard build is a few megabytes. */
  static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;

  /** What a browser is told when the object itself carries no instruction. */
  private static final String DEFAULT_CACHE_CONTROL = "public, max-age=60";

  private static final Map<String, String> CONTENT_TYPES = contentTypes();

  private record Cached(Instant storedAt, Response response) {}

  private final S3Client s3;
  private final String bucket;
  private final Clock clock;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();
  private final AtomicLong cachedBytes = new AtomicLong();

  Site(S3Client s3, String bucket, Clock clock) {
    this.s3 = s3;
    this.bucket = bucket;
    this.clock = clock;
  }

  /**
   * The page, or one of its files. Never throws for a missing file; a failure to reach S3 is left to
   * the handler, which turns it into a 503 that is not cached.
   */
  Response serve(String path) {
    String key = keyFor(path);
    if (key == null) {
      return notFound();
    }
    Cached hit = cache.get(key);
    Instant now = clock.instant();
    if (hit != null && Duration.between(hit.storedAt(), now).compareTo(TTL) < 0) {
      return hit.response();
    }
    Response fetched = fetch(key);
    if (fetched == null) {
      // A miss that could be a route rather than a file: the app's own router should decide.
      return key.contains(".") ? notFound() : indexOr404(now);
    }
    remember(key, fetched, now);
    return fetched;
  }

  /** index.html under another name, so a deep link renders the app instead of an error. */
  private Response indexOr404(Instant now) {
    Cached hit = cache.get("index.html");
    if (hit != null && Duration.between(hit.storedAt(), now).compareTo(TTL) < 0) {
      return hit.response();
    }
    Response index = fetch("index.html");
    if (index == null) {
      return notFound();
    }
    remember("index.html", index, now);
    return index;
  }

  private void remember(String key, Response response, Instant now) {
    if (cachedBytes.get() > MAX_CACHE_BYTES) {
      cache.clear();
      cachedBytes.set(0);
    }
    cache.put(key, new Cached(now, response));
    cachedBytes.addAndGet(response.body().length());
  }

  /** Null when the object is not there. Anything else propagates: it is not a 404. */
  private Response fetch(String key) {
    ResponseBytes<GetObjectResponse> object;
    try {
      object = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (NoSuchKeyException e) {
      return null;
    }
    byte[] bytes = object.asByteArray();
    String contentType = contentTypeFor(key, object.response().contentType());
    // The publish script states cache-control per file as it uploads -- a year for hashed assets, a
    // minute for index.html. Repeating those rules here would be a second place to change them.
    String cacheControl =
        object.response().cacheControl() == null
            ? DEFAULT_CACHE_CONTROL
            : object.response().cacheControl();
    Map<String, String> headers =
        Map.of("content-type", contentType, "cache-control", cacheControl);
    return isText(contentType)
        ? new Response(200, headers, new String(bytes, StandardCharsets.UTF_8), false)
        : new Response(200, headers, Base64.getEncoder().encodeToString(bytes), true);
  }

  /**
   * The key this path asks for, or null if the path has no business naming a key.
   *
   * <p>The bucket holds the build at its root, so the request path minus its leading slash is the
   * key, and the root itself is index.html.
   */
  private static String keyFor(String path) {
    String key = path.startsWith("/") ? path.substring(1) : path;
    if (key.isEmpty() || key.endsWith("/")) {
      key = key + "index.html";
    }
    if (key.contains("..") || key.contains("\\") || key.startsWith("/")) {
      return null;
    }
    return key;
  }

  /**
   * S3 reports what was set at upload time, which the sync does get right for the common types; the
   * table is what answers when it does not, and what keeps a JavaScript module from arriving as
   * {@code application/octet-stream} -- which a browser refuses to execute.
   */
  private static String contentTypeFor(String key, String reported) {
    int dot = key.lastIndexOf('.');
    String extension = dot < 0 ? "" : key.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    String known = CONTENT_TYPES.get(extension);
    if (known != null) {
      return known;
    }
    return reported == null || reported.isBlank() ? "application/octet-stream" : reported;
  }

  /** Text goes back as text; everything else is base64, which the function URL then decodes. */
  private static boolean isText(String contentType) {
    return contentType.startsWith("text/")
        || contentType.startsWith("application/json")
        || contentType.startsWith("application/javascript")
        || contentType.startsWith("image/svg+xml");
  }

  private static Response notFound() {
    return new Response(
        404,
        Map.of("content-type", "text/plain; charset=utf-8", "cache-control", "public, max-age=60"),
        "not found",
        false);
  }

  private static Map<String, String> contentTypes() {
    Map<String, String> types = new LinkedHashMap<>();
    types.put("html", "text/html; charset=utf-8");
    types.put("js", "application/javascript; charset=utf-8");
    types.put("mjs", "application/javascript; charset=utf-8");
    types.put("css", "text/css; charset=utf-8");
    types.put("json", "application/json; charset=utf-8");
    types.put("svg", "image/svg+xml");
    types.put("png", "image/png");
    types.put("jpg", "image/jpeg");
    types.put("jpeg", "image/jpeg");
    types.put("gif", "image/gif");
    types.put("webp", "image/webp");
    types.put("ico", "image/x-icon");
    types.put("woff", "font/woff");
    types.put("woff2", "font/woff2");
    types.put("ttf", "font/ttf");
    types.put("txt", "text/plain; charset=utf-8");
    types.put("map", "application/json; charset=utf-8");
    types.put("webmanifest", "application/manifest+json");
    return Map.copyOf(types);
  }
}
