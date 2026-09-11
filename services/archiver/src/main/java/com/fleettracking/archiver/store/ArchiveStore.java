package com.fleettracking.archiver.store;

import java.util.List;

/**
 * The three things the archive is ever asked to do. One implementation talks to S3; the seam exists
 * so the loop and the replay can be tested without one.
 *
 * <p>There is deliberately no delete and no overwrite-if-present. Objects leave the archive in exactly
 * one way, the bucket's lifecycle rule, and the IAM policies say the same thing a second time: the
 * archiver's role may only put, the replay's may only get and list.
 */
public interface ArchiveStore {

  void put(String key, byte[] body);

  /** Every key under the prefix, in lexical order. */
  List<String> list(String prefix);

  byte[] get(String key);

  /** A human-readable name for the destination, for the startup log line. */
  String describe();
}
