package com.fleettracking.publicview.plan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlansTest {

  private final Plans plans = Plans.packaged();

  @Test
  void theCommittedCatalogueIsPackagedIntoTheFunction() {
    // Four lanes in docs/samples/lanes.json. A build that left the resource out fails here rather
    // than in a Lambda that answers every shipment with no plan.
    assertThat(plans.laneCount()).isEqualTo(4);
  }

  @Test
  void theShipmentIdNamesTheLaneAndTheStopsComeInOrder() {
    List<Plans.Stop> stops = plans.forShipment("SHP-HYD-0002");

    assertThat(stops).extracting(Plans.Stop::stopId)
        .containsExactly("hyd-genome", "knl-clinic", "blr-hosp");
    assertThat(stops.get(1).radiusMeters()).isEqualTo(120.0);
    assertThat(stops.get(0).name()).isEqualTo("Genome Valley depot");
  }

  @Test
  void aLoadOnNoKnownLaneHasNoPlanAndThatIsNotAnError() {
    assertThat(plans.forShipment("SHP-XYZ-0001")).isEmpty();
    assertThat(plans.forShipment("TRUCK-7")).isEmpty();
    assertThat(plans.forShipment(null)).isEmpty();
  }

  @Test
  void theCodeIsBetweenThePrefixAndTheNumber() {
    assertThat(Plans.codeOf("SHP-DEL-0064")).isEqualTo("DEL");
    assertThat(Plans.codeOf("SHP-0064")).isEmpty();
    assertThat(Plans.codeOf("SHP-")).isEmpty();
  }
}
