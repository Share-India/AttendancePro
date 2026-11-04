package com.company.attendance.util;

import java.math.BigDecimal;

/**
 * Distance maths for geofencing. Runs on the server, always — the browser's
 * own distance check is a courtesy to the employee, never the decision.
 */
public final class GeoUtils {

    /** Mean Earth radius, metres. */
    private static final double EARTH_RADIUS_M = 6_371_000d;

    /** Metres per degree of latitude. Constant everywhere. */
    private static final double METERS_PER_DEGREE_LAT = 111_320d;

    private GeoUtils() {
    }

    /**
     * Great-circle distance in metres.
     *
     * <p>Accurate to well under a metre at geofence scale, which is far finer
     * than consumer GPS (5–50 m), so the formula is never the limiting factor.
     */
    public static double distanceInMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static double distanceInMeters(BigDecimal lat1, BigDecimal lon1, BigDecimal lat2, BigDecimal lon2) {
        return distanceInMeters(lat1.doubleValue(), lon1.doubleValue(), lat2.doubleValue(), lon2.doubleValue());
    }

    /**
     * Half-height of a bounding box covering the given radius, in degrees of
     * latitude. Used to narrow candidates in SQL before any trigonometry runs.
     */
    public static BigDecimal latitudeDelta(int radiusMeters) {
        return BigDecimal.valueOf(radiusMeters / METERS_PER_DEGREE_LAT);
    }

    /**
     * Half-width in degrees of longitude at a given latitude.
     *
     * <p>Degrees of longitude narrow towards the poles, so this divides by
     * cos(latitude). Near the poles that cosine approaches zero, which would
     * produce an unbounded box — clamped so the prefilter stays sane rather
     * than degenerating into a full-table scan.
     */
    public static BigDecimal longitudeDelta(double latitude, int radiusMeters) {
        double cos = Math.cos(Math.toRadians(latitude));
        double safeCos = Math.max(Math.abs(cos), 0.01);
        return BigDecimal.valueOf(radiusMeters / (METERS_PER_DEGREE_LAT * safeCos));
    }

    /** Rejects coordinates outside the valid range before any distance maths. */
    public static boolean isValidCoordinate(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null) {
            return false;
        }
        double lat = latitude.doubleValue();
        double lon = longitude.doubleValue();
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    /**
     * Implied speed in km/h between two points over an elapsed time. Feeds the
     * impossible-travel check; returns 0 when no time has passed, since an
     * infinite speed would flag every rapid double-submit as fraud.
     */
    public static double impliedSpeedKmh(double lat1, double lon1, double lat2, double lon2, long elapsedSeconds) {
        if (elapsedSeconds <= 0) {
            return 0;
        }
        double meters = distanceInMeters(lat1, lon1, lat2, lon2);
        return (meters / elapsedSeconds) * 3.6;
    }
}
