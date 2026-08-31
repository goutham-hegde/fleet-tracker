package com.fleettracking.simulator.emit;

import com.fleettracking.events.EventJson;
import com.fleettracking.events.SourceSystem;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Collects everything emitted so a test can assert over it. */
class RecordingMessageSink implements MessageSink {

  private final List<SourceMessage> messages = new ArrayList<>();
  private boolean closed;

  @Override
  public void accept(SourceMessage message) {
    messages.add(message);
  }

  @Override
  public void close() {
    closed = true;
  }

  List<SourceMessage> messages() {
    return List.copyOf(messages);
  }

  List<SourceMessage> from(SourceSystem source) {
    return messages.stream().filter(m -> m.source() == source).toList();
  }

  /** The first message's body parsed as JSON, for asserting on the wire shape. */
  JsonNode firstJson() {
    return json(0);
  }

  JsonNode json(int index) {
    return EventJson.mapper().readTree(messages.get(index).body());
  }

  boolean isClosed() {
    return closed;
  }

  int size() {
    return messages.size();
  }
}
