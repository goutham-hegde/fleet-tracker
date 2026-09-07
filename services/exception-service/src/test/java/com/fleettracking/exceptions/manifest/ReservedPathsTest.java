package com.fleettracking.exceptions.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleettracking.events.EventJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The convention the manifest-reading rules depend on, checked against the real customer contracts.
 *
 * <h2>Why this test exists at all</h2>
 *
 * <p>Reading SLA terms from reserved paths in an untyped body buys something valuable — no rule
 * knows a customer's name, and a fifth customer still costs one inserted document rather than a
 * release — and pays for it by giving up compile-time enforcement. Nothing in Java stops MediVault's
 * schema being edited tomorrow to call the range {@code tempRange}, at which point the cold-chain
 * rule silently stops enforcing anything and every consignment reports as healthy.
 *
 * <p>So the enforcement moves to build time, and it has to read the <em>committed</em> schemas
 * rather than a copy typed into a test. That is the same rule the shipment service's tests follow
 * for the same reason: a test that checks a copy is a test that passes while the real contract has
 * moved underneath it.
 *
 * <h2>What it does not check</h2>
 *
 * <p>It says nothing about a customer who declares no SLA terms. QuickShip's parcels have no
 * temperature band and Southern Freight books no dock slot, and neither is a defect — a rule that
 * finds no commitment stays quiet, which is correct. What is checked is only this: a schema that
 * <em>does</em> declare one of these terms must declare it where the platform will look.
 */
class ReservedPathsTest {

  private static final Path SCHEMAS = Path.of("../../docs/schemas/manifests");

  @Test
  void theColdChainSchemaDeclaresItsBandAtTheReservedPath() {
    Map<String, Object> properties = propertiesOf("medivault.pharma-cold-chain.json");

    assertThat(properties).containsKey(SlaTerms.TEMPERATURE);

    Map<String, Object> band = nested(properties, SlaTerms.TEMPERATURE);
    // The three field names the rule reads. A schema that renamed any of them would leave the
    // cold-chain rule enforcing nothing, with nothing failing to say so.
    assertThat(band).containsKeys("minC", "maxC", "excursionToleranceMinutes");
  }

  @Test
  void theRetailSchemaDeclaresItsWindowAtTheReservedPath() {
    Map<String, Object> properties = propertiesOf("vistamart.retail-replenishment.json");

    assertThat(properties).containsKey(SlaTerms.DELIVERY_WINDOW);

    Map<String, Object> window = nested(properties, SlaTerms.DELIVERY_WINDOW);
    assertThat(window).containsKeys("opensAt", "closesAt");
  }

  @Test
  void aScheduleIsOnlyEnforceableIfTheCustomerCommittedToOne() {
    // Stated as a test rather than left implicit, because "the parcel schema has no delivery
    // window" reads like an omission and is a decision: a carrier cannot be late against a
    // deadline nobody set.
    assertThat(propertiesOf("quickship.parcel.json")).doesNotContainKey(SlaTerms.DELIVERY_WINDOW);
    assertThat(propertiesOf("southern-freight.ltl.json")).doesNotContainKey(SlaTerms.TEMPERATURE);
  }

  @Test
  void everyCommittedSchemaClosesItsBody() {
    // Not strictly about reserved paths, but it is what keeps them meaningful: without
    // additionalProperties false, a typo'd "temperatur" would be stored silently and read by
    // nobody, and the manifest would look valid while enforcing nothing.
    for (String file : schemaFiles()) {
      Map<String, Object> schema = read(file);
      assertThat(schema)
          .as("%s must set additionalProperties: false", file)
          .containsEntry("additionalProperties", false);
    }
  }

  private static String[] schemaFiles() {
    return new String[] {
      "medivault.pharma-cold-chain.json",
      "vistamart.retail-replenishment.json",
      "southern-freight.ltl.json",
      "quickship.parcel.json"
    };
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> read(String file) {
    try {
      String json = Files.readString(SCHEMAS.resolve(file));
      return EventJson.mapper().readValue(json, Map.class);
    } catch (Exception e) {
      throw new IllegalStateException(
          "could not read the committed schema " + file + " from " + SCHEMAS.toAbsolutePath(), e);
    }
  }

  private static Map<String, Object> propertiesOf(String file) {
    return nested(read(file), "properties");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> nested(Map<String, Object> parent, String key) {
    Object value = parent.get(key);
    assertThat(value).as("expected %s to be an object", key).isInstanceOf(Map.class);
    Map<String, Object> section = (Map<String, Object>) value;
    // A JSON Schema nests its own fields under "properties"; step through it when there is one.
    return section.containsKey("properties") ? (Map<String, Object>) section.get("properties") : section;
  }
}
