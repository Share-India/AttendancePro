package com.company.attendance.util;

import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Shift;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Business-date resolution. Nothing else in the application may decide what
 * "today" means for an employee.
 *
 * <p>The JVM runs in UTC (pinned in {@code main}), so a local date only ever
 * comes from an explicit zone — which is the point. Using the server's own
 * clock for a business date is the bug this class exists to prevent.
 */
public final class DateTimeUtils {

    public static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Kolkata");

    private DateTimeUtils() {
    }

    /** The employee's office timezone, falling back when no office is set. */
    public static ZoneId zoneOf(Employee employee) {
        if (employee == null || employee.getOffice() == null || employee.getOffice().getTimezone() == null) {
            return FALLBACK_ZONE;
        }
        try {
            return ZoneId.of(employee.getOffice().getTimezone());
        } catch (Exception e) {
            return FALLBACK_ZONE;
        }
    }

    public static LocalDate localDate(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toLocalDate();
    }

    public static LocalTime localTime(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toLocalTime();
    }

    /**
     * The business date a punch belongs to.
     *
     * <p>For a day shift this is simply the local date. For a night shift the
     * day belongs to the date the shift <em>started</em>: a 22:00–07:00 shift
     * punching out at 07:10 updates yesterday's row, not today's. Without this
     * a night worker would produce two half-days instead of one full one.
     *
     * <p>The split point is the shift's own start time. A punch earlier in the
     * local day than that belongs to the previous business date, provided it
     * falls within the window the shift can still be running.
     */
    public static LocalDate resolveBusinessDate(Instant instant, ZoneId zone, Shift shift) {
        ZonedDateTime local = instant.atZone(zone);
        LocalDate localDate = local.toLocalDate();

        if (shift == null || !shift.spansMidnight()) {
            return localDate;
        }

        LocalTime localTime = local.toLocalTime();

        // Still before the shift's start time, so this is the tail of a shift
        // that began yesterday.
        if (localTime.isBefore(shift.getStartTime())) {
            return localDate.minusDays(1);
        }
        return localDate;
    }

    /**
     * The instant a shift starts on a given business date, in the office zone.
     */
    public static Instant shiftStartInstant(LocalDate businessDate, ZoneId zone, Shift shift) {
        return LocalDateTime.of(businessDate, shift.getStartTime()).atZone(zone).toInstant();
    }

    /**
     * The instant a shift ends. A midnight-crossing shift ends on the following
     * calendar day, which is what makes early-exit maths work for night shifts.
     */
    public static Instant shiftEndInstant(LocalDate businessDate, ZoneId zone, Shift shift) {
        LocalDate endDate = shift.spansMidnight() ? businessDate.plusDays(1) : businessDate;
        return LocalDateTime.of(endDate, shift.getEndTime()).atZone(zone).toInstant();
    }

    /** Whole minutes between two instants, never negative. */
    public static int minutesBetween(Instant from, Instant to) {
        if (from == null || to == null || to.isBefore(from)) {
            return 0;
        }
        return (int) Duration.between(from, to).toMinutes();
    }

    /** Formats a minute count as "8h 25m" for messages and reports. */
    public static String formatMinutes(int minutes) {
        if (minutes <= 0) {
            return "0m";
        }
        int hours = minutes / 60;
        int remainder = minutes % 60;
        if (hours == 0) {
            return remainder + "m";
        }
        return remainder == 0 ? hours + "h" : hours + "h " + remainder + "m";
    }
}
