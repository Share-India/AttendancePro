package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import com.company.attendance.enums.RequestStatus;
import com.company.attendance.enums.WorkLocation;
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
 * A request to work away from the office.
 *
 * <p>An approved request is what lets punch-in skip the geofence for those
 * dates. That makes this the one request type the attendance path reads on
 * every single punch, which is why its days are exploded into their own table.
 */
@Entity
@Table(name = "work_from_home_requests")
@Getter
@Setter
@NoArgsConstructor
public class WorkFromHomeRequest extends BaseEntity {

    @Column(name = "request_number", nullable = false, unique = true, length = 30)
    private String requestNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    /** Working days only; weekends inside the range are not counted. */
    @Column(name = "total_days", nullable = false)
    private short totalDays;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_location", nullable = false, length = 20)
    private WorkLocation workLocation = WorkLocation.HOME;

    @Column(name = "contact_number", length = 50)
    private String contactNumber;

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
}
