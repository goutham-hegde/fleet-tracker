package com.fleettracking.gateway.normalize;

import com.fleettracking.events.LocationHint;
import com.fleettracking.events.RawPayload;
import com.fleettracking.events.SourceEvent;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.events.StatusCode;
import com.fleettracking.events.StatusEvent;
import com.fleettracking.gateway.identity.Identity;
import com.fleettracking.gateway.identity.IdentityResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a carrier's EDI 214 interchange: delayed batch text, many shipments, no coordinates.
 *
 * <p>This is the feed that breaks what the other three have in common. Telematics, the phone and
 * the reefer probe each send one message describing one moment, more or less now, with a position
 * in it. An EDI 214 is a file the carrier's back office transmitted on a schedule, describing
 * several shipments, hours after the fact, and it contains no coordinates anywhere.
 *
 * <h2>X12, briefly</h2>
 *
 * <p>The format predates JSON by decades. A document is a flat list of <b>segments</b> terminated
 * by {@code ~}; each segment is a tag followed by <b>elements</b> separated by {@code *}. There are
 * no field names and no types — <em>position is meaning</em>. The sixth element of an {@code AT7}
 * is a time because the specification says the sixth element is a time.
 *
 * <p>Two consequences shape this parser:
 *
 * <ul>
 *   <li><b>Line breaks are cosmetic.</b> The terminator is {@code ~}, not a newline. Production
 *       interchanges are routinely one enormous line. Splitting on {@code \n} works against every
 *       readable sample and then fails against real traffic, so this splits on {@code ~} and
 *       treats whitespace around a segment as noise.
 *   <li><b>Empty elements are load-bearing.</b> {@code AT7*X1*NS***20260831*0930*UT} has two empty
 *       elements in the middle holding the position of everything after them. Collapsing repeated
 *       delimiters shifts the date into the time's slot, and nothing errors — the shipment simply
 *       arrives at an impossible hour of an impossible day.
 * </ul>
 *
 * <h2>What this normalizer must decide that the others do not</h2>
 *
 * <ul>
 *   <li><b>One message, many events.</b> An interchange has no single shipment and therefore no
 *       Kafka key. It is split into one status per {@code ST}/{@code SE} transaction set before
 *       anything can be keyed, which is why success is a list.
 *   <li><b>A place, not a position.</b> The only location is {@code MS1*KURNOOL*AP*IN} — true,
 *       useful, and impossible to draw on a map or compare with a GPS fix until something geocodes
 *       it. It becomes a {@link LocationHint} rather than a fabricated coordinate. Guessing the
 *       centroid of Bhiwandi would put a truck 8 km from where it is and make every downstream
 *       consumer believe it had a real fix.
 *   <li><b>Minute resolution.</b> {@code AT7} carries {@code HHMM} and no seconds, so every EDI
 *       timestamp is already rounded before it arrives. Two different events for one shipment can
 *       share a minute, which is why the status code is part of the derived event id.
 *   <li><b>Damage is partial.</b> A truncated file usually holds several intact transaction sets
 *       and one that was cut off. See {@link NormalizationResult.Partial}.
 * </ul>
 */
public class Edi214Normalizer implements Normalizer {

  /**
   * The carrier's status vocabulary, translated into this platform's.
   *
   * <p>These codes are X12's, not ours, and the translation is genuinely lossy in one direction and
   * genuinely informative in the other. {@code X3} and {@code X1} both become an arrival, because
   * the canonical vocabulary describes what happened to the truck rather than which leg of the trip
   * it was on — the route model already knows whether that stop is a pickup or a delivery, and
   * duplicating that into the status code would create two sources of truth for one fact.
   *
   * <p>{@code AF} is the exception and worth the detail: "carrier departed pick-up location with
   * shipment" is not merely a departure, it is the moment the freight is aboard. Mapping it to a
   * generic departure would throw away the pickup milestone that a customer's SLA is written
   * against.
   */
  private static final Map<String, StatusCode> STATUS_CODES =
      Map.of(
          "X3", StatusCode.ARRIVED_AT_STOP, // arrived at pick-up location
          "AF", StatusCode.PICKED_UP, // departed pick-up location with shipment
          "X1", StatusCode.ARRIVED_AT_STOP, // arrived at delivery location
          "CD", StatusCode.DEPARTED_STOP, // departed delivery location
          "X4", StatusCode.DELIVERED, // completed unloading at delivery location
          "AG", StatusCode.IN_TRANSIT, // estimated departure
          "SD", StatusCode.DELAY_REPORTED); // shipment delayed

