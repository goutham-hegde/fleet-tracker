package com.fleettracking.events;

/**
 * Which external feed an event came in on.
 *
 * <p>These four are not variations on a theme — each one breaks a different assumption, and code
 * that treats them uniformly is wrong:
 *
 * <ul>
 *   <li>{@link #TELEMATICS} — high frequency, deeply nested JSON, imperial units.
 *   <li>{@link #MOBILE_APP} — unreliable. Goes quiet in a dead zone, then dumps the whole backlog
 *       at once, out of order and with duplicates.
 *   <li>{@link #EDI_214} — delayed batch text with <em>no coordinates at all</em>; a city and
 *       state that must be geocoded before the event means anything on a map.
 *   <li>{@link #REEFER_SENSOR} — temperature but <em>no position</em>, and no idea which shipment
 *       it belongs to; it knows only its own device id.
 * </ul>
 *
 * <p>The value is retained on the normalized event so that a downstream consumer can weigh events
 * by provenance — a GPS fix from telematics and a "departed" filed by EDI two hours after the fact
 * are not equally trustworthy statements about where a truck is now.
 */
public enum SourceSystem {

  /** In-cab telematics unit. Nested JSON, imperial units, sub-minute frequency. */
  TELEMATICS("application/json"),

  /** Driver's phone app. Lossy, bursty, duplicates on reconnect. */
  MOBILE_APP("application/json"),

  /** X12 EDI 214 Transportation Carrier Shipment Status Message. Flat text, batch, no coordinates. */
  EDI_214("application/edi-x12"),

  /** Refrigerated trailer temperature probe. No position, no shipment id. */
  REEFER_SENSOR("application/json");

  private final String defaultContentType;

  SourceSystem(String defaultContentType) {
    this.defaultContentType = defaultContentType;
  }

  /** The media type this feed normally arrives as, used when a producer does not state one. */
  public String defaultContentType() {
    return defaultContentType;
  }
}
