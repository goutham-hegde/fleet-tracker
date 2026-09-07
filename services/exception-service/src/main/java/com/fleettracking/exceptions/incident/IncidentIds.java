package com.fleettracking.exceptions.incident;

import com.fleettracking.events.ExceptionType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Names an incident, and the two events that open and close it.
 *
 * <h2>Three ids, and they are not interchangeable</h2>
 *
 * <p>An exception is the only thing this platform models as a pair of events, so it is the only
 * place with an identifier that outlives a single message. Getting the three straight matters:
 *
 * <ul>
 *   <li><b>The incident id</b> ({@link #incident}) names the <em>condition</em> — one truck, one
 *       breach, from the moment it began until the moment it resolved. Both events carry it, and
 *       that is the join. A rule that minted a fresh one on every firing would turn one late truck
 *       into forty alerts, which is the failure mode {@code ExceptionRaised} warns about in its own
 *       documentation.
 *   <li><b>The raise's event id</b> ({@link #raised}) names the message that announced it.
 *   <li><b>The clear's event id</b> ({@link #cleared}) names the message that closed it.
 * </ul>
 *
 * <h2>Derived from the onset, never from wall-clock time</h2>
 *
 * <p>The incident id is a name-based UUID over the type, the shipment, the stop and the instant the
 * condition <em>began</em> — not the instant the rule became sure of it. Two consequences, both
 * load-bearing.
 *
 * <p>First, it survives a restart. The rule state that was accumulating when the process died is
 * read back from MongoDB, the onset is the same instant it always was, and the incident regenerates
 * the same id — so a clear published by a different process still pairs with a raise published by
 * the one before it.
 *
 * <p>Second, it makes publish-then-record safe, which is the order this service uses for the same
 * reason geofencing does: recording first can lose an exception permanently and silently, while
 * publishing first can only repeat one, and a repeat with an identical id is a duplicate every
 * consumer already collapses.
 *
 * <p>Using the onset rather than the confirmation instant has a third effect worth stating: an
 * exception is stamped with when the condition started, so a temperature excursion confirmed after
 * thirty minutes says it began thirty minutes ago. The alternative biases every incident late by
 * exactly the tolerance, which is the same mistake as stamping an arrival with the moment the dwell
 * threshold expired.
 */
public final class IncidentIds {

  private IncidentIds() {}

  /**
   * The id of the incident of this type, for this shipment, at this stop, that began at this
   * instant.
   *
   * @param stopId null for the rules that are not about a place — a silent device, a reefer drifting
   *     off its band. The empty string stands in, so a stopless incident still has a stable name
   */
  public static String incident(
      ExceptionType type, String shipmentId, String stopId, Instant onsetAt) {
    return uuid("INCIDENT", type.name(), shipmentId, stopId, onsetAt == null ? null : onsetAt.toString());
  }

  /** The id of the message that opens an incident. */
  public static String raised(String incidentId) {
    return uuid("RAISED", incidentId);
  }

  /**
   * The id of the message that closes an incident.
   *
   * <p>Derived from the incident alone, with no reference to the event that resolved it. An
   * incident closes exactly once, so there is nothing for a second input to disambiguate — and
   * leaving it out means a clear republished after a crash is byte-identical whether or not the
   * same source event triggered the retry.
   */
  public static String cleared(String incidentId) {
    return uuid("CLEARED", incidentId);
  }

  private static String uuid(String... parts) {
    StringBuilder name = new StringBuilder();
    for (String part : parts) {
      if (name.length() > 0) {
        name.append('|');
      }
      name.append(part == null ? "" : part);
    }
    return UUID.nameUUIDFromBytes(name.toString().getBytes(StandardCharsets.UTF_8)).toString();
  }
}
