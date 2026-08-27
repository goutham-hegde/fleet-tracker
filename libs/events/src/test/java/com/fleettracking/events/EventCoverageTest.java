package com.fleettracking.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the round-trip suite against the failure mode it cannot catch itself: an event type added
 * to the model and forgotten in {@link EventFixtures}.
 *
 * <p>A parameterized test proves things about the cases it is given. It says nothing about a case
 * nobody wrote down, so "every event type round-trips" quietly degrades to "every event type
 * someone remembered round-trips" the first time the model grows.
 *
 * <p>The fix uses the same property that makes the hierarchy sealed in the first place. Because
 * {@code permits} lists every subtype in the bytecode, the complete set is available at runtime
 * through {@link Class#getPermittedSubclasses()} — so the test can ask the model what types exist
 * rather than being told.
 */
class EventCoverageTest {

  @Test
  @DisplayName("every event type in the sealed hierarchy has a fixture")
  void everyEventTypeIsCovered() {
    List<Class<?>> declared = concreteSubtypesOf(Event.class);
    List<Class<?>> covered =
        EventFixtures.all().stream().<Class<?>>map(Object::getClass).toList();

    assertThat(covered)
        .as("add the new event type to EventFixtures so the round-trip suite covers it")
        .containsExactlyInAnyOrderElementsOf(declared);
  }

  @Test
  @DisplayName("every event type is registered for JSON under a stable name")
  void everyEventTypeHasADiscriminator() {
    // A type missing from Event's @JsonSubTypes compiles and serializes fine, then fails only on
    // the read side - and only for that one type. Cheaper to catch here.
    assertThat(EventFixtures.all())
        .allSatisfy(
            event -> {
              String json = EventJson.mapper().writeValueAsString(event);
              assertThat(EventJson.mapper().readValue(json, Event.class)).isEqualTo(event);
            });
  }

  /** Walks the sealed hierarchy down to the records that actually get serialized. */
  private static List<Class<?>> concreteSubtypesOf(Class<?> root) {
    List<Class<?>> leaves = new ArrayList<>();
    Class<?>[] permitted = root.getPermittedSubclasses();
    if (permitted == null) {
      leaves.add(root);
      return leaves;
    }
    for (Class<?> child : permitted) {
      leaves.addAll(concreteSubtypesOf(child));
    }
    return leaves;
  }
}
