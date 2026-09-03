package com.fleettracking.tracking.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fleettracking.events.PositionEvent;
import com.fleettracking.tracking.Positions;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import org.bson.Document;
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
 * The storage layer against a real MongoDB.
 *
 * <p>None of what this class asserts can be checked without a server. Whether a collection comes
 * out as time-series or ordinary, whether a conditional upsert with no match inserts or collides,
 * and whether a duplicate insert into a time-series collection is refused or accepted are all
 * behaviours of MongoDB, not of this code — and two of the three turned out to be the opposite of
 * what a reasonable person would guess.
 *
 * <p>No Spring context here: the store takes a {@link MongoOperations} and a {@link Clock} and
 * nothing else, so a test can build one directly. That is worth keeping — a storage class that
 * needs an application context to be exercised is one that will only ever be tested through
 * everything else.
 */
@Testcontainers
class PositionStoreIT {

  @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

  private MongoOperations mongo;
  private PositionStore store;

  @BeforeEach
  void setUp() {
    mongo =
        new MongoTemplate(
            new SimpleMongoClientDatabaseFactory(MONGO.getConnectionString() + "/fleet-test"));
    // Fresh collections per test: the history is append-only by design, so leftovers from an
    // earlier test would make every count assertion depend on execution order.
    mongo.dropCollection(PositionPoint.COLLECTION);
    mongo.dropCollection(CurrentPosition.COLLECTION);
    store = new PositionStore(mongo, Clock.fixed(Positions.T0, ZoneOffset.UTC));
    store.ensureCollections();
  }

  /**
   * The destination is asserted, not assumed.
   *
   * <p>This machine runs an unrelated MongoDB on the default port, and in S8 a service silently
   * connected to it and passed its tests — writing and reading its own data in someone else's
   * database, which any test that round-trips a value is perfectly happy with. A test that could
   * pass against the wrong destination must name the destination.
   */
  @Test
  void talksToTheContainerAndNotToAnyLocalMongo() {
    assertThat(MONGO.getConnectionString()).contains(":" + MONGO.getFirstMappedPort());
    assertThat(MONGO.getFirstMappedPort()).isNotEqualTo(27017);
  }

  @Test
  void createsTheHistoryAsATimeSeriesCollection() {
    assertThat(collectionType(PositionPoint.COLLECTION)).isEqualTo("timeseries");
  }

  @Test
  void ensureCollectionsIsIdempotent() {
    store.ensureCollections();
    store.ensureCollections();
    assertThat(collectionType(PositionPoint.COLLECTION)).isEqualTo("timeseries");
  }

  /**
   * The failure this guards against is the quiet one: an ordinary collection where a time-series
   * one was intended looks completely normal until someone measures it.
   */
  @Test
  void refusesToRunAgainstAnOrdinaryCollectionOfTheSameName() {
    mongo.dropCollection(PositionPoint.COLLECTION);
    mongo.getCollection(PositionPoint.COLLECTION).insertOne(new Document("ts", "not a time series"));

    assertThatThrownBy(() -> store.ensureCollections())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a time-series collection");
  }

  @Test
  void appendsHistoryAndSetsCurrentPosition() {
    PositionEvent event = Positions.at("SHP-1", Duration.ZERO, 41.8781, -87.6298);

    assertThat(store.record(event, false)).isTrue();

    assertThat(store.historyCount()).isEqualTo(1);
    CurrentPosition current = store.currentPosition("SHP-1").orElseThrow();
    assertThat(current.eventId()).isEqualTo(event.eventId());
    assertThat(current.occurredAt()).isEqualTo(event.occurredAt());
    // GeoJSON is [longitude, latitude]; getX is longitude. If this ever reads 41.87 the swap has
    // been lost somewhere and every truck is in the wrong hemisphere.
    assertThat(current.location().getX()).isEqualTo(-87.6298);
    assertThat(current.location().getY()).isEqualTo(41.8781);
  }

  @Test
  void currentPositionMovesForwardInEventTime() {
    store.record(Positions.at("SHP-1", Duration.ZERO), false);
    store.record(Positions.at("SHP-1", Duration.ofMinutes(5), 42.0, -87.0), false);

    CurrentPosition current = store.currentPosition("SHP-1").orElseThrow();
    assertThat(current.occurredAt()).isEqualTo(Positions.T0.plus(Duration.ofMinutes(5)));
    assertThat(current.location().getY()).isEqualTo(42.0);
  }

  /**
   * The mobile feed's normal behaviour, not an edge case: a phone that lost signal dumps its
   * buffer out of order, and the last message to arrive is frequently the oldest.
   */
  @Test
  void anOlderEventJoinsHistoryButDoesNotMoveCurrentPosition() {
    store.record(Positions.at("SHP-1", Duration.ofMinutes(5), 42.0, -87.0), false);
    store.record(Positions.at("SHP-1", Duration.ZERO, 41.8781, -87.6298), false);

    assertThat(store.historyCount()).isEqualTo(2);
    CurrentPosition current = store.currentPosition("SHP-1").orElseThrow();
    assertThat(current.occurredAt()).isEqualTo(Positions.T0.plus(Duration.ofMinutes(5)));
    assertThat(current.location().getY()).isEqualTo(42.0);
    assertThat(store.trackedShipments()).isEqualTo(1);
  }

  /**
   * The reason the consumer has to carry a guard at all.
   *
   * <p>MongoDB refuses a unique index on a time-series collection, so the same measurement written
   * twice is simply there twice — the {@code _id} is decoration. This test pins that down as a fact
   * of the platform rather than a rumour, because it is the entire justification for
   * {@code PartitionGuard} existing.
   */
  @Test
  void theDatabaseDoesNotSuppressDuplicates() {
    PositionEvent event = Positions.at("SHP-1", Duration.ZERO);

    store.record(event, false);
    store.record(event, false);

    assertThat(store.historyCount()).isEqualTo(2);
    assertThat(store.trackedShipments()).isEqualTo(1);
  }

  @Test
  void theVerifyFlagSuppressesADuplicate() {
    PositionEvent event = Positions.at("SHP-1", Duration.ZERO);
    store.record(event, false);

    assertThat(store.record(event, true)).isFalse();
    assertThat(store.historyCount()).isEqualTo(1);
  }

  @Test
  void keepsExactlyOneCurrentPositionPerShipmentAcrossManyEvents() {
    for (int minute = 0; minute < 20; minute++) {
      store.record(Positions.at("SHP-1", Duration.ofMinutes(minute)), false);
      store.record(Positions.at("SHP-2", Duration.ofMinutes(minute)), false);
    }

    assertThat(store.historyCount()).isEqualTo(40);
    assertThat(store.trackedShipments()).isEqualTo(2);
    assertThat(mongo.count(new Query(), CurrentPosition.COLLECTION)).isEqualTo(2);
  }

  private String collectionType(String name) {
    return mongo.execute(
        db -> {
          Document info = db.listCollections().filter(new Document("name", name)).first();
          return info == null ? null : info.getString("type");
        });
  }
}
