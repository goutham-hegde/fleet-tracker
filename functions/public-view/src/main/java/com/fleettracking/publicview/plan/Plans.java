package com.fleettracking.publicview.plan;

import com.fleettracking.events.EventJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Which stops each load is planned to visit, read from the lane catalogue packaged into this
 * function.
 *
 * <h2>The same file the seed script reads, not a copy of the itinerary collection</h2>
 *
 * <p>The live dashboard reads plans from MongoDB, which this function cannot reach: Mongo runs on a
 * laptop behind a home router. What MongoDB holds is itself generated, though. {@code
 * seed-itinerary.sh} loads {@code docs/samples/lanes.json}, which the simulator exports, and the
 * build puts that same committed file into this function. So a stop's name, position and geofence
 * radius have one home, and both readers get them from it.
 *
 * <h2>The shipment id names its lane</h2>
 *
 * <p>The simulator names a load {@code SHP-<lane code>-<number>}, and the seed script relies on
 * exactly that to know which plan a load follows. This applies the same rule. A load whose code
 * matches no lane has no plan, which is not an error: the tracking processor stores such positions
 * and announces nothing, and the live map draws the truck with no route. Here too.
 */
public final class Plans {

  /** One lane: the code in a shipment id, and its stops in the order they are visited. */
  public record Lane(String code, String routeId, String name, List<Stop> stops) {}

  /**
   * A planned stop, as the catalogue states it.
   *
   * <p>{@code dwellSeconds} is in the catalogue and deliberately not here: it is the scheduled
   * service time, which the platform has never loaded (see the ETA notes), and a public page is no
   * place to start using it.
   */
  public record Stop(
      String stopId,
      int seq,
      String name,
      String city,
      String state,
      double latitude,
      double longitude,
      double radiusMeters,
      String kind) {}

  private record Catalogue(List<Lane> lanes) {}

  private static final String RESOURCE = "/plan/lanes.json";

  private final Map<String, Lane> byCode;

  private Plans(List<Lane> lanes) {
    this.byCode =
        lanes.stream()
            .map(
                lane ->
                    new Lane(
                        lane.code(),
                        lane.routeId(),
                        lane.name(),
                        lane.stops().stream().sorted(Comparator.comparingInt(Stop::seq)).toList()))
            .collect(Collectors.toMap(Lane::code, Function.identity()));
  }

  /** The catalogue this function was built with. Fails loudly if the build left it out. */
  public static Plans packaged() {
    try (InputStream in = Plans.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(
            RESOURCE + " is not on the classpath; the build copies it from docs/samples");
      }
      return new Plans(EventJson.mapper().readValue(in, Catalogue.class).lanes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** For tests. */
  public static Plans of(List<Lane> lanes) {
    return new Plans(lanes);
  }

  /** The stops a load is planned to visit, in order, or empty for a load nobody planned. */
  public List<Stop> forShipment(String shipmentId) {
    return Optional.ofNullable(byCode.get(codeOf(shipmentId))).map(Lane::stops).orElse(List.of());
  }

  public int laneCount() {
    return byCode.size();
  }

  /** {@code SHP-HYD-0002} is on lane {@code HYD}. Anything else is on no lane. */
  static String codeOf(String shipmentId) {
    if (shipmentId == null || !shipmentId.startsWith("SHP-")) {
      return "";
    }
    int end = shipmentId.lastIndexOf('-');
    return end <= 4 ? "" : shipmentId.substring(4, end);
  }
}
