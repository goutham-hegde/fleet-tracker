package com.fleettracking.gateway.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * The temporal lookup against a real MongoDB.
 *
 * <p>These assertions cannot be made against a fake. The behaviour under test is a query — a
 * half-open range on one field combined with a null-or-greater-than on another, matched against an
 * array without an explicit operator — and a hand-written stub would be asserting that the test
 * author's idea of that query matches their own idea of it. What is worth proving is that MongoDB
 * agrees.
 *
 * <p>An {@code *IT}, therefore, and not a {@code *Test}: it needs Docker and runs under Failsafe in
 * the {@code verify} phase.
 */
@Testcontainers
class MongoIdentityResolverIT {

  @Container
  static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

  private static MongoOperations mongo;
  private MongoIdentityResolver resolver;

  /** A day the scenarios below hang their hours off, so the arithmetic reads plainly. */
  private static final Instant DAY = Instant.parse("2026-08-31T00:00:00Z");

  private static Instant at(int hour) {
    return DAY.plus(Duration.ofHours(hour));
  }

  @BeforeAll
  static void connect() {
    mongo =
        new MongoTemplate(
            new SimpleMongoClientDatabaseFactory(MONGO.getConnectionString() + "/fleet-test"));
  }

  @BeforeEach
  void reset() {
    mongo.remove(new Query(), Assignment.class);
    resolver = new MongoIdentityResolver(mongo);
  }

  private void seed(Assignment... assignments) {
    mongo.insertAll(List.of(assignments));
  }

  @Test
  void resolvesAVehicleInsideItsWindow() {
    seed(Assignment.of("SHP-HYD-0002", "VEH-0002", List.of("TLM-0002"), at(6), at(18)));

    assertThat(resolver.byVehicle("VEH-0002", at(12)))
        .contains(new Identity("SHP-HYD-0002", "VEH-0002"));
  }

  @Test
  void resolvesNothingBeforeTheWindowOpens() {
    seed(Assignment.of("SHP-HYD-0002", "VEH-0002", List.of("TLM-0002"), at(6), at(18)));

    // A telematics unit reporting at 05:00 is reporting about a truck that had not been given this
    // load yet. Attributing the position to it would put a shipment on a map before it existed.
    assertThat(resolver.byVehicle("VEH-0002", at(5))).isEmpty();
  }

  @Test
  void theWindowIncludesItsStartAndExcludesItsEnd() {
    seed(Assignment.of("SHP-HYD-0002", "VEH-0002", List.of("TLM-0002"), at(6), at(18)));

    assertThat(resolver.byVehicle("VEH-0002", at(6))).isPresent();
    assertThat(resolver.byVehicle("VEH-0002", at(18))).isEmpty();
  }

  @Test
  void anOpenEndedAssignmentHasNoUpperBound() {
    // The normal state of a live fleet: dispatch has said when the load started and not when it
    // ends. Written as a null rather than a far-future sentinel, so the $or branch is load-bearing.
    seed(Assignment.of("SHP-DEL-0001", "VEH-0001", List.of("TLM-0001"), at(6), null));

    assertThat(resolver.byVehicle("VEH-0001", at(23))).isPresent();
    assertThat(resolver.byVehicle("VEH-0001", DAY.plus(Duration.ofDays(400)))).isPresent();
  }

  @Test
  void theSameTractorResolvesToDifferentLoadsAtDifferentTimes() {
    // The whole reason reference data left the configuration file. Two consecutive loads on one
    // tractor, meeting exactly at 14:00.
    seed(
        Assignment.of("SHP-HYD-0002", "VEH-0002", List.of("TLM-0002"), at(2), at(14)),
        Assignment.of("SHP-HYD-0042", "VEH-0002", List.of("TLM-0002"), at(14), null));

    assertThat(resolver.byVehicle("VEH-0002", at(13)).orElseThrow().shipmentId())
        .isEqualTo("SHP-HYD-0002");
    assertThat(resolver.byVehicle("VEH-0002", at(15)).orElseThrow().shipmentId())
        .isEqualTo("SHP-HYD-0042");
  }