  /**
   * X12's time zone codes, as fixed offsets.
   *
   * <p>The seventh element of {@code AT7} says which clock the date and time are on, and honouring
   * it is not optional: reading a Pacific timestamp as UTC puts an arrival eight hours early, which
   * is well outside any tolerance a geofence reconciliation would use and looks like a plausible
   * time rather than an error.
   *
   * <p>The US daylight and standard codes are each an unambiguous fixed offset, so they convert
   * exactly. {@code LT} — "local time" — is deliberately absent: it means whatever clock the sender
   * was on, which the file does not state. A message using it is rejected rather than assumed to
   * mean UTC.
   */
  private static final Map<String, ZoneOffset> TIME_ZONES =
      Map.of(
          "UT", ZoneOffset.UTC,
          "GM", ZoneOffset.UTC,
          "ED", ZoneOffset.ofHours(-4),
          "ES", ZoneOffset.ofHours(-5),
          "CD", ZoneOffset.ofHours(-5),
          "CS", ZoneOffset.ofHours(-6),
          "MD", ZoneOffset.ofHours(-6),
          "MS", ZoneOffset.ofHours(-7),
          "PD", ZoneOffset.ofHours(-7),
          "PS", ZoneOffset.ofHours(-8));

  private final IdentityResolver identities;

  public Edi214Normalizer(IdentityResolver identities) {
    this.identities = identities;
  }

  @Override
  public SourceSystem source() {
    return SourceSystem.EDI_214;
  }

  @Override
  public NormalizationResult normalize(InboundMessage message) {
    List<Segment> segments = segments(message.body());
    if (segments.isEmpty()) {
      return NormalizationResult.rejected(RejectionReason.MALFORMED_PAYLOAD, "no X12 segments");
    }
    if (!segments.getFirst().tagIs("ISA")) {
      // An HTML error page, a JSON body posted to the wrong endpoint, or a file whose first bytes
      // were lost. Every interchange begins with ISA; nothing else does.
      return NormalizationResult.rejected(
          RejectionReason.MALFORMED_PAYLOAD,
          "expected an ISA header, found " + abbreviate(segments.getFirst().tag()));
    }

    RawPayload raw = new RawPayload(SourceSystem.EDI_214, message.contentType(), message.body());
    List<SourceEvent> events = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    RejectionReason reason = null;

    int setNumber = 0;
    int index = 0;
    while (index < segments.size()) {
      if (!segments.get(index).tagIs("ST")) {
        index++;
        continue;
      }
      setNumber++;
      TransactionSet set = readTransactionSet(segments, index);
      index = set.nextIndex();

      switch (set.outcome()) {
        case TransactionSet.Damaged damaged -> {
          problems.add("transaction set %d: %s".formatted(setNumber, damaged.detail()));
          reason = worse(reason, damaged.reason());
        }
        case TransactionSet.Read read -> {
          Result converted = toEvent(read, message, raw);
          if (converted.event() != null) {
            events.add(converted.event());
          } else {
            problems.add("transaction set %d: %s".formatted(setNumber, converted.detail()));
            reason = worse(reason, converted.reason());
          }
        }
      }
    }

    // The envelope's own integrity check. GE states how many transaction sets the functional group
    // contained, which is exactly the fact a truncated transmission destroys and the only way to
    // notice that sets are missing rather than simply absent. A file with no GE at all was cut off
    // before its footer.
    Envelope envelope = readEnvelope(segments, setNumber);
    if (envelope.problem() != null) {
      problems.add(envelope.problem());
      reason = worse(reason, RejectionReason.MALFORMED_PAYLOAD);
    }

    if (events.isEmpty() && problems.isEmpty()) {
      return NormalizationResult.rejected(
          RejectionReason.MISSING_FIELD, "interchange contains no transaction sets");
    }
    return NormalizationResult.of(events, reason, String.join("; ", problems));
  }

  // -----------------------------------------------------------------------------------------
  // Reading one ST/SE transaction set.
  // -----------------------------------------------------------------------------------------

