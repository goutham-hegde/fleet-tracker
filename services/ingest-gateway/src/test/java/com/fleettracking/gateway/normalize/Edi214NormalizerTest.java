package com.fleettracking.gateway.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.SourceEvent;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.events.StatusCode;
import com.fleettracking.events.StatusEvent;
import com.fleettracking.gateway.Fixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Edi214NormalizerTest {

  private final Edi214Normalizer normalizer = new Edi214Normalizer(Fixtures.defaultFleet());

  private static final Instant ARRIVED = Instant.parse("2026-08-31T12:00:00Z");

  private NormalizationResult normalize(String body) {
    return normalizer.normalize(
        new InboundMessage(SourceSystem.EDI_214, "application/edi-x12", body, ARRIVED));
  }

  private List<SourceEvent> normalizeAll(String body) {
    NormalizationResult result = normalize(body);
    assertThat(result)
        .as("expected a normalized result, got %s", result)
        .isInstanceOf(NormalizationResult.Normalized.class);
    return ((NormalizationResult.Normalized) result).events();
  }

  /** One interchange carrying a single arrival at a delivery, written on one line and no newlines. */
  private static String interchange() {
    return "ISA*00*          *00*          *02*CARRIER01      *ZZ*FLEETTRACK     "
        + "*260831*1200*U*00401*000000101*0*P*>~"
        + "GS*QM*CARRIER01*FLEETTRACK*20260831*1200*101*X*004010~"
        + "ST*214*0001~"
        + "B10*1645387*SHP-ATL-0003*FLTX~"
        + "LX*1~"
        + "AT7*X1*NS***20260831*0930*UT~"
        + "MS1*MEMPHIS*TN*US~"
        + "SE*6*0001~"
        + "GE*1*101~"
        + "IEA*1*000000101~";
  }

  private static List<String> committedInterchanges(String directory) {
    try (var files = Files.list(Fixtures.samples().resolve(directory))) {
      List<String> bodies = new ArrayList<>();
      for (Path file : files.sorted().toList()) {
        bodies.add(Files.readString(file, StandardCharsets.UTF_8));
      }
      return bodies;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The committed interchange carrying the most transaction sets.
   *
   * <p>Chosen by content rather than by filename: the batch sizes depend on what the simulator had
   * queued when each batch window came round, so hard-coding a file would quietly become a
   * one-shipment test the next time the fixtures are regenerated.
   */
  private static String largestInterchange() {
    return committedInterchanges("edi-214").stream()
        .max(java.util.Comparator.comparingInt(body -> body.split("ST\\*214", -1).length))
        .orElseThrow();
  }

  @Test
  void normalizesEveryCapturedInterchange() {
    List<String> bodies = committedInterchanges("edi-214");
    assertThat(bodies).isNotEmpty();

    for (String body : bodies) {
      assertThat(normalize(body))
          .as("captured interchange should normalize")
          .isInstanceOf(NormalizationResult.Normalized.class);
    }
  }

  @Test
  void splitsOneInterchangeIntoOneEventPerShipment() {
    // The committed fixtures batch several shipments into one file, which is the property that
    // makes this feed unlike the other three: the message has no shipment id of its own and
    // therefore no Kafka key until it has been split.
    String batched = largestInterchange();

    List<SourceEvent> events = normalizeAll(batched);

    assertThat(events).hasSizeGreaterThan(1);
    assertThat(events).extracting(SourceEvent::shipmentId).doesNotHaveDuplicates();
  }

  @Test
  void splitsOnTildesNotOnNewlines() {
    // interchange() has no newlines in it at all, which is what a production file often looks like.
    // A parser that split on \n would find one segment here and reject a perfectly good file.
    assertThat(normalizeAll(interchange())).hasSize(1);
  }

  @Test
  void readsTheDateAndTimeFromTheirOwnPositionsPastTheEmptyElements() {
    // AT7*X1*NS***20260831*0930*UT -- the two empty elements are unpopulated appointment fields,
    // and they hold the position of everything after them. Collapsing the delimiters would read
    // the date as the appointment reason and 0930 as the date.
    StatusEvent event = (StatusEvent) normalizeAll(interchange()).getFirst();

    assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-31T09:30:00Z"));
  }

  @Test
  void keepsTheHoursOfLagBetweenTheEventAndItsFiling() {
    // The defining property of this feed. The truck arrived at 09:30; the carrier's batch reached
    // the platform at 12:00, by which time telematics has reported its position a hundred times.
    StatusEvent event = (StatusEvent) normalizeAll(interchange()).getFirst();

    assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-31T09:30:00Z"));
    assertThat(event.receivedAt()).isEqualTo(ARRIVED);
  }

  @Test
  void honoursTheTimeZoneCodeRatherThanAssumingUtc() {
    // The seventh element says which clock the timestamp is on. Reading a Pacific time as UTC
    // would place the arrival eight hours early -- a plausible-looking time, not an error.
    String pacific = interchange().replace("*20260831*0930*UT~", "*20260831*0930*PS~");

    StatusEvent event = (StatusEvent) normalizeAll(pacific).getFirst();

    assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-31T17:30:00Z"));
  }

  @Test
  void rejectsATimeZoneThatNamesNoClock() {
    // LT means "local time" -- local to a sender whose location the file never states.
    String local = interchange().replace("*20260831*0930*UT~", "*20260831*0930*LT~");

    NormalizationResult result = normalize(local);

    assertThat(result).isInstanceOf(NormalizationResult.Rejected.class);
    assertThat(((NormalizationResult.Rejected) result).reason())
        .isEqualTo(RejectionReason.INVALID_VALUE);
  }

  @Test
  void carriesAPlaceNameAndNoCoordinatesAtAll() {
    StatusEvent event = (StatusEvent) normalizeAll(interchange()).getFirst();

    // The single most important property of this feed. A city and a state is a true statement that
    // cannot be drawn on a map or compared with a GPS fix until something geocodes it, and
    // inventing the centroid of Memphis would make every consumer believe there was a real fix.
    assertThat(event.position()).isNull();
    assertThat(event.location().city()).isEqualTo("MEMPHIS");
    assertThat(event.location().stateOrProvince()).isEqualTo("TN");
    assertThat(event.location().countryCode()).isEqualTo("US");
  }

  @Test
  void namesNoStopBecauseTheCarrierDoesNotKnowOurRouteModel() {
    StatusEvent event = (StatusEvent) normalizeAll(interchange()).getFirst();

    // Matching "MEMPHIS TN" to the Memphis hub is real work for a consumer. Filling this in here
    // from the city name would be a guess wearing the clothes of a fact.
    assertThat(event.stopId()).isNull();
    // No device either: a back-office translator is not a device.
    assertThat(event.deviceId()).isNull();
  }

  @Test
  void translatesTheCarriersVocabularyIntoOurs() {
    assertThat(statusOf("X3")).isEqualTo(StatusCode.ARRIVED_AT_STOP);
    assertThat(statusOf("X1")).isEqualTo(StatusCode.ARRIVED_AT_STOP);
    // Not a generic departure: AF is "departed the pick-up location with the shipment", which is
    // the moment the freight is aboard and the milestone a customer's SLA is written against.
    assertThat(statusOf("AF")).isEqualTo(StatusCode.PICKED_UP);
    assertThat(statusOf("CD")).isEqualTo(StatusCode.DEPARTED_STOP);
    assertThat(statusOf("X4")).isEqualTo(StatusCode.DELIVERED);
  }

  private StatusCode statusOf(String x12Code) {
    String body = interchange().replace("AT7*X1*", "AT7*" + x12Code + "*");
    return ((StatusEvent) normalizeAll(body).getFirst()).status();
  }

  @Test
  void rejectsAStatusCodeItDoesNotKnow() {
    // A carrier extending its vocabulary should surface as work to do rather than be filed as
    // something this gateway already understands.
    NormalizationResult result = normalize(interchange().replace("AT7*X1*", "AT7*ZZ*"));

    assertThat(result)
        .isEqualTo(
            new NormalizationResult.Rejected(
                RejectionReason.INVALID_VALUE, "transaction set 1: unknown shipment status code ZZ"));
  }

  @Test
  void dropsANormalStatusReasonAndKeepsARealOne() {
    // NS is "normal status" -- the carrier saying nothing unusual happened, which is noise on every
    // ordinary message. Anything else is the interesting case.
    StatusEvent ordinary = (StatusEvent) normalizeAll(interchange()).getFirst();
    assertThat(ordinary.reasonCode()).isNull();

    String weather = interchange().replace("AT7*X1*NS*", "AT7*X1*AA*");
    assertThat(((StatusEvent) normalizeAll(weather).getFirst()).reasonCode()).isEqualTo("AA");
  }

  @Test
  void givesTheSameEventIdToAResentInterchange() {
    SourceEvent first = normalizeAll(interchange()).getFirst();
    SourceEvent second =
        ((NormalizationResult.Normalized)
                normalizer.normalize(
                    new InboundMessage(
                        SourceSystem.EDI_214,
                        "application/edi-x12",
                        interchange(),
                        Instant.parse("2026-08-31T14:00:00Z"))))
            .events()
            .getFirst();

    assertThat(second.eventId()).isEqualTo(first.eventId());
  }

  @Test
  void separatesTwoStatusesFiledForTheSameMinute() {
    // EDI carries HHMM and no seconds, so an arrival and a departure genuinely can share a
    // timestamp. Without the status code in the derived id they would collapse into one event.
    String arrival = interchange();
    String departure = interchange().replace("AT7*X1*", "AT7*CD*");

    assertThat(normalizeAll(arrival).getFirst().eventId())
        .isNotEqualTo(normalizeAll(departure).getFirst().eventId());
  }

  // -------------------------------------------------------------------------------------------
  // Damage.
  // -------------------------------------------------------------------------------------------

  @Test
  void publishesTheIntactSetsOfATruncatedInterchangeAndDeadLettersTheWhole() {
    // Truncated by hand rather than taken from the committed chaos capture: the fault injector cuts
    // at a random point and, in the run that produced docs/samples/faults, happened never to cut an
    // interchange in a place that leaves intact sets behind. This is the same damage, made
    // deterministic -- the file is cut in the middle of the third transaction set's B10.
    String batched = largestInterchange();
    int thirdSet = batched.indexOf("ST*214*0003");
    assertThat(thirdSet).as("fixture should carry at least three transaction sets").isPositive();
    String truncated = batched.substring(0, thirdSet + 40);

    NormalizationResult result = normalize(truncated);

    assertThat(result).isInstanceOf(NormalizationResult.Partial.class);
    NormalizationResult.Partial partial = (NormalizationResult.Partial) result;
    // The two complete sets before the cut are real freight events, and the carrier will not send
    // this batch again.
    assertThat(partial.events()).hasSize(2);
    assertThat(partial.reason()).isEqualTo(RejectionReason.MALFORMED_PAYLOAD);
    assertThat(partial.detail()).contains("transaction set 3").contains("no GE footer");
  }

  @Test
  void doesNotLetOneDamagedSetSwallowTheSetsAfterIt() {
    // A set with no SE terminator ends at the next ST rather than running to the end of the file.
    // Without that, one lost segment would cost every shipment in the rest of the batch.
    String batched = largestInterchange();
    int events = normalizeAll(batched).size();
    String missingTerminator = batched.replaceFirst("SE\\*6\\*0001~\\s*", "");

    NormalizationResult result = normalize(missingTerminator);

    assertThat(result).isInstanceOf(NormalizationResult.Partial.class);
    // Exactly one set is lost, not all of them.
    assertThat(((NormalizationResult.Partial) result).events()).hasSize(events - 1);
  }

  @Test
  void noticesWhenTheGroupFooterCountsMoreSetsThanArrived() {
    // GE states how many transaction sets the group held. It is the only way to know that sets are
    // missing rather than simply never sent, and it is exactly what a truncation destroys.
    String body = interchange().replace("GE*1*101~", "GE*4*101~");

    NormalizationResult result = normalize(body);

    assertThat(result).isInstanceOf(NormalizationResult.Partial.class);
    assertThat(((NormalizationResult.Partial) result).detail())
        .isEqualTo("GE declares 4 transaction sets, found 1");
  }

  @Test
  void noticesASegmentDroppedFromInsideATransactionSet() {
    // SE counts the segments between ST and SE inclusive. It is a checksum, and this is the only
    // thing that catches a segment lost in the middle of an otherwise well-formed file.
    String body = interchange().replace("MS1*MEMPHIS*TN*US~", "");

    NormalizationResult result = normalize(body);

    assertThat(result).isInstanceOf(NormalizationResult.Rejected.class);
    assertThat(((NormalizationResult.Rejected) result).detail())
        .contains("SE declares 6 segments, found 5");
  }

  @Test
  void noticesALostSegmentTerminatorThatMergedTheTwoHeaders() {
    // Dropping the tilde after ISA merges it with GS into a single segment that still begins with
    // "ISA" and still parses. Every count below it agrees, so the missing GS is the only symptom.
    String body = interchange().replaceFirst("~", "");

    NormalizationResult result = normalize(body);

    assertThat(result).isInstanceOf(NormalizationResult.Partial.class);
    assertThat(((NormalizationResult.Partial) result).detail())
        .isEqualTo("no GS functional group header");
  }

  @Test
  void rejectsAnUnknownShipmentWithoutLosingTheRestOfTheBatch() {
    String batched = largestInterchange();
    int events = normalizeAll(batched).size();
    String unknown = batched.replaceFirst("SHP-[A-Z]{3}-\\d{4}", "SHP-XXX-9999");

    NormalizationResult result = normalize(unknown);

    assertThat(result).isInstanceOf(NormalizationResult.Partial.class);
    NormalizationResult.Partial partial = (NormalizationResult.Partial) result;
    assertThat(partial.events()).hasSize(events - 1);
    assertThat(partial.reason()).isEqualTo(RejectionReason.UNRESOLVED_IDENTITY);
    assertThat(partial.detail()).contains("SHP-XXX-9999");
  }

  @Test
  void rejectsRatherThanThrowsOnEveryCorruptedFixture() {
    List<String> bodies = committedInterchanges("faults/edi-214");
    assertThat(bodies).isNotEmpty();

    List<NormalizationResult> results = bodies.stream().map(this::normalize).toList();
    List<NormalizationResult> rejected =
        results.stream().filter(NormalizationResult.Rejected.class::isInstance).toList();

    // Both kinds are in the directory: the chaos run corrupts a fraction of what it emits. The
    // damaged files must become values rather than exceptions, and the intact ones beside them must
    // still get through.
    assertThat(rejected).as("corrupted interchanges in the chaos capture").isNotEmpty();
    assertThat(results).hasSizeGreaterThan(rejected.size());
  }

  @Test
  void rejectsAnUpstreamErrorPageWithoutQuotingTheWholeThingBack() {
    NormalizationResult result = normalize("<html><body>502 Bad Gateway</body></html>");

    assertThat(result).isInstanceOf(NormalizationResult.Rejected.class);
    NormalizationResult.Rejected rejected = (NormalizationResult.Rejected) result;
    assertThat(rejected.reason()).isEqualTo(RejectionReason.MALFORMED_PAYLOAD);
    // The payload travels alongside the reason in full; repeating it here would double the size of
    // every dead-letter message.
    assertThat(rejected.detail()).hasSizeLessThan(80);
  }

  @Test
  void rejectsAnEmptyBody() {
    assertThat(normalize("   "))
        .isEqualTo(new NormalizationResult.Rejected(RejectionReason.MALFORMED_PAYLOAD, "no X12 segments"));
  }
}
