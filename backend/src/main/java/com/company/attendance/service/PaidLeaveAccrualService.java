package com.company.attendance.service;

import com.company.attendance.entity.Employee;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * How much Paid Leave somebody has earned by a given date.
 *
 * <p>Share India's rules, in the order they bite:
 *
 * <ol>
 *   <li>Join on or before the 10th and that month earns leave; join after it
 *       and it does not.
 *   <li>One day a month for the six-month probation, two a month after.
 *   <li>Unused days carry month to month without limit.
 *   <li>At 31 December, up to ten days carry into the next year.
 * </ol>
 *
 * <p>This computes only (1) and (2) — what has accrued <em>within one leave
 * year</em>. Carry-forward is a separate figure held in
 * {@code leave_balances.carried_forward_days}, because an opening balance and
 * a year's accrual answer different questions and mixing them makes both
 * unexplainable.
 *
 * <p><strong>Computed, never scheduled.</strong> Nothing in this application
 * runs on a timer, so there is no monthly job to credit leave and nothing to
 * re-run when one is missed. The entitlement is a function of the joining date
 * and today, evaluated whenever a balance is read. A server that was switched
 * off for a month comes back with the right number rather than a month short.
 */
@Service
public class PaidLeaveAccrualService {

    /** Join on or before this day of the month and the month earns leave. */
    private static final int ELIGIBLE_JOINING_DAY = 10;

    /** Probation runs six months from the joining date. */
    private static final int PROBATION_MONTHS = 6;

    private static final BigDecimal PROBATION_RATE = new BigDecimal("1.00");
    private static final BigDecimal CONFIRMED_RATE = new BigDecimal("2.00");

    /**
     * Days accrued between the start of {@code leaveYear} and {@code asOf}
     * inclusive of the month {@code asOf} falls in.
     *
     * <p>The current month counts in full as soon as it begins. Crediting it
     * only at month end would mean somebody who joined on the 1st could not
     * take a day in their first month, which is not what the policy says.
     *
     * @param employee  whose leave it is; its joining date drives everything
     * @param yearStart the first day of the leave year being asked about
     * @param asOf      the date to accrue up to, normally today
     */
    public BigDecimal accruedBy(Employee employee, LocalDate yearStart, LocalDate asOf) {
        return accruedBy(employee, yearStart, asOf, null);
    }

    /**
     * The same, bounded below by the date this system took over the leave
     * ledger.
     *
     * <p>Balances kept in a spreadsheet before the handover are loaded as
     * carried-forward days, and those figures are already net of that year's
     * accrual. Accruing from the leave-year opening as well would credit the
     * same months twice — generously, and invisibly. So nothing accrues before
     * {@code accrualStart}.
     *
     * @param accrualStart the handover date, or null to accrue from the year
     *                     opening as normal
     */
    public BigDecimal accruedBy(Employee employee, LocalDate yearStart, LocalDate asOf,
                                LocalDate accrualStart) {
        LocalDate joining = employee.getJoiningDate();
        if (joining == null || asOf.isBefore(yearStart)) {
            return BigDecimal.ZERO;
        }

        LocalDate probationEnds = joining.plusMonths(PROBATION_MONTHS);

        // Start from the latest of: the year opening, the month they joined,
        // and the handover. A 2019 joiner accrues from January; a joiner
        // mid-year from the month they arrived; neither from before the
        // spreadsheet balances stop.
        LocalDate from = joining.isAfter(yearStart) ? joining : yearStart;
        if (accrualStart != null && accrualStart.isAfter(from)) {
            from = accrualStart;
        }
        YearMonth first = YearMonth.from(from);
        YearMonth last = YearMonth.from(asOf);
        if (last.isBefore(first)) {
            return BigDecimal.ZERO;
        }

        BigDecimal total = BigDecimal.ZERO;
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            total = total.add(rateFor(month, joining, probationEnds));
        }
        return total;
    }

    /**
     * What one month earns.
     *
     * <p>Probation is decided by the first of the month, not by the day the
     * six months actually elapse. A month in which probation ends on the 19th
     * still earns the probation rate — matched against HR's own November
     * figures for forty people, and it is the reading that does not give
     * somebody two rates in one month.
     */
    private BigDecimal rateFor(YearMonth month, LocalDate joining, LocalDate probationEnds) {
        // The joining month earns nothing when they arrived after the 10th.
        if (month.equals(YearMonth.from(joining)) && joining.getDayOfMonth() > ELIGIBLE_JOINING_DAY) {
            return BigDecimal.ZERO;
        }
        return month.atDay(1).isBefore(probationEnds) ? PROBATION_RATE : CONFIRMED_RATE;
    }

    /**
     * What carries into the next calendar year: the closing balance, capped.
     *
     * <p>Fifteen days carry as ten; eight carry as eight. The cap is a
     * ceiling, not a target.
     */
    public BigDecimal carryForward(BigDecimal closingBalance, BigDecimal maxCarryForward) {
        if (closingBalance == null || closingBalance.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (maxCarryForward == null) {
            return closingBalance;
        }
        return closingBalance.min(maxCarryForward);
    }

    /** True once the six months are up, by the same month-start reading as the rate. */
    public boolean isConfirmed(Employee employee, LocalDate asOf) {
        LocalDate joining = employee.getJoiningDate();
        return joining != null
                && !YearMonth.from(asOf).atDay(1).isBefore(joining.plusMonths(PROBATION_MONTHS));
    }
}