  @Test
  void aLateFiledEventResolvesToWhatWasTrueWhenItHappened() {
    // The EDI 214 case, stated as a test. The carrier files the 14:00 arrival of the first load at
    // 20:00, six hours after the tractor has already picked up the next one. Asking "now" would
    // attribute the end of one load to the start of another.
    seed(
        Assignment.of("SHP-HYD-0002", "VEH-0002", List.of("TLM-0002"), at(2), at(14)),
        Assignment.of("SHP-HYD-0042", "VEH-0002", List.of("TLM-0042"), at(14), null));

    Instant stated = at(13).plus(Duration.ofMinutes(58));
    assertThat(resolver.byShipment("SHP-HYD-0002", stated).orElseThrow().vehicleId())
        .isEqualTo("VEH-0002");
    assertThat(resolver.byShipment("SHP-HYD-0002", at(20))).isEmpty();
  }

  @Test
  void resolvesADeviceThroughItsVehicleToALoad() {
    // Both hops in one query: the probe knows only its own name, and the array match is what turns
    // that into a shipment and a vehicle.
    seed(
        Assignment.of(
            "SHP-HYD-0002", "VEH-0002", List.of("TLM-0002", "DEV-0002"), at(0), null));

    assertThat(resolver.byDevice("DEV-0002", at(9)))
        .contains(new Identity("SHP-HYD-0002", "VEH-0002"));
    assertThat(resolver.byDevice("TLM-0002", at(9)))
        .contains(new Identity("SHP-HYD-0002", "VEH-0002"));
  }

  @Test
  void aTrailerSwappedMidDayResolvesToWhicheverTractorHadItAtTheTime() {
    // The probe is bolted to the trailer, not to the tractor, so the same device id genuinely
    // belongs to two vehicles on the same day. This is the lookup that would be silently wrong
    // under an "as of now" resolver, and wrong in the most expensive way: a real temperature
    // reading filed against the wrong load.
    seed(
        Assignment.of("SHP-HYD-0002", "VEH-0002", List.of("DEV-0002"), at(0), at(12)),
        Assignment.of("SHP-BLR-0003", "VEH-0003", List.of("DEV-0002"), at(12), null));

    assertThat(resolver.byDevice("DEV-0002", at(11)).orElseThrow().vehicleId()).isEqualTo("VEH-0002");
    assertThat(resolver.byDevice("DEV-0002", at(13)).orElseThrow().vehicleId()).isEqualTo("VEH-0003");
  }

  @Test
  void overlappingAssignmentsResolveToNothing() {
    // Contradictory reference data: dispatch has said one tractor is pulling two loads at once.
    // Returning either would publish positions attributed to a load that may not be on the truck,
    // so the resolver returns neither and the message is dead-lettered.
    seed(
        Assignment.of("SHP-HYD-0002", "VEH-0002", List.of("TLM-0002"), at(0), at(18)),
        Assignment.of("SHP-HYD-0099", "VEH-0002", List.of("TLM-0099"), at(6), null));

    assertThat(resolver.byVehicle("VEH-0002", at(9))).isEmpty();
    // Outside the overlap it is unambiguous again, and answering is correct.
    assertThat(resolver.byVehicle("VEH-0002", at(3)).orElseThrow().shipmentId())
        .isEqualTo("SHP-HYD-0002");
  }

  @Test
  void anUnknownIdentifierResolvesToNothingRatherThanThrowing() {
    seed(Assignment.of("SHP-HYD-0002", "VEH-0002", List.of("TLM-0002"), at(0), null));

    assertThat(resolver.byVehicle("VEH-9999", at(9))).isEmpty();
    assertThat(resolver.byDevice("DEV-9999", at(9))).isEmpty();
    assertThat(resolver.byShipment("SHP-NOPE-0000", at(9))).isEmpty();
    assertThat(resolver.byVehicle(null, at(9))).isEmpty();
    assertThat(resolver.byVehicle("VEH-0002", null)).isEmpty();
  }

  @Test
  void reSeedingTheSameAssignmentUpdatesItRatherThanDuplicatingIt() {
    // Why the document id is derived from the shipment and the start instant. An operator re-runs
    // the seed script after a cluster rebuild; with generated ids that would produce a second row
    // for the same assignment, and the resolver would report it as contradictory.
    Assignment first =
        Assignment.of("SHP-DEL-0001", "VEH-0001", List.of("TLM-0001"), at(6), null);
    Assignment corrected =
        Assignment.of("SHP-DEL-0001", "VEH-0001", List.of("TLM-0001", "DEV-0001"), at(6), null);

    mongo.save(first);
    mongo.save(corrected);

    assertThat(mongo.count(new Query(), Assignment.class)).isEqualTo(1);
    assertThat(resolver.byDevice("DEV-0001", at(9))).isPresent();
  }
}
