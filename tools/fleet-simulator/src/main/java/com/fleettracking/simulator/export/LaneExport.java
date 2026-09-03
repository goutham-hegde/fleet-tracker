package com.fleettracking.simulator.export;

import com.fleettracking.events.EventJson;
import com.fleettracking.simulator.route.Lanes;
import com.fleettracking.simulator.route.Route;
import com.fleettracking.simulator.route.Stop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Writes the lane definitions out as JSON, so that something other than the simulator can know
 * where the stops are.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>M3's geofencing has to decide that a truck has arrived at a stop, which means it needs to know
 * where the stop is and how big its geofence is. That information lives in {@link Lanes}, as Java,
 * inside the simulator — and the simulator is a test tool. A production service must not depend on
 * it, in the same way that the thing being measured must not be wired to the ruler.
 *
 * <p>The alternative was to retype the coordinates into the seeding script. Twenty-odd stops of
 * latitude and longitude, maintained in two places, where a transposed digit does not fail any
 * build and instead surfaces as a geofence that never triggers. That is precisely the "two
 * statements of the truth" trap S8 removed from identity resolution, and it is not worth
 * reintroducing for the sake of avoiding one small exporter.
 *
 * <p>So the coordinates keep exactly one home, here in code, and this writes a committed copy of
 * them into {@code docs/samples/} — the same arrangement as the contract fixtures, which are also
 * generated from a fixed source and then committed so that nothing downstream has to run the
 * simulator to use them.
 *
 * <h2>Running it</h2>
 *
 * <pre>{@code
 * ./mvnw -pl tools/fleet-simulator -am package
 * java -Dloader.main=com.fleettracking.simulator.export.LaneExport \
 *   -cp tools/fleet-simulator/target/fleet-simulator-0.1.0-SNAPSHOT.jar \
 *   org.springframework.boot.loader.launch.PropertiesLauncher docs/samples/lanes.json
 * }</pre>
 *
 * <p>The launcher is not optional. The {@code repackage} goal moves this module's classes under
 * {@code BOOT-INF/classes}, where a plain {@code -cp} cannot see them, and the failure is a
 * {@code ClassNotFoundException} naming a class that is plainly in the jar.
 *
 * <p>A plain {@code main} rather than another simulator flag. Every existing flag configures a
 * <em>run</em>; this produces a file and stops, and folding it into the run configuration would
 * mean a mode where the trucks do not move, which reads as a bug the first time someone meets it.
 */
public final class LaneExport {

  private LaneExport() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 1) {
      System.err.println("usage: LaneExport <output-file>");
      System.exit(2);
    }
    Path out = Path.of(args[0]);
    if (out.getParent() != null) {
      Files.createDirectories(out.getParent());
    }
    String json = asJson();
    Files.writeString(out, json, StandardCharsets.UTF_8);
    System.out.printf(
        Locale.ROOT, "wrote %d lanes to %s (%d bytes)%n", Lanes.ALL.size(), out, json.length());
  }

  /**
   * The lane catalogue as pretty-printed JSON, exactly as it is committed.
   *
   * <p>Line endings are forced to LF. Jackson's pretty printer uses the platform separator, so this
   * file would otherwise be written with CRLF on Windows and LF elsewhere — a committed artifact
   * whose bytes depend on who regenerated it, showing up as a whole-file diff every time the
   * machine changes. The same reasoning as the {@code .gitattributes} rules on the shell scripts.
   */
  public static String asJson() {
    return EventJson.mapper()
        .writerWithDefaultPrettyPrinter()
        .writeValueAsString(new Catalogue(Lanes.ALL.stream().map(LaneExport::lane).toList()))
        .replace("\r\n", "\n");
  }

  private static Lane lane(Route route) {
    return new Lane(
        laneCode(route),
        route.id(),
        route.name(),
        java.util.stream.IntStream.range(0, route.stops().size())
            .mapToObj(seq -> stop(route.stops().get(seq), seq))
            .toList());
  }

  /**
   * The three-letter code that appears in a shipment id.
   *
   * <p>Derived here by the same rule the simulator uses when it names a load, rather than written
   * down as a fourth list of lane codes. If that rule ever changes, this follows it.
   */
  public static String laneCode(Route route) {
    return route.id().substring(0, 3).toUpperCase(Locale.ROOT);
  }

  private static ExportedStop stop(Stop stop, int seq) {
    return new ExportedStop(
        stop.id(),
        seq,
        stop.name(),
        stop.city(),
        stop.state(),
        stop.location().latitude(),
        stop.location().longitude(),
        stop.geofenceRadiusMeters(),
        stop.kind().name(),
        stop.dwell().toSeconds());
  }

  /** The whole file. Wrapped in an object rather than a bare array so fields can be added later. */
  public record Catalogue(List<Lane> lanes) {}

  /**
   * One lane.
   *
   * @param code the three letters that appear in a shipment id on this lane, e.g. {@code CHI}
   */
  public record Lane(String code, String routeId, String name, List<ExportedStop> stops) {}

  /**
   * One stop, flattened.
   *
   * <p>Latitude and longitude are separate numbers rather than a nested point, because the consumer
   * of this file is a shell script driving {@code mongosh}, and every level of nesting is another
   * thing for it to get wrong. {@code dwellSeconds} is how long the truck is scheduled to stay,
   * which is not the same as the dwell threshold the geofencer applies before believing it has
   * arrived — that one is the platform's judgement and does not belong in reference data.
   */
  public record ExportedStop(
      String stopId,
      int seq,
      String name,
      String city,
      String state,
      double latitude,
      double longitude,
      double radiusMeters,
      String kind,
      long dwellSeconds) {}
}
