package com.fleettracking.exceptions.incident;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import com.fleettracking.events.ExceptionType;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Where open incidents live, and how a clear finds the raise it belongs to.
 *
 * <h2>The open set is enforced by the database, not only by the code</h2>
 *
 * <p>Every rule checks for an existing open incident before raising, so in ordinary running nothing
 * here has to defend anything. The index exists for the extraordinary case: two instances of this
 * service holding the same shipment's partitions during a rebalance, or a redelivered record
 * evaluated concurrently with a fresh one. Both would read "nothing open" and both would raise.
 *
 * <p>MongoDB cannot express "at most one open incident per shipment, type and stop" as an ordinary
 * unique index, because the constraint applies to a subset of the documents — closed incidents are
 * kept, and a shipment that broke its cold chain twice has two of them. A <b>partial</b> unique
 * index can: it indexes only the documents matching a filter, so uniqueness is enforced over the
 * open ones and says nothing about the rest.
 *
 * <p>This is the opposite decision from the one S8 reached about overlapping assignments, where the
 * conclusion was that Mongo <em>could not</em> express the constraint and it had to be checked on
 * read. The difference is what the constraint ranges over: an assignment overlap is a relationship
 * between two documents' time windows, which no index can see, while this is an equality on three
 * fields of one document, which is exactly what an index is.
 *
 * <h2>A duplicate key here is a warning, not a failure</h2>
 *
 * <p>If the index does fire, the losing write is the second attempt to raise something already
 * raised — the outcome the constraint exists to produce. Throwing would hand it to an error handler
 * configured to retry for ever, and it would retry for ever, because the condition is permanent.
 * It is logged and swallowed, and the published event is a byte-identical duplicate of one already
 * on the topic.
 */
public class IncidentStore {

  private static final Logger log = LoggerFactory.getLogger(IncidentStore.class);

  private final MongoOperations mongo;

  public IncidentStore(MongoOperations mongo) {
    this.mongo = mongo;
  }

  /**
   * Creates the indexes this collection needs.
   *
   * <p>Explicitly, at startup, for the reason the topics are created by a Job rather than by
   * auto-creation: an index that appears only where somebody remembered to add it is not a
   * constraint, it is a coincidence.
   */
  public void ensureIndexes() {
    // At most one open incident per shipment, type and stop. Partial, so the closed history is
    // unconstrained -- a lane that breaches every run accumulates as many closed incidents as it
    // earns.
    mongo
        .indexOps(Incident.class)
        .createIndex(
            new Index()
                .on("shipmentId", Sort.Direction.ASC)
                .on("type", Sort.Direction.ASC)
                .on("stopId", Sort.Direction.ASC)
                .named("one_open_per_shipment_type_stop")
                .unique()
                .partial(PartialIndexFilter.of(where("state").is(Incident.OPEN))));

    // For the dashboard question M5 will ask: what is open right now, worst first.
    mongo
        .indexOps(Incident.class)
        .createIndex(
            new Index()
                .on("state", Sort.Direction.ASC)
                .on("raisedAt", Sort.Direction.DESC)
                .named("open_by_recency"));
  }

  /**
   * The open incident of this type for this shipment and stop, if there is one.
   *
   * <p>The stop is part of the key rather than ignored, so a truck that is late to two different
   * stops has two incidents. Passing null matches only documents with no stop, which is what the
   * three rules that are not about a place want.
   */
  public Optional<Incident> open(ExceptionType type, String shipmentId, String stopId) {
    Query query =
        Query.query(
            where("shipmentId")
                .is(shipmentId)
                .and("type")
                .is(type)
                .and("stopId")
                .is(stopId)
                .and("state")
                .is(Incident.OPEN));
    return Optional.ofNullable(mongo.findOne(query, Incident.class));
  }

  /** Every open incident for a shipment, whatever the rule. */
  public List<Incident> openFor(String shipmentId) {
    return mongo.find(
        Query.query(where("shipmentId").is(shipmentId).and("state").is(Incident.OPEN)),
        Incident.class);
  }

  /**
   * Writes an incident, creating or replacing the document under its derived id.
   *
   * @return false if the partial unique index refused it, meaning something else already has this
   *     one open. The caller has already published, and that publication is a duplicate of one on
   *     the topic rather than a second incident, because the id is derived
   */
  public boolean save(Incident incident) {
    try {
      mongo.save(incident);
      return true;
    } catch (DuplicateKeyException alreadyOpen) {
      log.warn(
          "an incident of type {} is already open for shipment {} at stop {}; "
              + "this raise is a duplicate and was not stored",
          incident.type(),
          incident.shipmentId(),
          incident.stopId());
      return false;
    }
  }

  /** How many incidents are open across the whole fleet. Reported by the heartbeat. */
  public long openCount() {
    return mongo.count(Query.query(where("state").is(Incident.OPEN)), Incident.class);
  }

  /** How many have ever been raised, open or closed. */
  public long totalCount() {
    return mongo.getCollection(Incident.COLLECTION).countDocuments();
  }
}
