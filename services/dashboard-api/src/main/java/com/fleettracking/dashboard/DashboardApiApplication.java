package com.fleettracking.dashboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The fifth service, and the first one a person looks at.
 *
 * <p>Everything before this writes. The gateway normalizes four feeds into one stream, the tracking
 * processor turns that stream into position, arrivals and estimates, the shipment service holds the
 * paperwork, and the exception service judges all of it against what a customer agreed to. Between
 * them they have produced five collections and four topics that no human being has ever seen. This
 * service reads them.
 *
 * <h2>Why a fifth service rather than an endpoint on the fourth</h2>
 *
 * <p>The dashboard's questions cut across every component: where a truck is (tracking processor),
 * what it is carrying (shipment service), what is wrong with it (exception service), where it is
 * meant to go (reference data). Hanging that off any one of them would make that service the place
 * the user interface lives, and it would grow a dependency on all the others' data while keeping
 * whatever it was already responsible for. The shipment service is a write path with a schema
 * validator; making it also the fleet view would mean a browser refresh and a customer's manifest
 * submission contending for the same threads and the same deployment.
 *
 * <p>Separating them also lets them scale on different axes, which is the argument Kubernetes is
 * here for in the first place. This service holds one open connection per viewer for as long as
 * somebody is watching — a shape with nothing in common with a service that answers one request per
 * manifest.
 *
 * <h2>Read-only, and its own consumer group per instance</h2>
 *
 * <p>Nothing here writes to MongoDB or produces to Kafka. It is a projection: everything it serves
 * can be thrown away and rebuilt from what the other four services hold, which is what makes it safe
 * to read four services' collections directly.
 *
 * <p>Its Kafka group is unique per <em>instance</em>, not per service, and that is the one place
 * this consumer deliberately breaks the pattern the other three follow — see
 * {@code DashboardConsumers}.
 */
@SpringBootApplication
public class DashboardApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(DashboardApiApplication.class, args);
  }
}
