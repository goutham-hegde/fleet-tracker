package com.fleettracking.gateway;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the gateway itself.
 *
 * @param sendTimeout how long to wait for the broker to acknowledge a message before answering the
 *     producer with a 503. Bounded because an HTTP request thread waiting indefinitely on an
 *     unreachable broker is how a service stops answering anything at all: the threads fill up
 *     with requests that will never complete, and the readiness probe — which needs one of those
 *     threads — starts failing too, so the pod is restarted for a fault that is somewhere else
 *     entirely
 */
@ConfigurationProperties(prefix = "fleet.gateway")
public record GatewayProperties(Duration sendTimeout) {

  public GatewayProperties {
    sendTimeout = sendTimeout == null ? Duration.ofSeconds(10) : sendTimeout;
  }
}
