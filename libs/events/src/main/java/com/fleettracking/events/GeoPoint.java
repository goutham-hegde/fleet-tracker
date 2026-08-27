package com.fleettracking.events;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/**
 * A point on the earth in WGS-84 decimal degrees — the coordinate system GPS reports in and the
 * one MongoDB and MapLibre both expect, so nothing is reprojected anywhere in this platform.
 *
 * <p>Ordering trap worth knowing: this record is {@code (latitude, longitude)} because that is how
 * people say it, but GeoJSON — and therefore MongoDB's geospatial indexes — orders coordinate
 * pairs {@code [longitude, latitude]}. Anything writing to Mongo has to swap. Keeping them named
 * rather than a bare {@code double[]} is what makes that swap a visible line of code instead of a
 * silent bug that puts a truck in the Indian Ocean.
 *
 * @param latitude degrees north, -90 to 90
 * @param longitude degrees east, -180 to 180
 */
public record GeoPoint(
    @DecimalMin("-90.0") @DecimalMax("90.0") double latitude,
    @DecimalMin("-180.0") @DecimalMax("180.0") double longitude) {}
