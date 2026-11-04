package com.company.attendance.service;

import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.LocationPolicy;
import com.company.attendance.enums.SuspicionFlag;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.util.GeoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Raises flags on punches that do not look right.
 *
 * <p>Browser geolocation can be spoofed — devtools sensor override, OS-level
 * mock providers, emulators. Nothing here pretends otherwise. The purpose is to
 * record enough signal that a pattern becomes visible to a human, not to claim
 * prevention the architecture cannot deliver.
 *
 * <p>Flags are advisory by default. Whether one blocks a punch is a policy
 * setting, because the right answer differs by organisation and by how much a
 * false positive costs the person standing outside the office.
 */
@Service
public class SuspiciousPunchDetector {

    private static final Logger log = LoggerFactory.getLogger(SuspiciousPunchDetector.class);

    /** Faster than a commercial aircraft; nobody travels this between punches. */
    private static final double IMPOSSIBLE_SPEED_KMH = 900;

    /** Real GPS jitters by metres between readings; identical values repeat only if fed. */
    private static final long IDENTICAL_COORDINATE_THRESHOLD = 3;

    /** Beyond this the client's clock disagrees with the server enough to note. */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final AttendanceRepository attendanceRepository;

    public SuspiciousPunchDetector(AttendanceRepository attendanceRepository) {
        this.attendanceRepository = attendanceRepository;
    }

    @Transactional(readOnly = true)
    public Set<SuspicionFlag> detect(Employee employee,
                                     BigDecimal latitude,
                                     BigDecimal longitude,
                                     BigDecimal accuracyMeters,
                                     Instant serverTime,
                                     Instant clientReportedTime,
                                     LocalDate businessDate,
                                     LocationPolicy policy) {

        Set<SuspicionFlag> flags = EnumSet.noneOf(SuspicionFlag.class);

        if (accuracyMeters != null && accuracyMeters.intValue() > policy.getMaxAccuracyMeters()) {
            flags.add(SuspicionFlag.LOW_ACCURACY);
        }

        if (clientReportedTime != null) {
            Duration skew = Duration.between(clientReportedTime, serverTime).abs();
            if (skew.compareTo(MAX_CLOCK_SKEW) > 0) {
                flags.add(SuspicionFlag.CLIENT_CLOCK_SKEW);
            }
        }

        if (latitude != null && longitude != null) {
            detectImpossibleTravel(employee, latitude, longitude, serverTime).ifPresent(flags::add);
            detectIdenticalCoordinates(employee, latitude, longitude, businessDate).ifPresent(flags::add);
        }

        if (!flags.isEmpty()) {
            log.info("Punch by {} flagged: {}", employee.getEmployeeCode(), flags);
        }
        return flags;
    }

    /**
     * Compares against the employee's most recent punch. A distance that would
     * require impossible speed means one of the two positions is not real.
     */
    private Optional<SuspicionFlag> detectImpossibleTravel(Employee employee,
                                                           BigDecimal latitude,
                                                           BigDecimal longitude,
                                                           Instant now) {
        Optional<Attendance> previous = attendanceRepository.findMostRecentPunched(employee.getId());
        if (previous.isEmpty()) {
            return Optional.empty();
        }

        Attendance last = previous.get();
        // Prefer the punch-out position when there is one; it is the more recent
        // of the two and therefore the tighter constraint.
        BigDecimal lastLat = last.getPunchOutLatitude() != null
                ? last.getPunchOutLatitude() : last.getPunchInLatitude();
        BigDecimal lastLng = last.getPunchOutLongitude() != null
                ? last.getPunchOutLongitude() : last.getPunchInLongitude();
        Instant lastTime = last.getPunchOutTime() != null ? last.getPunchOutTime() : last.getPunchInTime();

        if (lastLat == null || lastLng == null || lastTime == null || !lastTime.isBefore(now)) {
            return Optional.empty();
        }

        long elapsedSeconds = Duration.between(lastTime, now).getSeconds();
        double speedKmh = GeoUtils.impliedSpeedKmh(
                lastLat.doubleValue(), lastLng.doubleValue(),
                latitude.doubleValue(), longitude.doubleValue(),
                elapsedSeconds);

        return speedKmh > IMPOSSIBLE_SPEED_KMH
                ? Optional.of(SuspicionFlag.IMPOSSIBLE_TRAVEL)
                : Optional.empty();
    }

    /**
     * A real GPS reading varies by metres every time. Coordinates that repeat
     * to seven decimal places across days are being supplied, not measured.
     */
    private Optional<SuspicionFlag> detectIdenticalCoordinates(Employee employee,
                                                               BigDecimal latitude,
                                                               BigDecimal longitude,
                                                               LocalDate businessDate) {
        long identical = attendanceRepository.countIdenticalPunchInCoordinates(
                employee.getId(), businessDate, latitude, longitude);

        return identical >= IDENTICAL_COORDINATE_THRESHOLD
                ? Optional.of(SuspicionFlag.IDENTICAL_COORDINATES)
                : Optional.empty();
    }
}
