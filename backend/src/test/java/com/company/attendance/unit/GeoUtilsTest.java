package com.company.attendance.unit;

import com.company.attendance.util.GeoUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Haversine and the bounding-box prefilter.
 *
 * <p>These numbers decide whether a punch is accepted, so they are checked
 * against known real-world distances rather than only against themselves.
 */
class GeoUtilsTest {

    // Mumbai office, the seeded head-office coordinates.
    private static final double MUMBAI_LAT = 19.0759837;
    private static final double MUMBAI_LNG = 72.8776559;

    @Test
    @DisplayName("the same point is zero metres away")
    void identicalPointsAreZero() {
        assertThat(GeoUtils.distanceInMeters(MUMBAI_LAT, MUMBAI_LNG, MUMBAI_LAT, MUMBAI_LNG))
                .isZero();
    }

    @Test
    @DisplayName("one degree of latitude is about 111 km")
    void oneDegreeLatitude() {
        double distance = GeoUtils.distanceInMeters(0, 0, 1, 0);
        assertThat(distance).isCloseTo(111_195, org.assertj.core.data.Offset.offset(200d));
    }

    @Test
    @DisplayName("Mumbai to Pune is about 120 km")
    void knownCityDistance() {
        double distance = GeoUtils.distanceInMeters(MUMBAI_LAT, MUMBAI_LNG, 18.5204, 73.8567);
        assertThat(distance / 1000).isCloseTo(120, org.assertj.core.data.Offset.offset(5d));
    }

    @Test
    @DisplayName("a point just inside a 200 m radius measures under 200 m")
    void insideTypicalRadius() {
        // Roughly 100 m north.
        double distance = GeoUtils.distanceInMeters(
                MUMBAI_LAT, MUMBAI_LNG, MUMBAI_LAT + 0.0009, MUMBAI_LNG);

        assertThat(distance).isLessThan(200).isGreaterThan(50);
    }

    @Test
    @DisplayName("a point several streets away exceeds a 200 m radius")
    void outsideTypicalRadius() {
        // Roughly 400 m north.
        double distance = GeoUtils.distanceInMeters(
                MUMBAI_LAT, MUMBAI_LNG, MUMBAI_LAT + 0.0036, MUMBAI_LNG);

        assertThat(distance).isGreaterThan(200);
    }

    @Test
    @DisplayName("distance is symmetric")
    void symmetric() {
        double there = GeoUtils.distanceInMeters(MUMBAI_LAT, MUMBAI_LNG, 18.5204, 73.8567);
        double back = GeoUtils.distanceInMeters(18.5204, 73.8567, MUMBAI_LAT, MUMBAI_LNG);
        assertThat(there).isCloseTo(back, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("the bounding box always covers the radius it was built for")
    void boundingBoxCoversRadius() {
        int radius = 500;
        BigDecimal latDelta = GeoUtils.latitudeDelta(radius);
        BigDecimal lngDelta = GeoUtils.longitudeDelta(MUMBAI_LAT, radius);

        // A point exactly on the box edge must be at least the radius away,
        // otherwise the prefilter would discard candidates that are in range.
        double northEdge = GeoUtils.distanceInMeters(
                MUMBAI_LAT, MUMBAI_LNG, MUMBAI_LAT + latDelta.doubleValue(), MUMBAI_LNG);
        double eastEdge = GeoUtils.distanceInMeters(
                MUMBAI_LAT, MUMBAI_LNG, MUMBAI_LAT, MUMBAI_LNG + lngDelta.doubleValue());

        assertThat(northEdge).isGreaterThanOrEqualTo(radius * 0.99);
        assertThat(eastEdge).isGreaterThanOrEqualTo(radius * 0.99);
    }

    @Test
    @DisplayName("longitude spans widen towards the poles, where degrees are narrower")
    void longitudeDeltaWidensWithLatitude() {
        BigDecimal equator = GeoUtils.longitudeDelta(0, 1000);
        BigDecimal mumbai = GeoUtils.longitudeDelta(MUMBAI_LAT, 1000);
        BigDecimal oslo = GeoUtils.longitudeDelta(59.9, 1000);

        assertThat(mumbai).isGreaterThan(equator);
        assertThat(oslo).isGreaterThan(mumbai);
    }

    @Test
    @DisplayName("near the poles the box stays bounded instead of exploding")
    void polarLongitudeIsClamped() {
        // Without clamping, cos(89.999 degrees) approaches zero and the delta
        // would blow up, turning the prefilter into a full-table scan.
        BigDecimal polar = GeoUtils.longitudeDelta(89.999, 1000);
        assertThat(polar.doubleValue()).isLessThan(1.0);
    }

    @Test
    @DisplayName("coordinates outside the valid range are rejected")
    void validatesCoordinates() {
        assertThat(GeoUtils.isValidCoordinate(BigDecimal.valueOf(19.07), BigDecimal.valueOf(72.87))).isTrue();
        assertThat(GeoUtils.isValidCoordinate(BigDecimal.valueOf(91), BigDecimal.valueOf(72))).isFalse();
        assertThat(GeoUtils.isValidCoordinate(BigDecimal.valueOf(19), BigDecimal.valueOf(181))).isFalse();
        assertThat(GeoUtils.isValidCoordinate(null, BigDecimal.valueOf(72))).isFalse();
    }

    @Test
    @DisplayName("implied speed flags a jump no traveller could make")
    void impossibleTravelSpeed() {
        // Mumbai to Pune in one minute.
        double speed = GeoUtils.impliedSpeedKmh(MUMBAI_LAT, MUMBAI_LNG, 18.5204, 73.8567, 60);
        assertThat(speed).isGreaterThan(900);
    }

    @Test
    @DisplayName("a normal commute implies an ordinary speed")
    void ordinaryCommuteSpeed() {
        // 10 km in 30 minutes.
        double speed = GeoUtils.impliedSpeedKmh(MUMBAI_LAT, MUMBAI_LNG, MUMBAI_LAT + 0.09, MUMBAI_LNG, 1800);
        assertThat(speed).isLessThan(100);
    }

    @Test
    @DisplayName("zero elapsed time reports no speed rather than infinity")
    void zeroElapsedIsNotInfinite() {
        // A rapid double-submit must not be mistaken for teleportation.
        double speed = GeoUtils.impliedSpeedKmh(MUMBAI_LAT, MUMBAI_LNG, 18.5204, 73.8567, 0);
        assertThat(speed).isZero();
    }
}
