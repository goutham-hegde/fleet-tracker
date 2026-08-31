package com.fleettracking.simulator.emit;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The small amount of X12 syntax this simulator needs to write a valid EDI 214.
 *
 * <p>X12 predates JSON by decades and looks it. A document is a flat sequence of <b>segments</b>,
 * each terminated by {@code ~}; a segment is an identifier followed by <b>elements</b> separated by
 * {@code *}. There is no nesting, no typing, and no field names — position is meaning, so the
 * seventh element of an {@code AT7} segment is a time zone code because the specification says the
 * seventh element is a time zone code. Parsing it wrongly does not produce an error; it produces a
 * shipment that arrived in the year 2609.
 *
 * <h2>Two traps worth knowing before writing a parser</h2>
 *
 * <ul>
 *   <li><b>The line breaks are cosmetic.</b> The terminator is {@code ~}. Real interchanges are
 *       frequently one enormous line with no newlines at all, and a parser that splits on
 *       {@code \n} works perfectly against every readable sample and then fails against production
 *       traffic. These fixtures include newlines <em>because</em> that trap is worth having in the
 *       sample set — the parser must split on {@code ~}.
 *   <li><b>Empty elements are meaningful.</b> {@code AT7*X1*NS***20260831*0930*UT} has two empty
 *       elements in the middle, and they are not padding: they are unpopulated appointment fields
 *       holding the position of everything after them. Collapsing repeated delimiters shifts every
 *       later element left by one and silently reinterprets a date as a time.
 * </ul>
 */
final class X12 {

  static final char SEGMENT_TERMINATOR = '~';
  static final char ELEMENT_SEPARATOR = '*';

  private static final DateTimeFormatter DATE_6 =
      DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DATE_8 =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter TIME_4 =
      DateTimeFormatter.ofPattern("HHmm").withZone(ZoneOffset.UTC);

  private X12() {}

  /**
   * One segment, terminated and followed by a newline.
   *
   * <p>The newline is for human readers of the fixtures; see the class note above.
   */
  static String segment(String... elements) {
    return String.join(String.valueOf(ELEMENT_SEPARATOR), elements) + SEGMENT_TERMINATOR + "\n";
  }

  /**
   * Right-pads to a fixed width, truncating if too long.
   *
   * <p>ISA is the one fixed-width segment in X12: every element has an exact length, and a sender
   * id shorter than fifteen characters is padded with spaces rather than left short. A trimmed ISA
   * is rejected outright by most trading partners.
   */
  static String pad(String value, int width) {
    String v = value == null ? "" : value;
    if (v.length() >= width) {
      return v.substring(0, width);
    }
    return v + " ".repeat(width - v.length());
  }

  /** Left-pads a control number with zeros, as the interchange header requires. */
  static String controlNumber(long number, int width) {
    return String.format("%0" + width + "d", number);
  }

  /** {@code YYMMDD} — the two-digit year the ISA header still uses. */
  static String date6(Instant at) {
    return DATE_6.format(at);
  }

  /** {@code CCYYMMDD} — the four-digit form used everywhere below the ISA. */
  static String date8(Instant at) {
    return DATE_8.format(at);
  }

  /** {@code HHMM}, to the minute. EDI carries no seconds, so event times arrive rounded. */
  static String time4(Instant at) {
    return TIME_4.format(at);
  }
}
