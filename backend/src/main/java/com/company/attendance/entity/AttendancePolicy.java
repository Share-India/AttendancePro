package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
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

    @Column(name = "half_day_minutes", nullable = false)
    private short halfDayMinutes = 240;

    @Column(name = "min_attendance_minutes", nullable = false)
    private short minAttendanceMinutes = 120;

    @Column(name = "grace_period_minutes", nullable = false)
    private short gracePeriodMinutes = 15;

    @Column(name = "early_exit_grace_minutes", nullable = false)
    private short earlyExitGraceMinutes = 15;

    @Column(name = "late_marks_for_half_day", nullable = false)
    private short lateMarksForHalfDay = 3;

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
