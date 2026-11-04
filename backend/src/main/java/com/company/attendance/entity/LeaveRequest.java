package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import com.company.attendance.enums.DayPortion;
import com.company.attendance.enums.RequestStatus;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The header of a leave request. The dates it actually covers live in
 * {@link LeaveRequestDay}, one row each.
 */
@Entity
@Table(name = "leave_requests")
@Getter
@Setter
@NoArgsConstructor
public class LeaveRequest extends BaseEntity {

    /** Human-quotable reference, such as LV-2026-000123. */
    @Column(name = "request_number", nullable = false, unique = true, length = 30)
    private String requestNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_day_portion", nullable = false, length = 20)
    private DayPortion fromDayPortion = DayPortion.FULL_DAY;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_day_portion", nullable = false, length = 20)
    private DayPortion toDayPortion = DayPortion.FULL_DAY;

    /** Deductible days only — the figure charged against the balance. */
    @Column(name = "total_days", nullable = false, precision = 5, scale = 2)
    private BigDecimal totalDays;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Column(name = "attachment_url", length = 500)
    private String attachmentUrl;

    @Column(name = "emergency_contact", length = 50)
    private String emergencyContact;

    @Column(name = "contact_during_leave", length = 50)
    private String contactDuringLeave;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RequestStatus status = RequestStatus.PENDING;

    @Column(name = "current_level", nullable = false)
    private short currentLevel = 1;

    /** Denormalised so an approver's inbox is one indexed query. */
    @Column(name = "current_approver_employee_id")
    private Long currentApproverEmployeeId;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt = Instant.now();

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    public boolean isSingleDay() {
        return fromDate.equals(toDate);
    }
}
