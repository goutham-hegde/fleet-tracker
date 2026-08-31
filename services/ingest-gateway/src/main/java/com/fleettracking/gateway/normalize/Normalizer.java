package com.fleettracking.gateway.normalize;

import com.fleettracking.events.SourceSystem;

/**
 * Turns one feed's wire format into canonical events.
 *
 * <p>One implementation per feed, and they share no parsing code on purpose. The temptation with
 * four inbound formats is to write one flexible parser driven by a field-mapping table, and it is
 * the wrong instinct here: the four feeds disagree about units, about time representation, about
 * which identifier they know, about whether they carry coordinates at all, and about whether one
 * message means one event. A mapping table that could express all of that would be a programming
 * language with worse tooling than the one already in use.
 *
 * <p>Keeping them separate also keeps their failures separate. The EDI parser breaking on a padded
 * segment cannot affect telematics, and a change to how the mobile app reports speed is a change to
 * exactly one file with exactly one set of tests.
 *
 * <p>An implementation must not throw for a bad payload. Anything a producer can send — truncated,
 * empty, HTML, the wrong feed's format entirely — is a {@link NormalizationResult.Rejected}.
 */
public interface Normalizer {

  /** The feed this normalizer reads. Used to route an inbound request to the right one. */
  SourceSystem source();

  /** Reads one message. Never throws for a malformed or unattributable payload. */
  NormalizationResult normalize(InboundMessage message);
}
