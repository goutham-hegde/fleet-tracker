package com.fleettracking.gateway.identity;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The reference data the gateway resolves identity against, held in configuration.
 *
 * <p>This stands in for a transport management system. In a real deployment, dispatch assigns a
 * load to a tractor and the platform learns about it from a feed of its own; here the assignments
 * are listed in {@code application.yaml} and match the simulator's default eight-truck fleet. The
 * shape is the same either way — a list of "this vehicle, with these devices on it, is carrying
 * this load" — so S8 changes where the list comes from and not what a normalizer does with it.
 *
 * @param assignments every vehicle currently carrying a load
 */
@ConfigurationProperties(prefix = "fleet.gateway.identity")
public record IdentityProperties(List<Assignment> assignments) {

  public IdentityProperties {
    assignments = assignments == null ? List.of() : List.copyOf(assignments);
  }

  /**
   * One tractor, the load it is pulling, and the hardware bolted to it.
   *
   * <p>Devices are a list because a truck carries more than one reporting box and they do not share
   * an id namespace: the in-cab telematics unit calls itself {@code TLM-0002} while the reefer probe
   * on the trailer behind it is {@code DEV-0002}. Both resolve to the same load, and a lookup table
   * that assumed one device per vehicle would have to guess which one it held.
   *
   * @param shipmentId the load
   * @param vehicleId the tractor
   * @param deviceIds every device that reports on behalf of this vehicle
   */
  public record Assignment(String shipmentId, String vehicleId, List<String> deviceIds) {

    public Assignment {
      deviceIds = deviceIds == null ? List.of() : List.copyOf(deviceIds);
    }
  }
}
