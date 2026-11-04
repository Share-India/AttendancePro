package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.RegularizationReason;
import com.company.attendance.enums.RequestStatus;
import com.company.attendance.enums.WorkMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A request to correct an attendance record.
 *
 * <p>This is the only path that rewrites a day, so it carries a frozen snapshot
 * of what that day looked like before. The snapshot is taken at submission, not
 * derived later — by the time anyone asks, the attendance row has already been
 * overwritten by the approval.
 *
 * <p>Together with the append-only punch-event log, that means both halves of
 * the story survive: what the device actually reported, and what was approved
 * in its place.
 */
@Entity
@Table(name = "attendance_regularizations")
@Getter
@Setter
@NoArgsConstructor
public class AttendanceRegularization extends BaseEntity {

    @Column(name = "request_number", nullable = false, unique = true, length = 30)
    private String requestNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    /** Null when no punch was ever recorded — the commonest case. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attendance_id")
    private Attendance attendance;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_type", nullable = false, length = 30)
    private RegularizationReason reasonType;

    @Column(name = "requested_punch_in_time")
    private Instant requestedPunchInTime;

    @Column(name = "requested_punch_out_time")
    private Instant requestedPunchOutTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_work_mode", length = 20)
    private WorkMode requestedWorkMode;

    // --- Frozen snapshot of the day as it stood when this was raised ---

    @Column(name = "original_punch_in_time")
    private Instant originalPunchInTime;

    @Column(name = "original_punch_out_time")
    private Instant originalPunchOutTime;

    @Column(name = "original_working_minutes")
    private Integer originalWorkingMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "original_status", length = 30)
    private AttendanceStatus originalStatus;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Column(name = "attachment_url", length = 500)
    private String attachmentUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RequestStatus status = RequestStatus.PENDING;

    @Column(name = "current_level", nullable = false)
    private short currentLevel = 1;

    @Column(name = "current_approver_employee_id")
    private Long currentApproverEmployeeId;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    /** True when the day had no record at all, so nothing is being overwritten. */
    public boolean isCreatingMissingDay() {
        return originalPunchInTime == null && originalPunchOutTime == null;
    }
}
