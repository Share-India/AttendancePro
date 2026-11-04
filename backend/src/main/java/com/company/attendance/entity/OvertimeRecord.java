package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "overtime_records")
@Getter
@Setter
@NoArgsConstructor
public class OvertimeRecord extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attendance_id", nullable = false, unique = true)
    private Attendance attendance;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "overtime_date", nullable = false)
    private LocalDate overtimeDate;

    @Column(name = "overtime_minutes", nullable = false)
    private int overtimeMinutes;

    /** Null until a decision is made; may be less than claimed. */
    @Column(name = "approved_minutes")
    private Integer approvedMinutes;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "approver_employee_id")
    private Long approverEmployeeId;

    @Column(name = "acted_at")
    private Instant actedAt;

    @Column(name = "comments", length = 500)
    private String comments;
}
