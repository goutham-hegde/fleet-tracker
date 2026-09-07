package com.fleettracking.exceptions.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.ExceptionType;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The identity rules the raise-and-clear pairing rests on.
 *
 * <p>These read like tautologies and are not. Every one of them corresponds to a way the pairing can
 * silently break, and a broken pairing does not fail loudly — it produces a dashboard where one late
 * truck is forty alerts, or where an incident never closes because the clear named something else.
 */
class IncidentIdsTest {

  private static final Instant ONSET = Instant.parse("2026-09-01T08:00:00Z");

  @Test
  void theSameConditionAlwaysGetsTheSameId() {
    // The property that makes publish-then-record safe, and the one that lets a clear published by
    // a restarted process pair with a raise published by the one before it.
    String first =
        IncidentIds.incident(ExceptionType.TEMPERATURE_EXCURSION, "SHP-HYD-0002", null, ONSET);
    String second =
        IncidentIds.incident(ExceptionType.TEMPERATURE_EXCURSION, "SHP-HYD-0002", null, ONSET);

    assertThat(first).isEqualTo(second);
  }

  @Test
  void aDifferentOnsetIsADifferentIncident() {
    // A reefer that broke its band, recovered, and broke it again has had two incidents. They must
    // not collapse into one, or the second would be suppressed as a duplicate of the first.
    String first =
        IncidentIds.incident(ExceptionType.TEMPERATURE_EXCURSION, "SHP-HYD-0002", null, ONSET);
    String later =
        IncidentIds.incident(
            ExceptionType.TEMPERATURE_EXCURSION, "SHP-HYD-0002", null, ONSET.plusSeconds(3600));

    assertThat(first).isNotEqualTo(later);
  }

  @Test
  void theTypeIsPartOfTheName() {
    // A breakdown at a given moment can produce both an unplanned stop and, minutes later, a
    // signal loss. Same shipment, same instant, two entirely different incidents.
    String stopped = IncidentIds.incident(ExceptionType.UNPLANNED_STOP, "SHP-A", null, ONSET);
    String silent = IncidentIds.incident(ExceptionType.SIGNAL_LOSS, "SHP-A", null, ONSET);

    assertThat(stopped).isNotEqualTo(silent);
  }

  @Test
  void theStopIsPartOfTheName() {
    // A multi-drop run can be late to two different stops, and each is its own incident.
    String atOne = IncidentIds.incident(ExceptionType.LATE_ARRIVAL, "SHP-A", "knl-clinic", ONSET);
    String atAnother = IncidentIds.incident(ExceptionType.LATE_ARRIVAL, "SHP-A", "blr-hosp", ONSET);

    assertThat(atOne).isNotEqualTo(atAnother);
  }

  @Test
  void aStoplessIncidentStillHasAStableName() {
    String first = IncidentIds.incident(ExceptionType.SIGNAL_LOSS, "SHP-A", null, ONSET);
    String second = IncidentIds.incident(ExceptionType.SIGNAL_LOSS, "SHP-A", null, ONSET);

    assertThat(first).isEqualTo(second).isNotBlank();
  }

  @Test
  void theTwoEventsOfOneIncidentAreNamedDifferentlyFromEachOtherAndFromTheIncident() {
    String incident = IncidentIds.incident(ExceptionType.UNPLANNED_STOP, "SHP-A", null, ONSET);

    String raised = IncidentIds.raised(incident);
    String cleared = IncidentIds.cleared(incident);

    // Three distinct identifiers. Collapsing the raise and the clear onto one id would make a
    // consumer that de-duplicates on event id discard the half that closes the incident.
    assertThat(raised).isNotEqualTo(cleared).isNotEqualTo(incident);
    assertThat(cleared).isNotEqualTo(incident);
  }

  @Test
  void republishingEitherEventProducesTheSameBytes() {
    String incident = IncidentIds.incident(ExceptionType.ROUTE_DEVIATION, "SHP-A", null, ONSET);

    assertThat(IncidentIds.raised(incident)).isEqualTo(IncidentIds.raised(incident));
    assertThat(IncidentIds.cleared(incident)).isEqualTo(IncidentIds.cleared(incident));
  }
}
