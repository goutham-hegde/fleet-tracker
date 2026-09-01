package com.fleettracking.gateway.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * Resolves identity by querying dispatch reference data in MongoDB, as it stood at a given instant.
 *
 * <h2>One query per message, and no cache</h2>
 *
 * <p>Considered and rejected: a snapshot of the whole assignment table held in memory and refreshed
 * on a timer, which is what this class's configuration-backed predecessor practically was. It would
 * remove a database round trip per message, and it would buy a staleness window — for up to the
 * refresh interval the gateway would attribute positions using an assignment dispatch has already
 * ended. The round trip is one indexed lookup against a database on the same cluster, and this
 * service's actual ceiling is the broker acknowledging a produce request, not this. Paying a cost
 * that is not the bottleneck to buy a class of wrong answers is a bad trade, and cache invalidation
 * is the part of it that would still be wrong in six months.
 *
 * <p>The seam is unchanged, so if profiling ever says otherwise, a caching implementation is a new
 * class and a changed bean definition — no normalizer moves.
 *
 * <h2>Contradictory data is reported, never resolved arbitrarily</h2>
 *
 * <p>Two assignments whose validity windows overlap for the same vehicle is a fault in the
 * reference data: dispatch has said a tractor is pulling two loads at once. The fixed-list
 * implementation caught the equivalent — the same id listed twice — at startup and refused to
 * start. Mongo cannot express "no two documents for this vehicle may overlap in time" as a unique
 * index, so the check moves to read time: ask for two matches, and if two come back, resolve
 * nothing and log loudly.
 *
 * <p>Resolving nothing means the message is dead-lettered rather than published. That is the right
 * side to fail on. Picking either candidate would publish a position attributed to a load that may
 * not be carrying it, and it would do so silently, at the rate the telematics feed arrives.
 */
public class MongoIdentityResolver implements IdentityResolver {

  private static final Logger log = LoggerFactory.getLogger(MongoIdentityResolver.class);

  private final MongoOperations mongo;

  public MongoIdentityResolver(MongoOperations mongo) {
    this.mongo = mongo;
  }

  @Override
  public Optional<Identity> byVehicle(String vehicleId, Instant asOf) {
    return resolve("vehicleId", vehicleId, asOf);
  }

  @Override
  public Optional<Identity> byDevice(String deviceId, Instant asOf) {
    // deviceIds is an array, and Mongo matches a scalar against an array element without any
    // special operator: {deviceIds: "DEV-0002"} means "the array contains this". Spelling it as
    // $elemMatch or $in would read as though something subtler were happening.
    return resolve("deviceIds", deviceId, asOf);
  }

  @Override
  public Optional<Identity> byShipment(String shipmentId, Instant asOf) {
    return resolve("shipmentId", shipmentId, asOf);
  }

  /**
   * The one query all three lookups are: match an identifier, and keep only the assignment whose
   * half-open validity window contains the instant.
   *
   * @param field the document field to match on, which is also the leading field of the index that
   *     makes this lookup cheap
   */
  private Optional<Identity> resolve(String field, String identifier, Instant asOf) {
    if (identifier == null || identifier.isBlank() || asOf == null) {
      return Optional.empty();
    }

    Query query = new Query(Criteria.where(field).is(identifier).andOperator(validAt(asOf)));
    // Two, not one. The second row is never used -- it exists only so that contradictory reference
    // data is detected rather than silently resolved to whichever document the storage engine
    // happened to return first.
    query.limit(2);

    List<Assignment> matches = mongo.find(query, Assignment.class);
    if (matches.isEmpty()) {
      return Optional.empty();
    }
    if (matches.size() > 1) {
      log.error(
          "contradictory reference data: {} {} has {}+ overlapping assignments at {} ({} and {}) "
              + "-- resolving nothing and dead-lettering rather than guessing",
          field,
          identifier,
          matches.size(),
          asOf,
          matches.get(0).id(),
          matches.get(1).id());
      return Optional.empty();
    }
    return Optional.of(matches.get(0).identity());
  }

  /**
   * {@code validFrom <= asOf < validTo}, with a null {@code validTo} meaning the assignment is
   * still open.
   *
   * <p>The null branch is why this is two criteria joined by {@code $or} rather than a single range
   * comparison. Storing a far-future sentinel date instead would collapse it into one comparison
   * and index a little better, at the cost of every reader having to know that the year 9999 means
   * "still running". An open interval is what the data actually means, so it is what is stored.
   */
  private static Criteria validAt(Instant asOf) {
    return new Criteria()
        .andOperator(
            Criteria.where("validFrom").lte(asOf),
            new Criteria()
                .orOperator(
                    Criteria.where("validTo").is(null), Criteria.where("validTo").gt(asOf)));
  }

  /** How many assignments the collection holds. Logged at startup, so an empty seed is visible. */
  public long size() {
    return mongo.count(new Query(), Assignment.class);
  }
}
