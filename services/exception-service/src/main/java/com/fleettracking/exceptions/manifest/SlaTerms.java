package com.fleettracking.exceptions.manifest;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a customer committed to for one shipment, read out of the manifest a rule cannot otherwise
 * understand.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>A manifest body is deliberately untyped: the four freight modes share no fields, and a fifth
 * customer must cost an inserted schema document rather than a release. That is the whole argument
 * of S12, and it is not being conceded here. But two of the five SLA rules need a number out of
 * that body — the temperature band a cold-chain load must stay inside, and the window a
 * distribution centre booked — and a rule that reaches into an open map has to know <em>where</em>
 * to reach.
 *
 * <h2>Reserved paths, not a mapping table and not a class per customer</h2>
 *
 * <p>The platform reserves a small number of paths inside the body. A customer who wants a term
 * enforced puts it at the reserved path; a customer who does not simply has no such field, and the
 * rules that need it stay quiet for that shipment. Nothing here knows the name of a customer, and
 * onboarding a fifth one is still a single inserted schema document.
 *
 * <p>Two alternatives were rejected in S13:
 *
 * <ul>
 *   <li><b>A seeded table mapping customer and mode to a JSON path.</b> Fully general — a customer
 *       could call the field anything. It also creates a second contract per customer that has to
 *       be kept in step with the first, and the failure when they drift is silent: the rule finds
 *       nothing at the stale path and reports a healthy shipment for ever.
 *   <li><b>An extractor class per customer.</b> Type-safe, obvious to read, and it makes onboarding
 *       a customer a code change and a deployment — precisely the cost S12 exists to remove.
 * </ul>
 *
 * <p>What a reserved path gives up is compile-time enforcement, so it is enforced at build time
 * instead: {@code ReservedPathsTest} reads the committed schemas in {@code docs/schemas/manifests/}
 * and fails if one of them declares an SLA term somewhere other than its reserved path. A customer
 * contract that quietly moves the temperature band therefore breaks the build rather than the
 * cold chain.
 *
 * <h2>Absent is not invalid</h2>
 *
 * <p>Every term here is optional, and a missing one means "this customer made no commitment about
 * that", not "this manifest is broken". A parcel manifest has no temperature band because a parcel
 * has no reefer. Validating that would be validating a manifest against rules its own schema never
 * claimed — the schema is the contract, and it has already been enforced on write by the shipment
 * service.
 *
 * @param shipmentId the load these terms belong to
 * @param customerId whose commitment this is. Carried for the log line and nothing else — no rule
 *     branches on it, which is the property that keeps this design honest
 * @param mode the freight mode, for the same reason
 * @param temperatureBand the range the load must stay inside, when one was committed to
 * @param deliveryWindow when the consignee will accept the load, when one was booked
 */
public record SlaTerms(
    String shipmentId,
    String customerId,
    String mode,
    TemperatureBand temperatureBand,
    DeliveryWindow deliveryWindow) {

  private static final Logger log = LoggerFactory.getLogger(SlaTerms.class);

  /** The reserved path for a cold-chain load's permitted temperature range. */
  public static final String TEMPERATURE = "temperature";

  /** The reserved path for a booked delivery window. */
  public static final String DELIVERY_WINDOW = "deliveryWindow";

  /** The range the load must stay inside, if the customer committed to one. */
  public Optional<TemperatureBand> temperature() {
    return Optional.ofNullable(temperatureBand);
  }

  /** When the consignee will accept the load, if a window was booked. */
  public Optional<DeliveryWindow> delivery() {
    return Optional.ofNullable(deliveryWindow);
  }

  /**
   * Whether this load is temperature-controlled.
   *
   * <p>Answered by whether a band was committed to, not by the freight mode. That is the more
   * useful question and the more durable one: a retail customer who starts shipping chilled goods
   * declares a band and is treated as cold chain from that moment, with no change here. It is used
   * to decide severity — a truck that has stopped moving is a delay for dry freight and a countdown
   * for a reefer.
   */
  public boolean coldChain() {
    return temperatureBand != null;
  }

  /**
   * Reads the reserved paths out of one manifest body.
   *
   * <p>Anything unreadable is skipped with a warning rather than thrown. A malformed date in a
   * customer's body must not stall a Kafka partition: the manifest passed that customer's schema on
   * write, so a value that cannot be parsed here means the schema permits something this platform
   * did not anticipate, which is a gap to fix in daylight rather than a reason to stop consuming.
   */
  public static SlaTerms from(
      String shipmentId, String customerId, String mode, Map<String, Object> body) {
    Map<String, Object> safeBody = body == null ? Map.of() : body;
    return new SlaTerms(
        shipmentId,
        customerId,
        mode,
        temperatureBand(shipmentId, section(safeBody, TEMPERATURE)),
        deliveryWindow(shipmentId, section(safeBody, DELIVERY_WINDOW)));
  }

  private static TemperatureBand temperatureBand(String shipmentId, Map<String, Object> section) {
    if (section == null) {
      return null;
    }
    Double min = number(section.get("minC"));
    Double max = number(section.get("maxC"));
    if (min == null || max == null) {
      log.warn("manifest for {} declares a temperature section with no minC/maxC", shipmentId);
      return null;
    }
    Double toleranceMinutes = number(section.get("excursionToleranceMinutes"));
    return new TemperatureBand(
        min,
        max,
        toleranceMinutes == null ? null : Duration.ofMinutes(toleranceMinutes.longValue()));
  }

  private static DeliveryWindow deliveryWindow(String shipmentId, Map<String, Object> section) {
    if (section == null) {
      return null;
    }
    Instant opens = instant(shipmentId, section.get("opensAt"));
    Instant closes = instant(shipmentId, section.get("closesAt"));
    if (closes == null) {
      // opensAt alone commits to nothing this platform can breach; closesAt is the deadline.
      return null;
    }
    return new DeliveryWindow(opens, closes);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> section(Map<String, Object> body, String key) {
    Object value = body.get(key);
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
  }

  private static Double number(Object value) {
    return value instanceof Number n ? n.doubleValue() : null;
  }

  private static Instant instant(String shipmentId, Object value) {
    if (value instanceof Instant already) {
      // Mongo stores a date-time as a BSON date, which the driver hands back as a java.util.Date
      // and the mapper as an Instant. A body that came straight from a request is still a string.
      return already;
    }
    if (value instanceof java.util.Date date) {
      return date.toInstant();
    }
    if (value instanceof String text) {
      try {
        return Instant.parse(text);
      } catch (DateTimeParseException malformed) {
        log.warn("manifest for {} carries an unparseable window instant '{}'", shipmentId, text);
        return null;
      }
    }
    return null;
  }
}
