package com.fleettracking.gateway;

import com.fleettracking.gateway.normalize.RejectionReason;

/**
 * What became of one inbound message.
 *
 * <p>Counts rather than a single verdict, because one message does not always mean one event. An
 * EDI 214 interchange covering a dozen shipments can perfectly well produce ten canonical events
 * and two rejections, and a type that could only say "accepted" or "rejected" would have to round
 * that to one or the other.
 *
 * @param published how many canonical events reached a source topic
 * @param deadLettered how many parts of the message reached the dead-letter topic
 * @param reason the category of the first rejection, or null if there were none
 * @param detail what was wrong with it, or null
 */
public record IngestOutcome(
    int published, int deadLettered, RejectionReason reason, String detail) {

  public static IngestOutcome published(int count) {
    return new IngestOutcome(count, 0, null, null);
  }

  public static IngestOutcome deadLettered(RejectionReason reason, String detail) {
    return new IngestOutcome(0, 1, reason, detail);
  }

  /** True when nothing at all came out of the message. */
  public boolean isFullyRejected() {
    return published == 0;
  }
}
