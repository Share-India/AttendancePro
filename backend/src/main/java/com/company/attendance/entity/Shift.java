package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

/**
 * A working pattern with its own thresholds. Every hour rule an attendance
 * calculation needs comes from here or from the attendance policy, never from
 * a constant in code.
 */
@Entity
@Table(name = "shifts")
@Getter
@Setter
@NoArgsConstructor
public class Shift extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    /** Derived from start/end on write; stored so queries can filter on it. */
    @Column(name = "is_night_shift", nullable = false)
    private boolean nightShift = false;

    @Column(name = "grace_period_minutes", nullable = false)
    private short gracePeriodMinutes = 15;

    @Column(name = "early_exit_grace_minutes", nullable = false)
    private short earlyExitGraceMinutes = 15;

    @Column(name = "standard_work_minutes", nullable = false)
    private short standardWorkMinutes = 480;

    @Column(name = "full_day_minutes", nullable = false)
    private short fullDayMinutes = 480;

    @Column(name = "half_day_minutes", nullable = false)
    private short halfDayMinutes = 240;

    @Column(name = "min_attendance_minutes", nullable = false)
    private short minAttendanceMinutes = 120;

    @Column(name = "max_break_minutes", nullable = false)
    private short maxBreakMinutes = 60;

    @Column(name = "punch_in_window_before_minutes", nullable = false)
    private short punchInWindowBeforeMinutes = 120;

    @Column(name = "punch_out_window_after_minutes", nullable = false)
    private short punchOutWindowAfterMinutes = 240;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "office_id")
    private Office office;

    @Column(name = "description", length = 500)
    private String description;

    /** Assigned to new employees when nothing more specific applies. */
    @Column(name = "is_default", nullable = false)
    private boolean defaultShift = false;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** A shift whose end time is at or before its start time crosses midnight. */
    public boolean spansMidnight() {
        return !endTime.isAfter(startTime);
    }
}
