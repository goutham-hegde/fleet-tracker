package com.fleettracking.shipment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The business layer: what is being carried, as opposed to where it is.
 *
 * <p>Everything built before this point is about movement. The simulator produces positions, the
 * gateway normalizes them, the tracking processor turns them into arrivals and estimates. None of
 * it knows what is in the truck. This service holds that — the manifest — and it is the first
 * component whose data comes from a customer's order system rather than from a device.
 *
 * <h2>Why this one has an HTTP port</h2>
 *
 * <p>Same reason the gateway does, and for the same kind of caller. A manifest arrives from a
 * system this platform does not control, so it comes in over HTTP rather than by that system being
 * handed broker credentials. Being a web application also means the servlet container supplies the
 * non-daemon thread that keeps the JVM alive, which a Kafka listener does for the tracking
 * processor and which nothing would do here.
 *
 * <p>S14 considered adding the dashboard's query API and event stream here and decided against it.
 * They went to {@code services/dashboard-api} instead, because the dashboard's questions cut across
 * every component — where a truck is, what is wrong with it, where it is meant to go — and
 * answering them here would have made this service the place the user interface lives while it was
 * still the write path for customer manifests. A browser refresh and a customer's submission would
 * then contend for the same threads and the same deployment.
 *
 * <p>What this service reads out is therefore still just the manifest: by shipment, by customer, by
 * mode. The dashboard reads the same collection through its own narrow projection, and this service
 * is not on the path of a map refresh at all.
 */
@SpringBootApplication
public class ShipmentServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ShipmentServiceApplication.class, args);
  }
}
