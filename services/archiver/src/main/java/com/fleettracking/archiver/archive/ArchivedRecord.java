package com.fleettracking.archiver.archive;

import java.time.Instant;

/**
 * One line of an archive file: a Kafka record, and where it came from.
 *
 * <p><b>{@code value} is the exact string Kafka held, stored as a JSON string rather than embedded
 * as an object.</b> Embedding would be smaller and nicer to read, and would also mean the archive
 * holds a re-rendering: a mapper round trip reorders nothing today, but "byte-identical to what was
 * published" is a property worth having unconditionally, for the same reason the envelopes keep
 * their source payload untouched in {@code raw}. The escaping costs almost nothing once gzip has
 * seen the first few lines.
 *
 * <p>{@code key} is kept because it is the {@code shipmentId} every canonical topic is keyed by, and a
 * replay that republished without it would scatter one shipment's events across partitions and
 * lose the per-shipment ordering the whole platform relies on. {@code partition} and {@code offset}
 * say where the record sat in the cluster that archived it; they are provenance, not identity,
 * because a recreated cluster starts every offset at zero again.
 */
public record ArchivedRecord(
    String topic, int partition, long offset, Instant timestamp, String key, String value) {}
