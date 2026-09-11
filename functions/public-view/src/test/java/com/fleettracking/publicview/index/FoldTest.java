package com.fleettracking.publicview.index;

import static com.fleettracking.publicview.Events.arrived;
import static com.fleettracking.publicview.Events.at;
import static com.fleettracking.publicview.Events.cleared;
import static com.fleettracking.publicview.Events.departed;
import static com.fleettracking.publicview.Events.estimate;
import static com.fleettracking.publicview.Events.position;
import static com.fleettracking.publicview.Events.raised;
import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.Severity;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FoldTest {

  @Test
  void theNewestFixInEventTimeWinsWhateverOrderTheLinesArrivedIn() {
    // A mobile-app backlog: the burst is delivered newest first, then the stragglers.
    Fold fold =
        new Fold()
            .add(position("SHP-HYD-0002", 30, 17.3, 78.1, 60.0))
            .add(position("SHP-HYD-0002", 10, 17.1, 78.0, 55.0))
            .add(position("SHP-HYD-0002", 20, 17.2, 78.05, 58.0));

    assertThat(fold.positions()).hasSize(1);
    assertThat(fold.positions().get("SHP-HYD-0002").occurredAt()).isEqualTo(at(30));
  }

  @Test
  void aFileOfThousandsOfLinesFoldsToOneRowPerShipment() {
    Fold fold = new Fold();
    for (int minute = 0; minute < 3000; minute++) {
      fold.add(position("SHP-HYD-0002", minute, 17, 78, 50.0));
      fold.add(position("SHP-DEL-0001", minute, 28, 77, 50.0));
    }
    assertThat(fold.events()).isEqualTo(6000);
    assertThat(fold.facts()).isEqualTo(2);
  }

  @Test
  void anIncidentsRaiseAndClearBecomeOneIncident() {
    Fold fold =
        new Fold()
            .add(cleared("SHP-HYD-0002", "inc-1", 5, 45))
            .add(raised("SHP-HYD-0002", "inc-1", Severity.WARNING, 5));

    assertThat(fold.incidents()).hasSize(1);
    Fold.Incident incident = fold.incidents().get("inc-1");
    assertThat(incident.raised()).isNotNull();
    assertThat(incident.cleared()).isNotNull();
  }

  @Test
  void arrivalsAndDeparturesAreKeptPerStop() {
    Fold fold =
        new Fold()
            .add(arrived("SHP-HYD-0002", "hyd-genome", 0))
            .add(departed("SHP-HYD-0002", "hyd-genome", 0, 40))
            .add(arrived("SHP-HYD-0002", "knl-clinic", 200));

    assertThat(fold.arrivals()).hasSize(2);
    assertThat(fold.departures()).hasSize(1);
  }

  @Test
  void estimatesAreCountedAndLeftOut() {
    Fold fold = new Fold().add(estimate("SHP-HYD-0002", "knl-clinic", 10));

    assertThat(fold.events()).isEqualTo(1);
    assertThat(fold.skipped()).isEqualTo(1);
    assertThat(fold.facts()).isZero();
  }

  @Test
  void theArchiveRunsUpToTheNewestKafkaTimestamp() {
    Fold fold = new Fold();
    fold.received(at(5));
    fold.received(at(9));
    fold.received(at(7));
    assertThat(fold.newestReceived()).isEqualTo(at(9));
  }

  @Test
  void aNotifiedKeyIsDecodedBackToTheStoredOne() {
    String stored = "archive/position.events.v1/dt=2026-09-11/hour=12/p3-o18422-1757599322117.ndjson.gz";
    // What an S3 notification actually sends: '=' as %3D, and '/' may or may not be encoded.
    String notified = URLEncoder.encode(stored, StandardCharsets.UTF_8).replace("%2F", "/");

    assertThat(notified).contains("dt%3D2026-09-11");
    assertThat(ArchiveFiles.decodeKey(notified)).isEqualTo(stored);
    assertThat(ArchiveFiles.topicOf(stored)).isEqualTo("position.events.v1");
    assertThat(ArchiveFiles.topicOf("stray.txt")).isEmpty();
  }
}
