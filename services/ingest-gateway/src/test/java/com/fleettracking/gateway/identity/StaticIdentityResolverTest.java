package com.fleettracking.gateway.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class StaticIdentityResolverTest {

  private static final IdentityProperties.Assignment REEFER =
      new IdentityProperties.Assignment("SHP-LAX-0002", "VEH-0002", List.of("TLM-0002", "DEV-0002"));

  private final StaticIdentityResolver resolver = new StaticIdentityResolver(List.of(REEFER));

  @Test
  void resolvesTheSameLoadFromEveryIdentifierTheFeedsKnow() {
    Identity expected = new Identity("SHP-LAX-0002", "VEH-0002");

    // Telematics knows the vehicle, the reefer probe knows only its own id, the mobile app and EDI
    // know the shipment. All three routes have to arrive at the same load.
    assertThat(resolver.byVehicle("VEH-0002")).contains(expected);
    assertThat(resolver.byDevice("DEV-0002")).contains(expected);
    assertThat(resolver.byDevice("TLM-0002")).contains(expected);
    assertThat(resolver.byShipment("SHP-LAX-0002")).contains(expected);
  }

  @Test
  void returnsEmptyRatherThanGuessingForSomethingItHasNeverSeen() {
    assertThat(resolver.byVehicle("VEH-9999")).isEmpty();
    assertThat(resolver.byDevice("DEV-9999")).isEmpty();
    assertThat(resolver.byShipment("SHP-XXX-9999")).isEmpty();
    assertThat(resolver.byVehicle(null)).isEmpty();
  }

  @Test
  void refusesToStartWhenOneVehicleIsAssignedTwoLoads() {
    List<IdentityProperties.Assignment> conflicting =
        List.of(
            REEFER,
            new IdentityProperties.Assignment("SHP-LAX-0099", "VEH-0002", List.of("TLM-0099")));

    // Keeping whichever came last would attribute this truck's positions to a load chosen by
    // YAML ordering. Failing at startup makes it a deployment problem rather than a data problem.
    assertThatThrownBy(() -> new StaticIdentityResolver(conflicting))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("VEH-0002");
  }

  @Test
  void refusesToStartWhenOneDeviceIsOnTwoVehicles() {
    List<IdentityProperties.Assignment> conflicting =
        List.of(
            REEFER,
            new IdentityProperties.Assignment("SHP-CHI-0001", "VEH-0001", List.of("DEV-0002")));

    assertThatThrownBy(() -> new StaticIdentityResolver(conflicting))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DEV-0002");
  }

  @Test
  void acceptsTheSameAssignmentListedTwice() {
    // A duplicate row saying the same thing is untidy reference data, not a contradiction.
    assertThat(new StaticIdentityResolver(List.of(REEFER, REEFER)).size()).isEqualTo(1);
  }
}
