package com.fleettracking.events;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The original message from the source, kept verbatim alongside the normalized form.
 *
 * <p>Carrying the raw payload roughly doubles the size of a position event, and it is worth it.
 * Normalizers are where the bugs live — a unit conversion applied twice, a timezone assumed, an
 * EDI segment parsed by position when the carrier pads it differently. Without the original, the
 * only fix is to ask the carrier to resend, which for a batch feed means the data is simply gone.
 * With it, the fix is to correct the normalizer and replay the topic.
 *
 * @param source which feed this arrived on
 * @param contentType how {@link #body()} should be parsed — {@code application/json} for three of
 *     the feeds, {@code application/edi-x12} for EDI 214. Stated explicitly rather than inferred
 *     from {@code source} so that a feed changing format does not silently corrupt replay.
 * @param body the payload as received, byte-for-byte. Held as a {@code String} rather than a
 *     parsed JSON tree because EDI 214 is not JSON — it is {@code ~}-terminated segments of
 *     {@code *}-delimited text, and a model that could only hold JSON could not hold a quarter of
 *     the platform's input.
 */
public record RawPayload(
    @NotNull SourceSystem source, @NotBlank String contentType, @NotNull String body) {

  /** Convenience for the common case: the feed's usual content type. */
  public static RawPayload of(SourceSystem source, String body) {
    return new RawPayload(source, source.defaultContentType(), body);
  }
}
