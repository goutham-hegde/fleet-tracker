package com.fleettracking.publicview.store;

import com.fleettracking.events.ExceptionCleared;
import com.fleettracking.events.ExceptionRaised;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.ShipmentArrived;
import com.fleettracking.events.ShipmentDeparted;
import com.fleettracking.publicview.store.ShipmentFacts.IncidentFacts;
import com.fleettracking.publicview.store.ShipmentFacts.Position;
import com.fleettracking.publicview.store.ShipmentFacts.StopFacts;
import com.fleettracking.publicview.store.ShipmentFacts.Watermark;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * The public table: its layout, every write the indexer makes, and every read the lookup makes.
 *
 * <h2>Layout</h2>
 *
 * <p>One partition per shipment, one row per fact, told apart by the sort key:
 *
 * <pre>
 * shipmentId    fact
 * SHP-HYD-0002  POSITION                the newest fix
 * SHP-HYD-0002  STOP#knl-clinic         arrival and departure at that stop
 * SHP-HYD-0002  INCIDENT#&lt;exceptionId&gt;  one incident, raise and clear merged
 * _archive      position.events.v1      how far that topic has been indexed
 * </pre>
 *
 * <p>Rows per fact rather than one document per shipment, because every write can then be a single
 * conditional update of its own row. A document per shipment would need read-modify-write, and the
 * indexer runs several copies at once whenever the archiver closes an hour: three files, three
 * functions, one shipment, and the last writer silently undoing the other two.
 *
 * <h2>Every write is safe to repeat and safe to reorder</h2>
 *
 * <p>A position or a stop is written only if it is strictly newer, in event time, than what the row
 * holds. An incident's raise and clear write disjoint attributes, so whichever arrives second
 * completes the row instead of overwriting it. Re-indexing a file therefore changes nothing, and
 * indexing an hour before the hour it follows ends in the same table. That is the property that lets
 * {@code scripts/public-backfill.sh} be run as often as anybody likes.
 *
 * <h2>Everything expires</h2>
 *
 * <p>Every write stamps {@code expiresAt} thirty days ahead of the wall clock, and DynamoDB's
 * time-to-live deletes rows past it, at no charge. Thirty days matches how long the archive keeps
 * everything but positions. A shipment that stops being archived leaves the page a month later
 * rather than never. Wall-clock rather than event time: under a time-scaled run the event clock runs
 * days ahead, which would keep a row alive long after anybody could have seen it.
 */
public final class PublicTable {

  public static final String PK = "shipmentId";
  public static final String SK = "fact";
  public static final String TTL = "expiresAt";

  static final String POSITION = "POSITION";
  static final String STOP = "STOP#";
  static final String INCIDENT = "INCIDENT#";
  static final String ARCHIVE = "_archive";

  static final Duration KEEP = Duration.ofDays(30);

  private final DynamoDbClient dynamo;
  private final String table;
  private final Clock clock;

  public PublicTable(DynamoDbClient dynamo, String table, Clock clock) {
    this.dynamo = dynamo;
    this.table = table;
    this.clock = clock;
  }

  public String name() {
    return table;
  }

  // ---------------------------------------------------------------------------------------------
  // Writes. Each returns whether it changed anything; false means the row already held something
  // at least as new, which is normal and not an error.
  // ---------------------------------------------------------------------------------------------

  /** The newest fix for a shipment. Fields the fix lacks are removed, not left from an older one. */
  public boolean position(PositionEvent p) {
    Update u = new Update();
    u.set("eventId", s(p.eventId()))
        .set("vehicleId", s(p.vehicleId()))
        .replace("deviceId", s(p.deviceId()))
        .replace("source", s(p.raw() == null ? null : p.raw().source().name()))
        .set("occurredAt", s(p.occurredAt().toString()))
        .set("occurredAtMillis", n(p.occurredAt().toEpochMilli()))
        .set("receivedAt", s(p.receivedAt().toString()))
        .set("latitude", n(p.position().latitude()))
        .set("longitude", n(p.position().longitude()))
        .replace("speedKph", n(p.speedKph()))
        .replace("headingDegrees", n(p.headingDegrees()))
        .replace("accuracyMeters", n(p.accuracyMeters()));
    return apply(p.shipmentId(), POSITION, u, u.newerThan("occurredAtMillis", p.occurredAt()));
  }

  /** The platform announced an arrival. Only the arrival's own attributes are touched. */
  public boolean arrival(ShipmentArrived a) {
    Update u = new Update();
    u.set("stopId", s(a.stopId()))
        .set("arrivedAt", s(a.occurredAt().toString()))
        .set("arrivedAtMillis", n(a.occurredAt().toEpochMilli()))
        .set("arrivalEventId", s(a.eventId()));
    return apply(a.shipmentId(), STOP + a.stopId(), u, u.newerThan("arrivedAtMillis", a.occurredAt()));
  }

