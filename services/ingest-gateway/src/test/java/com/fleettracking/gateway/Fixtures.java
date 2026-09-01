package com.fleettracking.gateway;

import com.fleettracking.gateway.identity.Assignment;
import com.fleettracking.gateway.identity.InMemoryIdentityResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Access to the committed contract fixtures in {@code docs/samples}.
 *
 * <p>The normalizers are tested against payloads captured from the simulator and checked into the
 * repository, rather than against payloads written next to the parser. A parser tested only on
 * examples its own author invented proves that it agrees with itself, which is the one thing that
 * was never in doubt.
 */
public final class Fixtures {

  private Fixtures() {}

  /**
   * Finds {@code docs/samples} by walking up from the working directory.
   *
   * <p>Maven runs tests with the working directory set to the module, while an IDE frequently uses
   * the repository root, and a hard-coded {@code ../../docs} passes under one and fails under the
   * other. Walking up finds it either way.
   */
  public static Path samples() {
    Path dir = Path.of("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve("docs").resolve("samples");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException("docs/samples not found above " + Path.of("").toAbsolutePath());
  }

  /** Every non-blank line of a JSON-lines fixture. */
  public static List<String> lines(String relativePath) {
    try {
      List<String> kept = new ArrayList<>();
      for (String line : Files.readAllLines(samples().resolve(relativePath), StandardCharsets.UTF_8)) {
        if (!line.isBlank()) {
          kept.add(line);
        }
      }
      return kept;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Reference data matching the simulator's default eight-truck fleet.
   *
   * <p>The simulator deals trucks onto its four lanes in turn, so truck 1 runs the Chicago lane,
   * truck 2 the Los Angeles cold-chain lane, and so on. Each truck carries a telematics unit
   * numbered {@code TLM-} and, on the reefer lanes, a probe numbered {@code DEV-} — the same truck
   * under two identifiers that share nothing, which is the whole reason identity resolution exists.
   */
  public static InMemoryIdentityResolver defaultFleet() {
    return new InMemoryIdentityResolver(defaultFleetAssignments());
  }

  /**
   * The same fleet as a list of assignments, for tests that seed a real database with it.
   *
   * <p>All eight are open-ended and start well before the committed fixtures were captured. That is
   * not laziness about the temporal dimension — it is what makes these assignments usable by tests
   * whose payloads are stamped {@code 2026-08-31}. A window starting "now" would make every
   * fixture resolve to nothing, and the failure would look like a broken normalizer rather than
   * like reference data that had not been backdated. Tests that are specifically about validity
   * windows build their own assignments.
   */
  public static List<Assignment> defaultFleetAssignments() {
    String[] lanes = {"CHI", "LAX", "ATL", "HOU"};
    List<Assignment> assignments = new ArrayList<>();
    for (int number = 1; number <= 8; number++) {
      String suffix = "%04d".formatted(number);
      assignments.add(
          Assignment.of(
              "SHP-%s-%s".formatted(lanes[(number - 1) % lanes.length], suffix),
              "VEH-" + suffix,
              List.of("TLM-" + suffix, "DEV-" + suffix),
              FLEET_EPOCH,
              null));
    }
    return assignments;
  }

  /** When the standing fleet assignments begin: long before any committed fixture was captured. */
  public static final Instant FLEET_EPOCH = Instant.parse("2026-01-01T00:00:00Z");
}
