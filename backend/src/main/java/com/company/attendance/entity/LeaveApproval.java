package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import com.company.attendance.enums.ApprovalAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One level of the approval chain, kept as a row whether or not it was acted
 * on. A skipped level stays visible so the route a request took can always be
 * reconstructed.
 */
@Entity
@Table(name = "leave_approvals")
@Getter
@Setter
@NoArgsConstructor
public class LeaveApproval extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_request_id", nullable = false)
    private LeaveRequest leaveRequest;

    @Column(name = "level", nullable = false)
    private short level;

    @Column(name = "approver_employee_id")
    private Long approverEmployeeId;

    @Column(name = "approver_role", length = 30)
    private String approverRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private ApprovalAction action = ApprovalAction.PENDING;

    @Column(name = "comments", length = 1000)
    private String comments;

    @Column(name = "acted_at")
    private Instant actedAt;

    /** Set when someone acted in place of the assigned approver. */
    @Column(name = "delegated_from_employee_id")
    private Long delegatedFromEmployeeId;
}