  /** The platform announced a departure. Leaves the arrival's attributes alone. */
  public boolean departure(ShipmentDeparted d) {
    Update u = new Update();
    u.set("stopId", s(d.stopId()))
        .set("departedAt", s(d.occurredAt().toString()))
        .set("departedAtMillis", n(d.occurredAt().toEpochMilli()))
        .set("dwellSeconds", n(d.dwell() == null ? null : d.dwell().toSeconds()))
        .set("departureEventId", s(d.eventId()));
    return apply(
        d.shipmentId(), STOP + d.stopId(), u, u.newerThan("departedAtMillis", d.occurredAt()));
  }

  /** An incident was raised. Writes only what a raise knows, so a clear already here survives. */
  public boolean raised(ExceptionRaised r) {
    Update u = new Update();
    u.set("exceptionId", s(r.exceptionId()))
        .set("exceptionType", s(r.exceptionType().name()))
        .set("severity", s(r.severity().name()))
        .set("detail", s(r.detail()))
        .set("stopId", s(r.stopId()))
        .set("onsetAt", s(r.occurredAt().toString()))
        .set("observedValue", n(r.observedValue()))
        .set("thresholdValue", n(r.thresholdValue()));
    return apply(r.shipmentId(), INCIDENT + r.exceptionId(), u, null);
  }

  /** An incident was cleared. Writes only what a clear knows, so a raise already here survives. */
  public boolean cleared(ExceptionCleared c) {
    Update u = new Update();
    u.set("exceptionId", s(c.exceptionId()))
        .set("exceptionType", s(c.exceptionType().name()))
        .set("onsetAt", s(c.raisedAt().toString()))
        .set("clearedAt", s(c.occurredAt().toString()))
        .set("resolution", s(c.resolution()));
    return apply(c.shipmentId(), INCIDENT + c.exceptionId(), u, null);
  }

  /** Records that a topic's archive has been indexed up to a Kafka timestamp. */
  public boolean watermark(String topic, Instant newestReceived, String key) {
    if (newestReceived == null) {
      return false;
    }
    Update u = new Update();
    u.set("newestReceived", s(newestReceived.toString()))
        .set("newestReceivedMillis", n(newestReceived.toEpochMilli()))
        .set("lastKey", s(key))
        .set("indexedAt", s(clock.instant().toString()));
    return apply(ARCHIVE, topic, u, u.newerThan("newestReceivedMillis", newestReceived));
  }

