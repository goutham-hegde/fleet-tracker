package com.fleettracking.tracking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The first Kafka consumer in this platform.
 *
 * <p>Everything built before this point moves messages: the simulator produces them, the gateway
 * normalizes them and puts them on a topic. Nothing yet reads one back. This service does, and in
 * doing so it turns a stream that is merely carried into state that can be queried — where a
 * shipment is now, and where it has been.
 *
 * <h2>Why this has no HTTP port</h2>
 *
 * <p>The gateway is a web application because external systems call it. Nothing calls this; it
 * pulls its work from a topic. A Spring Boot application with no web server exits the moment
 * {@code main} returns unless something keeps a non-daemon thread alive — logging a clean startup
 * and a clean shutdown, with no error anywhere, which is a genuinely confusing way to fail. Here
 * the Kafka listener container supplies that thread. That is a claim about a library rather than
 * about this code, so it is verified rather than assumed: {@code TrackingProcessorIT} runs the real
 * context and the application stays up.
 */
@SpringBootApplication
public class TrackingProcessorApplication {

  public static void main(String[] args) {
    SpringApplication.run(TrackingProcessorApplication.class, args);
  }
}