  /**
   * Collects the segments between {@code ST} and its {@code SE}.
   *
   * <p>A set that runs off the end of the file, or into the next {@code ST}, was truncated in
   * transit. Stopping at the next {@code ST} matters as much as stopping at the end: without it a
   * damaged set would swallow every set after it and one lost segment would cost the whole batch.
   */
  private static TransactionSet readTransactionSet(List<Segment> segments, int start) {
    Segment st = segments.get(start);
    String control = st.element(2);
    if (!"214".equals(st.element(1))) {
      return TransactionSet.damaged(
          start + 1,
          RejectionReason.UNSUPPORTED_FEED,
          "expected transaction set 214, found " + st.element(1));
    }

    Segment b10 = null;
    Segment at7 = null;
    Segment ms1 = null;

    int index = start + 1;
    while (index < segments.size()) {
      Segment segment = segments.get(index);
      if (segment.tagIs("ST")) {
        return TransactionSet.damaged(index, RejectionReason.MALFORMED_PAYLOAD, "no SE terminator");
      }
      if (segment.tagIs("SE")) {
        if (!control.equals(segment.element(2))) {
          // ST and SE carry the same control number so that a receiver can prove the set it read is
          // the set the sender wrote. A mismatch means segments from two sets have been interleaved.
          return TransactionSet.damaged(
              index + 1,
              RejectionReason.MALFORMED_PAYLOAD,
              "SE control number %s does not match ST %s".formatted(segment.element(2), control));
        }
        int declared = parseInt(segment.element(1));
        int actual = index - start + 1;
        if (declared > 0 && declared != actual) {
          // SE counts every segment in the set including ST and SE themselves. It is a checksum,
          // and a mismatch means something between them was dropped in transit.
          return TransactionSet.damaged(
              index + 1,
              RejectionReason.MALFORMED_PAYLOAD,
              "SE declares %d segments, found %d".formatted(declared, actual));
        }
        return TransactionSet.read(index + 1, b10, at7, ms1);
      }
      if (segment.tagIs("B10")) {
        b10 = segment;
      } else if (segment.tagIs("AT7")) {
        at7 = segment;
      } else if (segment.tagIs("MS1")) {
        ms1 = segment;
      }
      // Everything else -- LX, and any segment a carrier adds that this platform has no use for --
      // is skipped rather than rejected. An interchange is allowed to carry more than we read.
      index++;
    }
    return TransactionSet.damaged(
        index, RejectionReason.MALFORMED_PAYLOAD, "truncated before SE terminator");
  }

  /** Turns one complete transaction set into a canonical status event, or explains why not. */
  private Result toEvent(TransactionSet.Read set, InboundMessage message, RawPayload raw) {
    if (set.b10() == null) {
      return Result.failed(RejectionReason.MISSING_FIELD, "no B10 segment, so no shipment id");
    }
    // B10 element 1 is the carrier's own trip number, which means nothing to this platform.
    // Element 2 is the shipment id we issued and the carrier echoed back.
    String shipmentId = set.b10().element(2);
    if (shipmentId.isBlank()) {
      return Result.failed(RejectionReason.MISSING_FIELD, "B10 carries no shipment id");
    }
    if (set.at7() == null) {
      return Result.failed(RejectionReason.MISSING_FIELD, "no AT7 segment, so no status or time");
    }

    String code = set.at7().element(1).toUpperCase(Locale.ROOT);
    StatusCode status = STATUS_CODES.get(code);
    if (status == null) {
      // A carrier extending its vocabulary should surface as work to do, not be filed as something
      // this gateway happens to already understand.
      return Result.failed(
          RejectionReason.INVALID_VALUE, "unknown shipment status code " + set.at7().element(1));
    }

    Instant occurredAt;
    try {
      occurredAt = timestamp(set.at7());
    } catch (IllegalArgumentException e) {
      return Result.failed(RejectionReason.INVALID_VALUE, e.getMessage());
    }

    Optional<Identity> identity = identities.byShipment(shipmentId, occurredAt);
    if (identity.isEmpty()) {
      return Result.failed(
          RejectionReason.UNRESOLVED_IDENTITY, "no vehicle assigned to shipment " + shipmentId);
    }

    return Result.of(
        new StatusEvent(
            // The reporter is the shipment: this feed names no device, because a back-office
            // translator is not a device. The status code is the discriminator because EDI times
            // are rounded to the minute, so an arrival and a departure filed for the same minute
            // are entirely possible and must not collapse into one id.
            EventIds.of(SourceSystem.EDI_214, shipmentId, occurredAt, code),
            shipmentId,
            identity.get().vehicleId(),
            null,
            occurredAt,
            message.receivedAt(),
            status,
            // No position, ever. This is the whole point of the feed.
            null,
            locationHint(set.ms1()),
            null,
            // No stop id either. The carrier does not know the identifiers in this platform's route
            // model, so matching "BHIWANDI MH" to the Bhiwandi DC is real work for a consumer rather
            // than a lookup. Filling this in from the city name would be a guess wearing the
            // clothes of a fact.
            null,
            reasonCode(set.at7()),
            raw));
  }

