package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import com.company.attendance.enums.PolicyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Binds a policy to a scope. Resolution is most-specific-wins:
 * EMPLOYEE, then DEPARTMENT, EMPLOYMENT_TYPE, OFFICE, and finally GLOBAL.
 */
@Entity
@Table(name = "policy_assignments")
@Getter
@Setter
@NoArgsConstructor
public class PolicyAssignment extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_type", nullable = false, length = 30)
    private PolicyType policyType;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(name = "scope_type", nullable = false, length = 30)
    private String scopeType;

    @Column(name = "scope_id")
    private Long scopeId;

    /** For scopes keyed by a value rather than an id, such as EMPLOYMENT_TYPE. */
    @Column(name = "scope_value", length = 40)
    private String scopeValue;

    @Column(name = "priority", nullable = false)
    private short priority = 0;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public boolean isEffectiveOn(LocalDate date) {
        boolean startedOk = effectiveFrom == null || !effectiveFrom.isAfter(date);
        boolean notEnded = effectiveTo == null || !effectiveTo.isBefore(date);
        return startedOk && notEnded;
    }
}
