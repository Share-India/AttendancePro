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

import java.time.Instant;

/**
 * One break within a day. A row whose end time is null means the employee is on
 * break right now — that absence is the state, so there is no separate flag to
 * fall out of sync with it.
 */
@Entity
@Table(name = "attendance_breaks")
@Getter
@Setter
@NoArgsConstructor
public class AttendanceBreak extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attendance_id", nullable = false)
    private Attendance attendance;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "break_type_id", nullable = false)
    private BreakType breakType;

    @Column(name = "break_start_time", nullable = false)
    private Instant breakStartTime;

    @Column(name = "break_end_time")
    private Instant breakEndTime;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "start_event_id")
    private Long startEventId;

    @Column(name = "end_event_id")
    private Long endEventId;

    /** Flagged, not blocked: an over-long break is a conversation, not an error. */
    @Column(name = "exceeded_limit", nullable = false)
    private boolean exceededLimit = false;

    /** Closed by punch-out or a scheduled job rather than by the employee. */
    @Column(name = "auto_closed", nullable = false)
    private boolean autoClosed = false;

    public boolean isOpen() {
        return breakEndTime == null;
    }
}
