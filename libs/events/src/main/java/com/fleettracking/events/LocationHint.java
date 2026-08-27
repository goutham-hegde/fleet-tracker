package com.fleettracking.events;

import jakarta.validation.constraints.Size;

/**
 * A place named in words rather than in coordinates.
 *
 * <p>This exists for EDI 214, which reports status against a city and state and never against a
 * latitude and longitude. "Arrived at MEMPHIS TN" is a real and useful statement, but it cannot be
 * drawn on a map until something geocodes it. Keeping the words in a distinct type from
 * {@link GeoPoint} means the difference between "we know where this is" and "we know what the
 * carrier called it" is visible in the type system rather than in a comment.
 *
 * @param city as the source wrote it, unnormalized
 * @param stateOrProvince two-letter subdivision code where the country uses one
 * @param postalCode when supplied; EDI 214 often omits it
 * @param countryCode ISO 3166-1 alpha-2
 */
public record LocationHint(
    String city,
    @Size(max = 3) String stateOrProvince,
    String postalCode,
    @Size(min = 2, max = 2) String countryCode) {}
