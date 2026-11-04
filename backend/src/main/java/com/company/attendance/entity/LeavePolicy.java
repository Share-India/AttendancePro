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

import java.math.BigDecimal;
import java.time.LocalDate;

/** Annual quota for a leave type, scoped like every other policy. */
@Entity
@Table(name = "leave_policies")
@Getter
@Setter
@NoArgsConstructor
public class LeavePolicy extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(name = "scope_type", nullable = false, length = 30)
    private String scopeType = "GLOBAL";

    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "scope_value", length = 40)
    private String scopeValue;

    @Column(name = "annual_quota", nullable = false, precision = 5, scale = 2)
    private BigDecimal annualQuota = BigDecimal.ZERO;

    /** A mid-year joiner earns a share of the year, not the whole entitlement. */
    @Column(name = "pro_rata_on_joining", nullable = false)
    private boolean proRataOnJoining = true;

    @Column(name = "priority", nullable = false)
    private short priority = 0;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public boolean isEffectiveOn(LocalDate date) {
        boolean started = effectiveFrom == null || !effectiveFrom.isAfter(date);
        boolean notEnded = effectiveTo == null || !effectiveTo.isBefore(date);
        return started && notEnded;
    }
}
