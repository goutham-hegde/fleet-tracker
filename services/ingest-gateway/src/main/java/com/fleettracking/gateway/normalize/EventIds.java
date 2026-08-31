package com.fleettracking.gateway.normalize;

import com.fleettracking.events.SourceSystem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Builds the {@code eventId} every canonical event carries.
 *
 * <h2>Why it is derived rather than random</h2>
 *
 * <p>A random UUID per event is the obvious choice and it quietly gives up the one thing the id is
 * for. Two of the four feeds deliver the same message more than once: the mobile app resends
 * anything it did not get an acknowledgement for, and any HTTP producer retries a request whose
 * response was lost. With random ids, the second copy is a brand new event that no consumer can
 * tell from a real one, so the same GPS fix is counted twice and a shipment appears to sit still
 * for twice as long as it did.
 *
 * <p>Deriving the id from what the source said instead — the feed, the device that reported, and
 * the instant it reported for — means a duplicate delivery produces a byte-identical id. A consumer
 * de-duplicates by remembering ids it has seen, and replaying a whole topic through a fixed
 * normalizer regenerates exactly the ids that were there before rather than a fresh set that would
 * defeat every downstream dedupe at once.
 *
 * <p>The inputs must therefore be things the <em>source</em> stated, never anything the gateway
 * decided. Including the arrival time would make every retry unique again, which is precisely the
 * bug this exists to avoid.
 *
 * <p>{@link UUID#nameUUIDFromBytes} is a version 3 (MD5) name-based UUID. MD5 is broken for
 * signatures and irrelevant here: nothing is being authenticated, and the property being used is
 * that the same name always yields the same id.
 */
public final class EventIds {

  private EventIds() {}

  /**
   * @param source which feed reported it
   * @param reporterId the identifier the source used for itself — a device id, or a shipment id
   *     from a feed that names no device. Two different boxes on one truck reporting the same
   *     instant are two genuine events and must not collide
   * @param occurredAt the instant the source said the event happened, not when it arrived
   * @param discriminator anything else needed to separate two events a source sent for the same
   *     instant — an EDI status code, a sequence number. Empty when the first three are enough
   */
  public static String of(
      SourceSystem source, String reporterId, Instant occurredAt, String discriminator) {
    String name =
        String.join(
            "|",
            source.name(),
            reporterId == null ? "" : reporterId,
            occurredAt == null ? "" : occurredAt.toString(),
            discriminator == null ? "" : discriminator);
    return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
  }

  /** The common case: the feed, the reporter and the instant identify the event on their own. */
  public static String of(SourceSystem source, String reporterId, Instant occurredAt) {
    return of(source, reporterId, occurredAt, "");
  }
}