  /**
   * {@code MS1*HYDERABAD*TG*IN} — a place name, which is not a place.
   *
   * <p>Kept as text in the shape the carrier sent it. The country code is constrained to two
   * characters by the envelope and the state to three, so a carrier sending something longer is
   * caught centrally by validation rather than silently truncated here.
   */
  private static LocationHint locationHint(Segment ms1) {
    if (ms1 == null) {
      return null;
    }
    String city = blankToNull(ms1.element(1));
    String state = blankToNull(ms1.element(2));
    String country = blankToNull(ms1.element(3));
    if (city == null && state == null && country == null) {
      return null;
    }
    return new LocationHint(city, state, null, country);
  }

  /**
   * The second element of {@code AT7} is the status <em>reason</em>, and {@code NS} means "normal
   * status" — the carrier saying nothing unusual happened. That is noise on every ordinary message,
   * so it is dropped and only a genuine exception reason is kept.
   */
  private static String reasonCode(Segment at7) {
    String reason = at7.element(2);
    return reason.isBlank() || "NS".equalsIgnoreCase(reason) ? null : reason;
  }

  /**
   * Elements 5, 6 and 7 of {@code AT7}: date, time and time zone.
   *
   * <p>This is the segment where positional parsing bites. The two empty elements before the date
   * are unpopulated appointment fields, and a parser that collapsed the delimiters would read
   * {@code 20260831} as the appointment reason and the time as the date.
   */
  private static Instant timestamp(Segment at7) {
    String date = at7.element(5);
    String time = at7.element(6);
    String zone = at7.element(7).toUpperCase(Locale.ROOT);

    if (date.isBlank() || time.isBlank()) {
      throw new IllegalArgumentException("AT7 carries no event date or time");
    }
    ZoneOffset offset = TIME_ZONES.get(zone.isBlank() ? "UT" : zone);
    if (offset == null) {
      // Including LT, "local time", which names no clock at all.
      throw new IllegalArgumentException("unusable AT7 time zone code " + at7.element(7));
    }
    try {
      LocalDate day = LocalDate.parse(date, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
      LocalTime clock =
          LocalTime.of(Integer.parseInt(time.substring(0, 2)), Integer.parseInt(time.substring(2, 4)));
      return day.atTime(clock).toInstant(offset);
    } catch (DateTimeParseException | NumberFormatException | StringIndexOutOfBoundsException e) {
      throw new IllegalArgumentException("unreadable AT7 date/time %s %s".formatted(date, time));
    }
  }

  // -----------------------------------------------------------------------------------------
  // The envelope.
  // -----------------------------------------------------------------------------------------

  /**
   * Checks the interchange footers.
   *
   * <p>{@code GE} states how many transaction sets the group held and {@code IEA} closes the
   * interchange. They exist so a receiver can prove the file arrived whole, which is precisely what
   * a truncated transmission takes away — and it is the only way to know that sets are <em>missing</em>
   * rather than simply not sent.
   */
  private static Envelope readEnvelope(List<Segment> segments, int setsFound) {
    Segment ge = null;
    boolean gs = false;
    boolean iea = false;
    for (Segment segment : segments) {
      if (segment.tagIs("GS")) {
        gs = true;
      } else if (segment.tagIs("GE")) {
        ge = segment;
      } else if (segment.tagIs("IEA")) {
        iea = true;
      }
    }
    if (!gs) {
      // Worth checking even though nothing downstream reads the functional group header, because
      // its absence is the one visible symptom of a lost segment terminator: drop the tilde after
      // ISA and the two headers merge into a single segment that still begins with "ISA" and still
      // parses. Every count below would then agree and the damage would pass unnoticed.
      return new Envelope("no GS functional group header");
    }
    if (ge == null) {
      return new Envelope("interchange truncated: no GE footer");
    }
    int declared = parseInt(ge.element(1));
    if (declared > 0 && declared != setsFound) {
      return new Envelope(
          "GE declares %d transaction sets, found %d".formatted(declared, setsFound));
    }
    if (!iea) {
      return new Envelope("interchange truncated: no IEA footer");
    }
    return new Envelope(null);
  }

  // -----------------------------------------------------------------------------------------
  // Syntax.
  // -----------------------------------------------------------------------------------------

  /**
   * Splits the document into segments on {@code ~}.
   *
   * <p>Not on newlines: the terminator is the tilde, and a real interchange may have no newlines at
   * all. Whitespace around a segment is discarded because a transmission that passed through a
   * mail gateway frequently acquires some.
   */
  private static List<Segment> segments(String body) {
    List<Segment> segments = new ArrayList<>();
    for (String raw : body.split("~", -1)) {
      String trimmed = raw.strip();
      if (!trimmed.isEmpty()) {
        segments.add(Segment.parse(trimmed));
      }
    }
    return segments;
  }

  /**
   * One segment, split into elements on {@code *}.
   *
   * <p>The limit of {@code -1} is the load-bearing part: without it Java discards trailing empty
   * elements, so a segment whose last field is unpopulated silently becomes shorter and every
   * bounds check downstream reads a different field than it asked for.
   */
  private record Segment(String tag, String[] elements) {

    static Segment parse(String text) {
      String[] parts = text.split("\\*", -1);
      return new Segment(parts[0].toUpperCase(Locale.ROOT), parts);
    }

    boolean tagIs(String candidate) {
      return tag.equals(candidate);
    }

    /** The element at a position, or empty if the segment is shorter than the spec allows. */
    String element(int position) {
      return position < elements.length ? elements[position].strip() : "";
    }
  }

  /** What reading one {@code ST}/{@code SE} pair produced, and where the next one starts. */
  private sealed interface TransactionSetOutcome {}

  private record TransactionSet(int nextIndex, TransactionSetOutcome outcome) {

    record Read(Segment b10, Segment at7, Segment ms1) implements TransactionSetOutcome {}

    record Damaged(RejectionReason reason, String detail) implements TransactionSetOutcome {}

    static TransactionSet read(int nextIndex, Segment b10, Segment at7, Segment ms1) {
      return new TransactionSet(nextIndex, new Read(b10, at7, ms1));
    }

    static TransactionSet damaged(int nextIndex, RejectionReason reason, String detail) {
      return new TransactionSet(nextIndex, new Damaged(reason, detail));
    }
  }

  /** One converted set: an event, or the reason there is not one. */
  private record Result(SourceEvent event, RejectionReason reason, String detail) {

    static Result of(SourceEvent event) {
      return new Result(event, null, null);
    }

    static Result failed(RejectionReason reason, String detail) {
      return new Result(null, reason, detail);
    }
  }

  private record Envelope(String problem) {}

  /**
   * Keeps the most structural of several rejection reasons.
   *
   * <p>A batch can fail several ways at once — one set truncated, another naming an unknown
   * shipment. The reported category should be the one that describes the message rather than the
   * reference data, because that is the one that decides whether replaying it later could ever
   * work: a malformed byte stream never will, an unresolved shipment might once the TMS catches up.
   */
  private static RejectionReason worse(RejectionReason current, RejectionReason candidate) {
    if (current == null) {
      return candidate;
    }
    return current == RejectionReason.MALFORMED_PAYLOAD ? current : candidate;
  }

  /** Keeps a rejection detail short: an error page posted here would otherwise arrive whole. */
  private static String abbreviate(String value) {
    return value.length() <= 40 ? value : value.substring(0, 40) + "...";
  }

  private static String blankToNull(String value) {
    return value.isBlank() ? null : value;
  }

  /** Zero for anything unreadable, which the callers treat as "not stated" rather than as a count. */
  private static int parseInt(String value) {
    try {
      return Integer.parseInt(value.strip());
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
