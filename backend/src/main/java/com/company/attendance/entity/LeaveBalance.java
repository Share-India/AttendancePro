package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A leave ledger for one employee, type and year.
 *
 * <p>Every movement is a separate column so the balance can always be
 * explained: how much was opened with, credited, carried forward, used,
 * reserved, encashed, lapsed and manually adjusted. A balance nobody can
 * account for is one nobody trusts.
 *
 * <p>The available figure is deliberately <em>not</em> stored — a stored
 * derived value drifts from its inputs and the drift is invisible.
 */
@Entity
@Table(
        name = "leave_balances",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_leave_balance",
                columnNames = {"employee_id", "leave_type_id", "leave_year"}))
@Getter
@Setter
@NoArgsConstructor
public class LeaveBalance extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(name = "leave_year", nullable = false)
    private short leaveYear;

    @Column(name = "opening_balance", nullable = false, precision = 6, scale = 2)
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column(name = "credited_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal creditedDays = BigDecimal.ZERO;

    @Column(name = "carried_forward_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal carriedForwardDays = BigDecimal.ZERO;

    @Column(name = "used_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal usedDays = BigDecimal.ZERO;

    /**
     * Reserved by a submitted-but-undecided request.
     *
     * <p>This column is what stops an employee with two days left from having
     * three separate two-day requests approved: each apply moves days here, so
     * the next one sees a balance that already accounts for it.
     */
    @Column(name = "pending_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal pendingDays = BigDecimal.ZERO;

    @Column(name = "encashed_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal encashedDays = BigDecimal.ZERO;

    @Column(name = "lapsed_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal lapsedDays = BigDecimal.ZERO;

    /** HR corrections, positive or negative. Always audited. */
    @Column(name = "adjusted_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal adjustedDays = BigDecimal.ZERO;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    /** Total entitlement before anything was taken. */
    public BigDecimal getEntitledDays() {
        return openingBalance
                .add(creditedDays)
                .add(carriedForwardDays)
                .add(adjustedDays);
    }

    /** What can still be requested, after used and reserved days. */
    public BigDecimal getAvailableDays() {
        return getEntitledDays()
                .subtract(usedDays)
                .subtract(pendingDays)
                .subtract(encashedDays)
                .subtract(lapsedDays);
    }

    public void reserve(BigDecimal days) {
        pendingDays = pendingDays.add(days);
    }

    /** Releases a reservation without consuming it — rejection or withdrawal. */
    public void release(BigDecimal days) {
        pendingDays = pendingDays.subtract(days).max(BigDecimal.ZERO);
    }

    /** Turns a reservation into consumption — approval. */
    public void consume(BigDecimal days) {
        pendingDays = pendingDays.subtract(days).max(BigDecimal.ZERO);
        usedDays = usedDays.add(days);
    }

    /** Gives back days already consumed — cancelling future dates. */
    public void refund(BigDecimal days) {
        usedDays = usedDays.subtract(days).max(BigDecimal.ZERO);
    }
}