  private boolean apply(String shipmentId, String fact, Update u, String condition) {
    u.set(TTL, n(clock.instant().plus(KEEP).getEpochSecond()));
    UpdateItemRequest.Builder request =
        UpdateItemRequest.builder()
            .tableName(table)
            .key(Map.of(PK, s(shipmentId), SK, s(fact)))
            .updateExpression(u.expression())
            .expressionAttributeNames(u.names)
            .expressionAttributeValues(u.values);
    if (condition != null) {
      request.conditionExpression(condition);
    }
    try {
      dynamo.updateItem(request.build());
      return true;
    } catch (ConditionalCheckFailedException olderOrEqual) {
      return false;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Reads.
  // ---------------------------------------------------------------------------------------------

  /**
   * Every shipment, from one scan of the whole table.
   *
   * <p>A scan is the right shape here for the reason the dashboard API gives for reading each
   * collection once: the question is about the whole fleet, and the fleet is sixty-odd partitions.
   * It is paged, because a scan returns at most a megabyte per request and a month of incidents
   * could pass that.
   */
  public Map<String, ShipmentFacts> all() {
    Map<String, List<Map<String, AttributeValue>>> rows = new TreeMap<>();
    for (Map<String, AttributeValue> item :
        dynamo.scanPaginator(ScanRequest.builder().tableName(table).build()).items()) {
      String shipmentId = str(item, PK);
      if (shipmentId != null && !shipmentId.equals(ARCHIVE)) {
        rows.computeIfAbsent(shipmentId, k -> new ArrayList<>()).add(item);
      }
    }
    Map<String, ShipmentFacts> fleet = new LinkedHashMap<>();
    rows.forEach((id, items) -> fleet.put(id, facts(id, items)));
    return fleet;
  }

  /** One shipment, from one query on its partition: the lookup. */
  public Optional<ShipmentFacts> one(String shipmentId) {
    List<Map<String, AttributeValue>> items = new ArrayList<>();
    dynamo
        .queryPaginator(
            QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", PK))
                .expressionAttributeValues(Map.of(":pk", s(shipmentId)))
                .build())
        .items()
        .forEach(items::add);
    return items.isEmpty() ? Optional.empty() : Optional.of(facts(shipmentId, items));
  }

  /** How far each topic has been indexed. */
  public List<Watermark> watermarks() {
    List<Watermark> marks = new ArrayList<>();
    dynamo
        .queryPaginator(
            QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("#pk = :pk")
                .expressionAttributeNames(Map.of("#pk", PK))
                .expressionAttributeValues(Map.of(":pk", s(ARCHIVE)))
                .build())
        .items()
        .forEach(
            item ->
                marks.add(
                    new Watermark(
                        str(item, SK),
                        instant(item, "newestReceived"),
                        str(item, "lastKey"),
                        instant(item, "indexedAt"))));
    return marks;
  }

  private static ShipmentFacts facts(String shipmentId, List<Map<String, AttributeValue>> items) {
    Position position = null;
    Map<String, StopFacts> stops = new LinkedHashMap<>();
    List<IncidentFacts> incidents = new ArrayList<>();
    for (Map<String, AttributeValue> item : items) {
      String fact = str(item, SK);
      if (POSITION.equals(fact)) {
        position =
            new Position(
                str(item, "eventId"),
                str(item, "vehicleId"),
                str(item, "deviceId"),
                str(item, "source"),
                instant(item, "occurredAt"),
                instant(item, "receivedAt"),
                dbl(item, "latitude"),
                dbl(item, "longitude"),
                dblOrNull(item, "speedKph"),
                dblOrNull(item, "headingDegrees"),
                dblOrNull(item, "accuracyMeters"));
      } else if (fact != null && fact.startsWith(STOP)) {
        Double dwell = dblOrNull(item, "dwellSeconds");
        StopFacts stop =
            new StopFacts(
                str(item, "stopId"),
                instant(item, "arrivedAt"),
                instant(item, "departedAt"),
                dwell == null ? null : dwell.longValue());
        stops.put(stop.stopId(), stop);
      } else if (fact != null && fact.startsWith(INCIDENT)) {
        incidents.add(
            new IncidentFacts(
                str(item, "exceptionId"),
                shipmentId,
                str(item, "exceptionType"),
                str(item, "severity"),
                str(item, "detail"),
                str(item, "stopId"),
                instant(item, "onsetAt"),
                instant(item, "clearedAt"),
                dblOrNull(item, "observedValue"),
                dblOrNull(item, "thresholdValue"),
                str(item, "resolution")));
      }
    }
    return new ShipmentFacts(shipmentId, position, stops, incidents);
  }

  // ---------------------------------------------------------------------------------------------
  // Attribute plumbing.
  // ---------------------------------------------------------------------------------------------

  /**
   * An update expression under construction.
   *
   * <p>Every attribute name goes through a {@code #placeholder}. That is not ceremony: DynamoDB has
   * several hundred reserved words, and {@code source}, {@code state} and {@code name} are among
   * them, so an expression that spells a name out fails at runtime with a message about syntax.
   */
  static final class Update {
    final Map<String, String> names = new HashMap<>();
    final Map<String, AttributeValue> values = new HashMap<>();
    private final Map<String, String> placeholders = new HashMap<>();
    private final List<String> sets = new ArrayList<>();
    private final List<String> removes = new ArrayList<>();

    /** Sets the attribute, or leaves it untouched when there is no value. */
    Update set(String attribute, AttributeValue value) {
      if (value != null) {
        sets.add(name(attribute) + " = " + value(value));
      }
      return this;
    }

    /** Sets the attribute, or removes it when there is no value. */
    Update replace(String attribute, AttributeValue value) {
      if (value == null) {
        removes.add(name(attribute));
        return this;
      }
      return set(attribute, value);
    }

    /** "Write only if the row has no such instant yet, or an older one." */
    String newerThan(String millisAttribute, Instant instant) {
      String n = name(millisAttribute);
      return "attribute_not_exists(" + n + ") OR " + n + " < " + value(n(instant.toEpochMilli()));
    }

    String expression() {
      String expr = "SET " + String.join(", ", sets);
      return removes.isEmpty() ? expr : expr + " REMOVE " + String.join(", ", removes);
    }

    private String name(String attribute) {
      return placeholders.computeIfAbsent(
          attribute,
          a -> {
            String placeholder = "#a" + placeholders.size();
            names.put(placeholder, a);
            return placeholder;
          });
    }

    private String value(AttributeValue value) {
      String placeholder = ":v" + values.size();
      values.put(placeholder, value);
      return placeholder;
    }
  }

  static AttributeValue s(String value) {
    return value == null ? null : AttributeValue.fromS(value);
  }

  /**
   * A number attribute. Doubles go through {@link BigDecimal} because {@code Double.toString}
   * switches to exponent notation for small and large magnitudes ({@code 1.0E-4}), and a plain
   * decimal is the one spelling every reader of the table accepts.
   */
  static AttributeValue n(Number value) {
    if (value == null) {
      return null;
    }
    String text =
        value instanceof Double || value instanceof Float
            ? BigDecimal.valueOf(value.doubleValue()).toPlainString()
            : value.toString();
    return AttributeValue.fromN(text);
  }

  private static String str(Map<String, AttributeValue> item, String attribute) {
    AttributeValue v = item.get(attribute);
    return v == null ? null : v.s();
  }

  private static Instant instant(Map<String, AttributeValue> item, String attribute) {
    String v = str(item, attribute);
    return v == null ? null : Instant.parse(v);
  }

  private static Double dblOrNull(Map<String, AttributeValue> item, String attribute) {
    AttributeValue v = item.get(attribute);
    return v == null || v.n() == null ? null : Double.valueOf(v.n());
  }

  private static double dbl(Map<String, AttributeValue> item, String attribute) {
    Double v = dblOrNull(item, attribute);
    return v == null ? 0 : v;
  }
}
