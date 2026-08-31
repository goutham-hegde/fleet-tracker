package com.fleettracking.gateway.normalize;

/**
 * Why a message could not become a canonical event.
 *
 * <p>An enum rather than a free-text string because this value is written onto every dead-letter
 * message and is the first thing anyone groups by when a feed starts misbehaving. "How many of
 * yesterday's rejections were unparseable versus unknown trucks" is a question with two very
 * different answers — one is a broken producer, the other is stale reference data — and a
 * free-text field would make it a question about string matching. The detail that goes with it is
 * free text; the category is not.
 */
public enum RejectionReason {

  /**
   * The bytes are not the format they claim to be — truncated JSON, an unbalanced quote, an HTML
   * error page from a proxy that answered instead of the real producer. Nothing can be read out of
   * it, so nothing about it can be checked.
   */
  MALFORMED_PAYLOAD,

  /**
   * The payload parsed, but something the canonical envelope requires is not in it. A telematics
   * report with no {@code gps} object is well-formed JSON and useless as a position.
   */
  MISSING_FIELD,

  /**
   * A field is present and cannot be true — a latitude of 240 degrees, an odometer in a unit
   * nobody has heard of, a speed no truck reaches. Distinguished from a missing field because the
   * two point at different bugs: absent usually means a producer version skew, wrong usually means
   * a broken sensor or a conversion applied twice.
   */
  INVALID_VALUE,

  /**
   * The message is fine, but the gateway cannot say which shipment it concerns. Three of the four
   * feeds do not name one, so this depends entirely on reference data being current. It is the
   * rejection most likely to be transient, and the one most worth replaying once the reference
   * data catches up.
   */
  UNRESOLVED_IDENTITY,

  /** The feed or content type is not one this gateway knows how to read. */
  UNSUPPORTED_FEED
}
