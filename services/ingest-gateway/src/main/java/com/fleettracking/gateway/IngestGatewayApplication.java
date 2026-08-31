package com.fleettracking.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The platform's front door.
 *
 * <p>Four external feeds arrive here as HTTP requests, in four dissimilar formats. This service
 * turns each of them into one of the two canonical envelopes and publishes it to Kafka, or, when
 * that cannot be done, publishes what arrived to the dead-letter topic with a reason attached.
 * Nothing downstream ever sees a raw feed.
 *
 * <p>Why HTTP rather than having the sources write to Kafka themselves: three of the four feeds are
 * things this platform does not control — a telematics vendor's webhook, a phone app, a carrier's
 * EDI system — and none of them will be given broker credentials or a client library. An HTTP
 * endpoint is what they can actually talk to. Making the gateway the only thing that writes to the
 * source topics also means the invariant every consumer depends on, that a message on
 * {@code position.events.v1} is keyed by its shipment id and has already been validated, is
 * enforced in exactly one place.
 */
@SpringBootApplication
public class IngestGatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(IngestGatewayApplication.class, args);
  }
}
