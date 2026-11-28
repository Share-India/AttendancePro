package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Every threshold the attendance engine reads. HR changes behaviour by editing
 * a row here, never by a deployment.
 */
@Entity
@Table(name = "attendance_policies")
@Getter
@Setter
@NoArgsConstructor
public class AttendancePolicy extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "full_day_minutes", nullable = false)
    private short fullDayMinutes = 480;

    /**
     * At or above this, but below a full day, the day is a SHORT_DAY: paid in
     * full and flagged, rather than halved.
     */
    @Column(name = "short_day_minutes", nullable = false)
    private short shortDayMinutes = 420;

    @Column(name = "half_day_minutes", nullable = false)
    private short halfDayMinutes = 240;

    @Column(name = "min_attendance_minutes", nullable = false)
    private short minAttendanceMinutes = 120;

    @Column(name = "grace_period_minutes", nullable = false)
    private short gracePeriodMinutes = 15;

    @Column(name = "early_exit_grace_minutes", nullable = false)
    private short earlyExitGraceMinutes = 15;

    /**
     * How many late marks in the reset period cost a day's pay.
     *
     * <p>A late mark is a day with recorded late minutes — lateness the
     * month's grace pool could no longer absorb. At the default, the second
     * late mark of a month costs {@link #lateMarkPenaltyDays}, the fourth
     * costs it again, and an odd one left over at month end costs nothing.
     *
     * <p>Zero switches the penalty off without disturbing the late marks
     * themselves, which are still counted and still shown.
     */
    @Column(name = "late_marks_per_penalty", nullable = false)
    private short lateMarksPerPenalty = 2;

    /**
     * What that many late marks costs, in days of pay.
     *
     * <p>Loss of pay, not a charge to the leave balance: the point of the
     * rule is that lateness cannot be absorbed by leave somebody has saved.
     */
    @Column(name = "late_mark_penalty_days", nullable = false, precision = 3, scale = 1)
    private BigDecimal lateMarkPenaltyDays = new BigDecimal("1.0");

    @Column(name = "late_mark_reset_period", nullable = false, length = 20)
    private String lateMarkResetPeriod = "MONTHLY";

    @Column(name = "max_break_minutes", nullable = false)
    private short maxBreakMinutes = 60;

    @Column(name = "deduct_break_from_working", nullable = false)
    private boolean deductBreakFromWorking = true;

    @Column(name = "allow_punch_on_holiday", nullable = false)
    private boolean allowPunchOnHoliday = true;

    @Column(name = "allow_punch_on_weekly_off", nullable = false)
    private boolean allowPunchOnWeeklyOff = true;

    @Column(name = "enforce_punch_window", nullable = false)
    private boolean enforcePunchWindow = true;

    @Column(name = "auto_absent_enabled", nullable = false)
    private boolean autoAbsentEnabled = true;

    @Column(name = "auto_punch_out_enabled", nullable = false)
    private boolean autoPunchOutEnabled = false;

    /**
     * Whether anyone is chased for a missing punch-in.
     *
     * <p>Off by default and left off by the migration that added it. The
     * reminder reaches every active employee at once, so turning it on is a
     * decision somebody makes, not a side effect of a deploy.
     */
    @Column(name = "punch_in_reminder_enabled", nullable = false)
    private boolean punchInReminderEnabled = false;

    /**
     * How long after a person's own shift starts the reminder goes.
     *
     * <p>Measured from their shift, not the clock: a company whose shifts
     * start at 08:30, 09:30 and 10:00 has no single time that is right for
     * all three. At the default a 09:30 shift is reminded at 11:00.
     */
    @Column(name = "punch_in_reminder_delay_minutes", nullable = false)
    private short punchInReminderDelayMinutes = 90;

    @Column(name = "regularization_window_days", nullable = false)
    private short regularizationWindowDays = 7;

    @Column(name = "max_regularizations_per_month", nullable = false)
    private short maxRegularizationsPerMonth = 3;

    /** A cap keeps work-from-home a arrangement rather than a default. */
    @Column(name = "max_wfh_days_per_month", nullable = false)
    private short maxWfhDaysPerMonth = 8;

    @Column(name = "wfh_min_notice_days", nullable = false)
    private short wfhMinNoticeDays = 0;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
