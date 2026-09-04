package com.fleettracking.shipment.schema;

import com.fleettracking.events.EventJson;
import com.fleettracking.shipment.manifest.FreightMode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Holds a manifest body to its customer's schema.
 *
 * <p>The schema itself is looked up per call rather than cached; see {@link SchemaStore} for why.
 * This class is the part that does the checking, and it is deliberately the only place in the
 * service that knows a JSON Schema library exists.
 *
 * <h2>Draft 2020-12, chosen rather than defaulted</h2>
 *
 * <p>A JSON Schema document can name its own dialect with {@code $schema}, and one that does falls
 * under that dialect. The dialect set here is what applies to a document that does <em>not</em> —
 * and the drafts genuinely disagree, most sharply about whether {@code exclusiveMaximum} is a
 * boolean flag beside {@code maximum} (draft 4) or a number in its own right (everything since).
 * Leaving the default unstated would mean a customer's schema could be interpreted one way today
 * and another after a library upgrade, with no change to their document or ours.
 */
public class ManifestValidator {

  /**
   * The registry is built once and reused. It compiles and caches schema documents internally and
   * is thread-safe; constructing one per request would re-parse every schema on every write, which
   * is the expensive half of validation.
   */
  private final SchemaRegistry registry =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

  private final SchemaStore schemas;

  public ManifestValidator(SchemaStore schemas) {
    this.schemas = schemas;
  }

  /**
   * Checks a manifest body against the schema on file for this customer and mode.
   *
   * <p>Never throws for invalid input: a body that breaks the schema is a {@link
   * ValidationResult.Invalid}, and an absent schema is a {@link ValidationResult.NoSchema}. Both are
   * expected states of a system that accepts data from outside.
   */
  public ValidationResult validate(String customerId, FreightMode mode, Map<String, Object> body) {
    Optional<ManifestSchema> found = schemas.find(customerId, mode);
    if (found.isEmpty()) {
      return new ValidationResult.NoSchema(customerId, mode.name());
    }

    ManifestSchema onFile = found.get();

    // Both the schema and the body go through the shared mapper rather than one built here, so a
    // date or a number is read exactly as every other component in this platform reads it.
    JsonNode schemaNode = EventJson.mapper().valueToTree(onFile.schema());
    JsonNode bodyNode = EventJson.mapper().valueToTree(body);

    Schema compiled = registry.getSchema(schemaNode);
    List<Error> errors = compiled.validate(bodyNode);

    if (errors.isEmpty()) {
      return new ValidationResult.Valid(onFile.version());
    }

    return new ValidationResult.Invalid(
        onFile.version(),
        errors.stream()
            .map(
                error ->
                    new ValidationResult.Violation(
                        // The instance location is the path to the offending value inside the body
                        // -- the single most useful thing in the response, because it points the
                        // customer at the field rather than at the schema.
                        error.getInstanceLocation().toString(),
                        error.getMessage(),
                        error.getKeyword()))
            .toList());
  }
}
