package com.fleettracking.gateway.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.SourceSystem;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventIdsTest {

  private static final Instant AT = Instant.parse("2026-08-31T14:05:00Z");

  @Test
  void isStableForTheSameReportedFact() {
    // The property the whole design rests on: a resent message is recognisably the same event.
    assertThat(EventIds.of(SourceSystem.TELEMATICS, "TLM-0003", AT))
        .isEqualTo(EventIds.of(SourceSystem.TELEMATICS, "TLM-0003", AT));
  }

  @Test
  void separatesTwoBoxesOnOneTruckReportingTheSameInstant() {
    // A telematics unit and a reefer probe both reporting at 14:05:00 are two genuine events.
    assertThat(EventIds.of(SourceSystem.TELEMATICS, "TLM-0003", AT))
        .isNotEqualTo(EventIds.of(SourceSystem.REEFER_SENSOR, "DEV-0003", AT));
  }

  @Test
  void separatesTwoInstantsFromOneDevice() {
    assertThat(EventIds.of(SourceSystem.TELEMATICS, "TLM-0003", AT))
        .isNotEqualTo(EventIds.of(SourceSystem.TELEMATICS, "TLM-0003", AT.plusSeconds(1)));
  }

  @Test
  void separatesTwoEventsASourceFiledForTheSameInstant() {
    // One EDI interchange can report an arrival and a departure with the same HHMM stamp.
    assertThat(EventIds.of(SourceSystem.EDI_214, "SHP-HOU-0004", AT, "X1"))
        .isNotEqualTo(EventIds.of(SourceSystem.EDI_214, "SHP-HOU-0004", AT, "CD"));
  }

  @Test
  void producesAWellFormedUuid() {
    assertThat(EventIds.of(SourceSystem.TELEMATICS, "TLM-0003", AT))
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }
}
