package com.fleettracking.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.GeoPoint;
import com.fleettracking.events.PositionEvent;
import com.fleettracking.events.RawPayload;
import com.fleettracking.events.SourceSystem;
import com.fleettracking.gateway.normalize.InboundMessage;
import com.fleettracking.gateway.normalize.NormalizationResult;
import com.fleettracking.gateway.normalize.Normalizer;
import com.fleettracking.gateway.normalize.RejectionReason;
import com.fleettracking.gateway.normalize.TelematicsNormalizer;
import com.fleettracking.gateway.publish.DeadLetter;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;

class IngestServiceTest {

  private static final Instant RECEIVED = Instant.parse("2026-08-31T10:00:00Z");

  private static final String GOOD =
      """
      {"deviceId":"TLM-0003","vehicle":{"id":"VEH-0003"},
       "gps":{"lat":35.1495,"lon":-90.049,"speedMph":62.5,"headingDeg":47.4,"hdop":1.2,
              "fixTime":"2026-08-31T14:05:00Z"},
       "odometer":{"value":100.0,"unit":"mi"},"sentAt":"2026-08-31T14:05:02Z"}
      """;

  private final RecordingPublisher publisher = new RecordingPublisher();

  private static Validator validator() {
    return Validation.byProvider(HibernateValidator.class)
        .configure()
        .messageInterpolator(new ParameterMessageInterpolator())
        .buildValidatorFactory()
        .getValidator();
  }

  private IngestService service(Normalizer... normalizers) {
    return new IngestService(List.of(normalizers), validator(), publisher);
  }

  private static InboundMessage inbound(SourceSystem source, String body) {
    return new InboundMessage(source, "application/json", body, RECEIVED);
  }

  @Test
  void publishesAValidMessageAndDeadLettersNothing() {
    IngestOutcome outcome =
        service(new TelematicsNormalizer(Fixtures.defaultFleet()))
            .accept(inbound(SourceSystem.TELEMATICS, GOOD));

    assertThat(outcome.published()).isEqualTo(1);
    assertThat(outcome.deadLettered()).isZero();
    assertThat(publisher.published()).hasSize(1);
    assertThat(publisher.deadLettered()).isEmpty();
    assertThat(publisher.published().getFirst().shipmentId()).isEqualTo("SHP-ATL-0003");
  }

  @Test
  void deadLettersAMalformedMessageWithTheOriginalBytesIntact() {
    String truncated = GOOD.substring(0, 60);

    IngestOutcome outcome =
        service(new TelematicsNormalizer(Fixtures.defaultFleet()))
            .accept(inbound(SourceSystem.TELEMATICS, truncated));

    assertThat(outcome.isFullyRejected()).isTrue();
    assertThat(publisher.published()).isEmpty();
    assertThat(publisher.deadLettered()).hasSize(1);

    DeadLetter dead = publisher.deadLettered().getFirst();
    assertThat(dead.reason()).isEqualTo(RejectionReason.MALFORMED_PAYLOAD);
    assertThat(dead.source()).isEqualTo(SourceSystem.TELEMATICS);
    assertThat(dead.receivedAt()).isEqualTo(RECEIVED);
    // The whole point of the topic: the payload survives, so fixing the parser and replaying it
    // recovers the event rather than only recording that one was lost.
    assertThat(dead.body()).isEqualTo(truncated);
  }

  @Test
  void deadLettersAnEventThatParsesButBreaksTheEnvelopesConstraints() {
    // A normalizer that produces a structurally fine event with an impossible latitude. This is
    // the case validation exists for: nothing here is malformed, and publishing it would put a
    // truck off the map for every consumer downstream.
    Normalizer broken =
        new Normalizer() {
          @Override
          public SourceSystem source() {
            return SourceSystem.TELEMATICS;
          }

          @Override
          public NormalizationResult normalize(InboundMessage message) {
            return NormalizationResult.of(
                new PositionEvent(
                    "id-1",
                    "SHP-ATL-0003",
                    "VEH-0003",
                    "TLM-0003",
                    RECEIVED,
                    RECEIVED,
                    new GeoPoint(240.0, -90.0),
                    null,
                    null,
                    null,
                    null,
                    RawPayload.of(SourceSystem.TELEMATICS, message.body())));
          }
        };

    IngestOutcome outcome = service(broken).accept(inbound(SourceSystem.TELEMATICS, GOOD));

    assertThat(outcome.reason()).isEqualTo(RejectionReason.INVALID_VALUE);
    assertThat(publisher.published()).isEmpty();
    assertThat(publisher.deadLettered()).hasSize(1);
    assertThat(publisher.deadLettered().getFirst().detail()).contains("position.latitude");
  }

  @Test
  void deadLettersAFeedItHasNoNormalizerFor() {
    IngestOutcome outcome =
        service(new TelematicsNormalizer(Fixtures.defaultFleet()))
            .accept(inbound(SourceSystem.REEFER_SENSOR, "{}"));

    assertThat(outcome.reason()).isEqualTo(RejectionReason.UNSUPPORTED_FEED);
    assertThat(publisher.deadLettered()).hasSize(1);
  }

  @Test
  void reportsWhichFeedsItCanRead() {
    IngestService service = service(new TelematicsNormalizer(Fixtures.defaultFleet()));

    assertThat(service.handles(SourceSystem.TELEMATICS)).isTrue();
    assertThat(service.handles(SourceSystem.MOBILE_APP)).isFalse();
  }

  @Test
  void describesEveryConstraintViolationInAStableOrder() {
    // Two things wrong at once. The detail string is what someone groups a morning's rejections
    // by, so it must not depend on the order a validator happened to return violations in.
    Normalizer broken =
        new Normalizer() {
          @Override
          public SourceSystem source() {
            return SourceSystem.TELEMATICS;
          }

          @Override
          public NormalizationResult normalize(InboundMessage message) {
            return NormalizationResult.of(
                new PositionEvent(
                    "id-1",
                    "SHP-ATL-0003",
                    "VEH-0003",
                    "TLM-0003",
                    RECEIVED,
                    RECEIVED,
                    new GeoPoint(240.0, -400.0),
                    null,
                    null,
                    null,
                    null,
                    RawPayload.of(SourceSystem.TELEMATICS, message.body())));
          }
        };

    service(broken).accept(inbound(SourceSystem.TELEMATICS, GOOD));
    String detail = publisher.deadLettered().getFirst().detail();

    assertThat(detail).contains("position.latitude").contains("position.longitude");
    assertThat(detail.indexOf("latitude")).isLessThan(detail.indexOf("longitude"));
  }
}
