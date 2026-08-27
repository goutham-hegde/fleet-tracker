package com.fleettracking.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one JSON configuration every service in this platform must use.
 *
 * <p>A shared event model is only shared if it is read and written the same way everywhere. Two
 * services with independently configured mappers agree on the field names and disagree on
 * everything else — one writes timestamps as epoch numbers and the other as strings, one rejects
 * unknown fields and the other ignores them — and the disagreement shows up as a deserialization
 * failure in production rather than as a compile error. Hence one factory, here, in the module
 * both sides already depend on.
 *
 * <h2>What is configured, and why each one matters</h2>
 *
 * <ul>
 *   <li><b>Unknown properties are ignored.</b> This is the deliberate one. Topics are versioned
 *       ({@code position.events.v1}), but within a version a producer must be able to add a field
 *       without every consumer being redeployed first — otherwise no service can ever be deployed
 *       independently, and the whole reason for separate consumers evaporates. Adding a field stays
 *       compatible; removing or retyping one is a new topic version.
 *   <li><b>Dates and durations are ISO-8601 strings, never numbers.</b> {@code
 *       "2026-08-27T14:03:11.482Z"} rather than {@code 1787839391.482}. Epoch numbers lose
 *       sub-millisecond precision to floating point, are unreadable when someone is staring at a
 *       console consumer at midnight, and are ambiguous between seconds and milliseconds — an
 *       ambiguity that reliably puts events in 1970 or in the year 57000.
 *   <li><b>Nulls are omitted.</b> Most fields on both envelopes are legitimately absent for most
 *       sources, and a position event carrying six explicit {@code null}s is mostly punctuation.
 *       Absent and null deserialize identically, so nothing is lost.
 * </ul>
 *
 * <p>Note this targets Jackson 3 ({@code tools.jackson}), which is what Spring Boot 4
 * auto-configures. Jackson 2 is still on the classpath of most projects of this age under
 * {@code com.fasterxml.jackson}, and mixing them is silent rather than loud: the annotations are
 * shared between the two versions, so a Jackson 2 mapper reading these classes compiles and runs
 * and simply ignores half the configuration.
 */
public final class EventJson {

  private static final ObjectMapper INSTANCE = newMapper();

  private EventJson() {}

  /**
   * The shared, immutable mapper. Jackson 3 mappers are immutable once built and safe to share
   * across threads, so services should use this rather than constructing their own.
   */
  public static ObjectMapper mapper() {
    return INSTANCE;
  }

  /** A fresh mapper with the same configuration, for the rare caller that needs to alter it. */
  public static ObjectMapper newMapper() {
    return JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .disable(
            DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS,
            DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        .changeDefaultPropertyInclusion(
            incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
        .build();
  }
}
