package com.fleettracking.simulator.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.EventJson;
import com.fleettracking.simulator.route.Lanes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the committed lane file against drifting away from the lanes themselves.
 *
 * <p>The file in {@code docs/samples} is what the seeding script loads, so a stop that exists in
 * code and not in the file is a geofence that will never fire, and a coordinate that differs
 * between the two is a geofence that fires in the wrong place. Neither would fail any other build.
 * The last test here is the one that matters: it compares the committed bytes against what the
 * exporter produces now, so changing a lane without regenerating the file fails the build that
 * changed it rather than a demo three weeks later.
 */
class LaneExportTest {

  /**
   * Walks up from the module directory to the repository root, since tests run with the working
   * directory set to the module rather than the root.
   */
  private static final Path COMMITTED =
      Path.of("..", "..", "docs", "samples", "lanes.json").normalize();

  @Test
  void exportsEveryLane() {
    LaneExport.Catalogue catalogue = parse(LaneExport.asJson());

    assertThat(catalogue.lanes()).hasSameSizeAs(Lanes.ALL);
    assertThat(catalogue.lanes()).extracting(LaneExport.Lane::code)
        .containsExactly("DEL", "HYD", "BLR", "BOM");
  }

  @Test
  void carriesEveryStopOfEveryLaneInOrder() {
    LaneExport.Catalogue catalogue = parse(LaneExport.asJson());

    for (int i = 0; i < Lanes.ALL.size(); i++) {
      var route = Lanes.ALL.get(i);
      var lane = catalogue.lanes().get(i);

      assertThat(lane.routeId()).isEqualTo(route.id());
      assertThat(lane.stops()).hasSameSizeAs(route.stops());
      assertThat(lane.stops()).extracting(LaneExport.ExportedStop::seq)
          .containsExactlyElementsOf(java.util.stream.IntStream.range(0, route.stops().size()).boxed().toList());

      for (int s = 0; s < route.stops().size(); s++) {
        var stop = route.stops().get(s);
        var exported = lane.stops().get(s);
        assertThat(exported.stopId()).isEqualTo(stop.id());
        assertThat(exported.latitude()).isEqualTo(stop.location().latitude());
        assertThat(exported.longitude()).isEqualTo(stop.location().longitude());
        assertThat(exported.radiusMeters()).isEqualTo(stop.geofenceRadiusMeters());
      }
    }
  }

  /**
   * The geofence radii are genuinely different per stop, which is the whole reason the radius
   * belongs to the stop rather than to a setting. If this ever collapses to one value, the
   * geofencer stops being tested against the case it exists for.
   */
  @Test
  void keepsDistinctGeofenceRadii() {
    LaneExport.Catalogue catalogue = parse(LaneExport.asJson());

    List<Double> radii =
        catalogue.lanes().stream()
            .flatMap(lane -> lane.stops().stream())
            .map(LaneExport.ExportedStop::radiusMeters)
            .distinct()
            .sorted()
            .toList();

    assertThat(radii).containsExactly(120.0, 400.0);
  }

  @Test
  void theCommittedFileMatchesWhatTheExporterProducesNow() throws IOException {
    assertThat(COMMITTED)
        .as("docs/samples/lanes.json is missing; regenerate it — see docs/samples/README.md")
        .exists();

    assertThat(Files.readString(COMMITTED).replace("\r\n", "\n"))
        .as("a lane changed without docs/samples/lanes.json being regenerated")
        .isEqualTo(LaneExport.asJson().replace("\r\n", "\n"));
  }

  private static LaneExport.Catalogue parse(String json) {
    return EventJson.mapper().readValue(json, LaneExport.Catalogue.class);
  }
}
