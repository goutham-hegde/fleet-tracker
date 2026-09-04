package com.fleettracking.shipment;

import com.fleettracking.shipment.manifest.ManifestService;
import com.fleettracking.shipment.manifest.ManifestStore;
import com.fleettracking.shipment.schema.ManifestValidator;
import com.fleettracking.shipment.schema.SchemaStore;
import com.mongodb.client.MongoClient;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoOperations;

/** Wiring. Beans are constructed explicitly rather than component-scanned. */
@Configuration
public class ShipmentConfig {

  private static final Logger log = LoggerFactory.getLogger(ShipmentConfig.class);

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public ManifestStore manifestStore(MongoOperations mongo) {
    return new ManifestStore(mongo);
  }

  @Bean
  public SchemaStore schemaStore(MongoOperations mongo) {
    return new SchemaStore(mongo);
  }

  @Bean
  public ManifestValidator manifestValidator(SchemaStore schemas) {
    return new ManifestValidator(schemas);
  }

  @Bean
  public ManifestService manifestService(
      ManifestStore store, ManifestValidator validator, Clock clock) {
    return new ManifestService(store, validator, clock);
  }

  /**
   * Creates the collection, its validator and its indexes once the context is up, and says out loud
   * which database it actually reached.
   *
   * <p>That log line exists because of a real failure in S8. Spring Boot 4 renamed the MongoDB
   * connection properties, the old names bind to nothing <em>silently</em>, and the application
   * fell back to {@code mongodb://localhost/test} — where an unrelated {@code mongod} on this
   * machine accepted every query and answered correctly. Everything worked, against the wrong
   * database. A service that states its destination cannot fail that way unnoticed.
   */
  @Bean
  public ApplicationListener<ApplicationReadyEvent> startupCheck(
      ManifestStore manifests, SchemaStore schemas, MongoClient client, MongoDatabaseFactory factory) {

    return event -> {
      manifests.ensureCollection();
      schemas.ensureIndexes();

      log.info(
          "shipment-service ready: database '{}' on {}, holding {} manifest(s)",
          factory.getMongoDatabase().getName(),
          client.getClusterDescription().getServerDescriptions().stream()
              .map(server -> server.getAddress().toString())
              .toList(),
          manifests.count());
    };
  }
}
