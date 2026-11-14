package com.company.attendance.service;

import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.CompanySetting;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.CompanySettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * The month's shared grace allowance.
 *
 * <p>Grace used to be per day — 15 minutes every morning, with nothing
 * counting the total — so somebody could arrive a quarter of an hour late on
 * every working day of a month and never be recorded late. This is a budget
 * instead: 30 minutes for the whole month, spent by whichever late arrivals
 * come first.
 *
 * <p>Only the excess is recorded. With 5 minutes of the month left and a
 * 12-minute arrival, the day shows 7 minutes late. The remaining 5 are spent,
 * and the next late arrival gets nothing.
 *
 * <p><strong>A day is no longer independent.</strong> Whether the 20th counts
 * as late depends on what the 3rd spent. Two consequences follow, and both are
 * deliberate:
 *
 * <ul>
 *   <li>Recomputing a day excludes that day's own prior spend from the total,
 *       so settling the same day twice does not charge the pool twice.
 *   <li>Changing an earlier day — a regularisation, a corrected punch — does
 *       not retroactively re-settle the later days of that month. They keep
 *       the figures they were settled with until something touches them. The
 *       alternative is recalculating a whole month on every approval, which
 *       would quietly rewrite records people have already seen and queried.
 * </ul>
 */
@Service
public class MonthlyGraceService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyGraceService.class);

    private static final String SETTING_KEY = "attendance.monthly_grace_minutes";

    /** Used when the setting is missing or unreadable. */
    private static final int DEFAULT_MONTHLY_GRACE = 30;

    private final AttendanceRepository attendanceRepository;
    private final CompanySettingRepository companySettingRepository;

    public MonthlyGraceService(AttendanceRepository attendanceRepository,
                               CompanySettingRepository companySettingRepository) {
        this.attendanceRepository = attendanceRepository;
        this.companySettingRepository = companySettingRepository;
    }

    /**
     * How a day's raw lateness is split between the month's allowance and the
     * record.
     *
     * @param graceUsedMinutes what this day draws from the month's pool
     * @param lateMinutes      what is recorded against the day after that
     */
    public record Allowance(int graceUsedMinutes, int lateMinutes) {

        static Allowance none(int lateMinutes) {
            return new Allowance(0, Math.max(0, lateMinutes));
        }
    }

    /**
     * Applies the month's remaining allowance to one day's lateness.
     *
     * <p>Reads what the rest of the month has already spent, so settling the
     * same day repeatedly is safe: the day's own earlier spend is excluded
     * from the total rather than counted against it.
     *
     * @param employeeId whose allowance
     * @param date       the business date being settled
     * @param rawLateMinutes minutes past the shift start, before any grace
     */
    @Transactional(readOnly = true)
    public Allowance apply(Long employeeId, LocalDate date, int rawLateMinutes) {
        if (rawLateMinutes <= 0 || employeeId == null || date == null) {
            return Allowance.none(rawLateMinutes);
        }

        int monthly = monthlyGraceMinutes();
        if (monthly <= 0) {
            return Allowance.none(rawLateMinutes);
        }

        LocalDate monthStart = date.withDayOfMonth(1);
        LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);

        // Excluding this date is what makes re-settlement idempotent.
        Integer spentElsewhere = attendanceRepository
                .sumGraceUsedInMonthExcluding(employeeId, monthStart, monthEnd, date);
        int alreadySpent = spentElsewhere == null ? 0 : spentElsewhere;

        int remaining = Math.max(0, monthly - alreadySpent);
        int used = Math.min(rawLateMinutes, remaining);

        return new Allowance(used, rawLateMinutes - used);
    }

    /**
     * Applies the allowance to a day and writes both figures onto it.
     *
     * <p>The two belong together — the minutes recorded and the minutes
     * forgiven are one decision — and every settlement path needs both. A
     * caller that set only the late minutes would leave the pool unspent and
     * forgive the same lateness again next week.
     *
     * @param rawLateMinutes minutes past the shift start, before any grace
     */
    @Transactional(readOnly = true)
    public void applyTo(Attendance attendance, int rawLateMinutes) {
        Allowance allowance = apply(
                attendance.getEmployee() == null ? null : attendance.getEmployee().getId(),
                attendance.getAttendanceDate(),
                rawLateMinutes);

        attendance.setGraceUsedMinutes(allowance.graceUsedMinutes());
        attendance.setLateMinutes(allowance.lateMinutes());
    }

    /** Minutes still available to this employee for the month containing {@code date}. */
    @Transactional(readOnly = true)
    public int remainingFor(Long employeeId, LocalDate date) {
        LocalDate monthStart = date.withDayOfMonth(1);
        Integer spent = attendanceRepository.sumGraceUsedInMonth(
                employeeId, monthStart, monthStart.plusMonths(1).minusDays(1));
        return Math.max(0, monthlyGraceMinutes() - (spent == null ? 0 : spent));
    }

    /**
     * The configured allowance.
     *
     * <p>An unreadable value falls back to the default rather than throwing: a
     * typo in a settings row should not stop people punching in, and the log
     * names the row.
     */
    public int monthlyGraceMinutes() {
        return companySettingRepository.findBySettingKey(SETTING_KEY)
                .map(CompanySetting::getSettingValue)
                .map(value -> {
                    try {
                        return Integer.parseInt(value.trim());
                    } catch (NumberFormatException e) {
                        log.warn("{} is not a number: '{}'. Using {} instead.",
                                SETTING_KEY, value, DEFAULT_MONTHLY_GRACE);
                        return DEFAULT_MONTHLY_GRACE;
                    }
                })
                .orElse(DEFAULT_MONTHLY_GRACE);
    }
}
