package com.company.attendance.service;

import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.CompanySetting;
import com.company.attendance.repository.AttendancePolicyRepository;
import com.company.attendance.repository.CompanySettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * The payroll rules introduced in V32, and the date they start from.
 *
 * <p>Two consumers, which is why this is a service rather than a few private
 * methods on the report. {@link com.company.attendance.service.impl.ReportServiceImpl}
 * needs the day weight to decide what a day on the salary sheet is owed;
 * {@link LeaveDayExpansionService} needs the same weight to decide what a
 * leave day on that date takes off the balance. If those two disagreed, a
 * Saturday's leave would be debited as one day and credited as half, and the
 * difference would come out of the employee.
 *
 * <p><strong>Dated, not switched.</strong> The salary sheet works out its own
 * opening balance by replaying every earlier month of the leave year — absence
 * is inferred, never stored — so a rule applied to that replay would rewrite
 * months that have already been paid. {@link Rules#appliesOn} is what keeps a
 * re-run of an earlier month reproducing it.
 */
@Service
public class PayrollRulesService {

    private static final Logger log = LoggerFactory.getLogger(PayrollRulesService.class);

    static final String SETTING_RULES_FROM = "payroll.rules_effective_date";
    static final String SETTING_SATURDAY_VALUE = "payroll.saturday_day_value";
    static final String SETTING_BACKDATED_GRACE = "payroll.backdated_leave_grace_days";

    /** Days into a new month that the previous one still accepts backdated leave. */
    private static final int DEFAULT_BACKDATED_GRACE_DAYS = 5;

    /** Share India's short Saturday, when the setting is missing or unreadable. */
    private static final BigDecimal DEFAULT_SATURDAY_VALUE = new BigDecimal("0.5");

    /** The policy every employee falls back to. */
    private static final String DEFAULT_POLICY_CODE = "DEFAULT";

    private final CompanySettingRepository companySettingRepository;
    private final AttendancePolicyRepository attendancePolicyRepository;

    public PayrollRulesService(CompanySettingRepository companySettingRepository,
                               AttendancePolicyRepository attendancePolicyRepository) {
        this.companySettingRepository = companySettingRepository;
        this.attendancePolicyRepository = attendancePolicyRepository;
    }

    /**
     * The rules as they stand, as one immutable value.
     *
     * <p>A snapshot rather than a set of live accessors, so a caller that asks
     * about thousands of days asks the database once. The salary sheet replays
     * a whole leave year for every person on it, and a settings lookup inside
     * that loop is thousands of queries for one download.
     *
     * @param from                first business date under these rules; null disables them
     * @param saturdayValue       what one short Saturday is owed and worth
     * @param lateMarksPerPenalty how many late marks in the month cost a day
     * @param lateMarkPenaltyDays what that many late marks costs, in days of pay
     */
    public record Rules(LocalDate from, BigDecimal saturdayValue,
                        int lateMarksPerPenalty, BigDecimal lateMarkPenaltyDays) {

        /** The rules off: what the sheet did before V32. */
        public static Rules off() {
            return new Rules(null, BigDecimal.ONE, 0, BigDecimal.ZERO);
        }

        public boolean appliesOn(LocalDate date) {
            return from != null && !date.isBefore(from);
        }

        /**
         * What one working day on this date is owed.
         *
         * <p>A Saturday is Share India's short day — 10:00 to 14:30 — so it is
         * owed half a day, pays half a day when worked, costs half a day when
         * missed, and takes half a day off the balance when covered by leave.
         *
         * <p>Keyed on the calendar day alone, deliberately. Whether the date
         * is a working day at all is the caller's question, and the two
         * callers answer it differently: the salary sheet asks this only for a
         * day it is already counting as working, while
         * {@link LeaveDayExpansionService} asks only for a day whose
         * {@link com.company.attendance.enums.DayType} is WORKING — because a
         * Saturday somebody is rostered off is not a short shift they can
         * miss, and when the sandwich rule charges one it charges a whole day.
         */
        public BigDecimal weightOf(LocalDate date) {
            return appliesOn(date) && date.getDayOfWeek() == DayOfWeek.SATURDAY
                    ? saturdayValue
                    : BigDecimal.ONE;
        }

        /**
         * The month's loss of pay for lateness.
         *
         * <p>Integer division on purpose: an odd late mark left at month end
         * costs nothing and does not carry into the next month, which is what
         * makes the reset period mean anything.
         */
        public BigDecimal penaltyFor(int lateMarks) {
            if (lateMarksPerPenalty <= 0 || lateMarks < lateMarksPerPenalty) {
                return BigDecimal.ZERO;
            }
            return lateMarkPenaltyDays
                    .multiply(BigDecimal.valueOf(lateMarks / lateMarksPerPenalty));
        }
    }

    /**
     * The earliest date somebody may apply for leave against.
     *
     * <p>The 1st of the current month, or the 1st of the previous month while
     * that one is still open. The salary sheet recomputes past months every
     * time it runs — absence is inferred, never stored — so an approved
     * backdated leave rewrites the payable figure for whatever month it lands
     * in. Without a floor, a leave backdated to April would change a sheet
     * that was paid in May.
     *
     * <p>Nothing in this system knows when payroll actually runs, so the
     * grace setting stands in for it: for that many days into a new month,
     * the month before is still reachable.
     */
    @Transactional(readOnly = true)
    public LocalDate earliestBackdatedLeaveDate(LocalDate today) {
        LocalDate monthStart = today.withDayOfMonth(1);
        int grace = settingInt(SETTING_BACKDATED_GRACE, DEFAULT_BACKDATED_GRACE_DAYS);

        return grace > 0 && today.getDayOfMonth() <= grace
                ? monthStart.minusMonths(1)
                : monthStart;
    }

    @Transactional(readOnly = true)
    public Rules current() {
        AttendancePolicy policy = attendancePolicyRepository
                .findByCode(DEFAULT_POLICY_CODE).orElse(null);

        return new Rules(
                settingDate(SETTING_RULES_FROM),
                settingDecimal(SETTING_SATURDAY_VALUE, DEFAULT_SATURDAY_VALUE),
                policy == null ? 0 : policy.getLateMarksPerPenalty(),
                policy == null || policy.getLateMarkPenaltyDays() == null
                        ? BigDecimal.ZERO
                        : policy.getLateMarkPenaltyDays());
    }

    /**
     * A date from settings, or null.
     *
     * <p>An unreadable value reads as absent rather than throwing, and absent
     * means the rules are off — so a typo in a settings row produces the
     * behaviour that preceded them, not a failed download, a refused leave
     * application, and not a half-applied rule.
     */
    private LocalDate settingDate(String key) {
        return companySettingRepository.findBySettingKey(key)
                .map(CompanySetting::getSettingValue)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> {
                    try {
                        return LocalDate.parse(value.trim());
                    } catch (DateTimeParseException e) {
                        log.warn("{} is not a date: '{}'. The rules it dates are off.",
                                key, value);
                        return null;
                    }
                })
                .orElse(null);
    }

    private int settingInt(String key, int fallback) {
        return companySettingRepository.findBySettingKey(key)
                .map(CompanySetting::getSettingValue)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> {
                    try {
                        return Integer.parseInt(value.trim());
                    } catch (NumberFormatException e) {
                        log.warn("{} is not a number: '{}'. Using {} instead.",
                                key, value, fallback);
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private BigDecimal settingDecimal(String key, BigDecimal fallback) {
        return companySettingRepository.findBySettingKey(key)
                .map(CompanySetting::getSettingValue)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> {
                    try {
                        return new BigDecimal(value.trim());
                    } catch (NumberFormatException e) {
                        log.warn("{} is not a number: '{}'. Using {} instead.",
                                key, value, fallback);
                        return fallback;
                    }
                })
                .orElse(fallback);
    }
}
