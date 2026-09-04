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
 * <p>S14 adds the query API and the SSE stream the dashboard subscribes to on top of this same
 * service. What exists now is deliberately the write path and a single read by shipment: enough to
 * get a manifest in, prove it was validated, and read it back.
 */
@SpringBootApplication
public class ShipmentServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(ShipmentServiceApplication.class, args);
  }
}
