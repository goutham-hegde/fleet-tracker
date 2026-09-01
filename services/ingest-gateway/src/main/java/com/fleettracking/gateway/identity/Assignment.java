package com.fleettracking.gateway.identity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One dispatch decision: this tractor, pulling this load, wearing this hardware, over this period.
 *
 * <p>The period is the whole reason this moved out of a configuration file. A YAML list can say
 * "VEH-0002 is pulling SHP-LAX-0002" and nothing else; it cannot say that the same tractor pulled a
 * different load yesterday, or that the trailer with probe {@code DEV-0002} bolted to it was
 * swapped onto another vehicle at noon. Freight reference data is a sequence of assignments with
 * start and end times, and a position stamped 09:42 has to resolve against whatever was true at
 * 09:42 — not against whatever is true when the message happens to be read.
 *
 * <p>That distinction is not academic here. The EDI 214 feed is delayed by a filing delay <em>and</em>
 * a batch window, so a status filed for 14:00 can reach the gateway hours later. Resolving it
 * against "now" would attribute a completed load's final arrival to whatever the tractor picked up
 * next.
 *
 * <h2>Validity is half-open: {@code [validFrom, validTo)}</h2>
 *
 * <p>The start instant belongs to the assignment and the end instant does not. That is what makes
 * two consecutive assignments meet exactly without overlapping — the old one's {@code validTo} and
 * the new one's {@code validFrom} are the same instant, and any event is inside exactly one of
 * them. Using a closed interval on both ends would make the changeover instant ambiguous, which is
 * precisely the contradictory reference data this record exists to avoid.
 *
 * <p>A null {@code validTo} means the assignment is open — the load is still running and dispatch
 * has not said when it ends. This is the normal state for a live fleet, so it is the common case
 * rather than an edge one.
 *
 * @param id the document key, derived from the shipment and the start instant so that re-seeding
 *     the same reference data updates rows in place instead of duplicating them
 * @param shipmentId the load, and therefore the Kafka partition key every feed ends up sharing
 * @param vehicleId the tractor pulling it
 * @param deviceIds every device reporting on this vehicle's behalf. A list because a truck carries
 *     more than one box and they do not share an id namespace: the in-cab telematics unit is
 *     {@code TLM-0002} while the reefer probe on the trailer behind it is {@code DEV-0002}
 * @param validFrom when the assignment starts, inclusive
 * @param validTo when it ends, exclusive; null while the assignment is still open
 */
@Document(collection = Assignment.COLLECTION)
public record Assignment(
    String id,
    String shipmentId,
    String vehicleId,
    List<String> deviceIds,
    Instant validFrom,
    Instant validTo) {

  /** The Mongo collection these live in, named once so the seed script and the code agree. */
  public static final String COLLECTION = "assignments";

  public Assignment {
    deviceIds = deviceIds == null ? List.of() : List.copyOf(deviceIds);
  }

  /**
   * Builds an assignment with a derived id.
   *
   * <p>Derived rather than generated, for the same reason event ids are: seeding reference data is
   * something an operator does repeatedly — after a cluster rebuild, after editing the fleet — and
   * a generated {@code ObjectId} would turn every re-run into a fresh set of rows that silently
   * contradict the previous set. With the id derived from the shipment and the start instant, a
   * re-seed is an upsert that lands on the same document.
   */
  public static Assignment of(
      String shipmentId,
      String vehicleId,
      List<String> deviceIds,
      Instant validFrom,
      Instant validTo) {
    Objects.requireNonNull(shipmentId, "shipmentId");
    Objects.requireNonNull(validFrom, "validFrom");
    return new Assignment(
        shipmentId + "@" + validFrom, shipmentId, vehicleId, deviceIds, validFrom, validTo);
  }

  /** The fragment of truth this assignment supplies to a normalizer. */
  public Identity identity() {
    return new Identity(shipmentId, vehicleId);
  }

  /** Whether {@code instant} falls inside this assignment's half-open validity window. */
  public boolean covers(Instant instant) {
    if (instant.isBefore(validFrom)) {
      return false;
    }
    return validTo == null || instant.isBefore(validTo);
  }
}
