package com.company.attendance.unit;

import com.company.attendance.entity.Employee;
import com.company.attendance.service.PaidLeaveAccrualService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paid Leave accrual: one day a month on probation, two after, and nothing for
 * a month joined after the 10th.
 *
 * <p>The policy is four sentences of prose, and prose admits more than one
 * reading. The readings were settled against a real payroll sheet of monthly
 * rates — which reproduced thirty-nine of forty people — but the dates below
 * are synthetic. Pinning real joining dates in a public repository would put
 * staff data in it, and a test that depends on live records stops being a test
 * of the rule and becomes a test of the data.
 *
 * <p>The boundaries the sheet actually disagreed about are the ones kept here:
 * the 10th of the month, and a month in which probation ends partway through.
 */
class PaidLeaveAccrualTest {

    private final PaidLeaveAccrualService accrual = new PaidLeaveAccrualService();

    private static Employee joinedOn(String date) {
        Employee employee = new Employee();
        employee.setEmployeeCode("TEST");
        employee.setJoiningDate(LocalDate.parse(date));
        return employee;
    }

    private BigDecimal accruedInYear(String joining, int year, String asOf) {
        return accrual.accruedBy(joinedOn(joining), LocalDate.of(year, 1, 1), LocalDate.parse(asOf));
    }

    @Nested
    @DisplayName("the joining-day rule")
    class JoiningDay {

        @Test
        @DisplayName("joining on the 10th earns that month")
        void onTheTenth() {
            assertThat(accruedInYear("2026-06-10", 2026, "2026-06-30"))
                    .isEqualByComparingTo("1.00");
        }

        @Test
        @DisplayName("joining on the 11th earns nothing for that month")
        void afterTheTenth() {
            assertThat(accruedInYear("2026-06-11", 2026, "2026-06-30"))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("the month after a late join earns normally")
        void theMonthAfterALateJoin() {
            // The rule costs one month, not the first six.
            assertThat(accruedInYear("2026-06-11", 2026, "2026-07-31"))
                    .isEqualByComparingTo("1.00");
        }
    }

    @Nested
    @DisplayName("probation")
    class Probation {

        @Test
        @DisplayName("six months at one a month, then two")
        void theRateChangesAfterSixMonths() {
            // Joined 1 January: six probation months at 1, six confirmed at 2.
            assertThat(accruedInYear("2026-01-01", 2026, "2026-06-30"))
                    .isEqualByComparingTo("6.00");
            assertThat(accruedInYear("2026-01-01", 2026, "2026-12-31"))
                    .isEqualByComparingTo("18.00");
        }

        @Test
        @DisplayName("a month in which probation ends still earns the probation rate")
        void theMonthProbationEnds() {
            // Joined the 19th, so probation ends on the 19th six months later.
            // That month earns 1, not 2, and not a split of the two. This is
            // the reading the payroll sheet agreed with; deciding it by the
            // elapsed day instead would give two rates inside one month.
            assertThat(accrual.isConfirmed(joinedOn("2026-05-19"), LocalDate.of(2026, 11, 1)))
                    .isFalse();
            assertThat(accrual.isConfirmed(joinedOn("2026-05-19"), LocalDate.of(2026, 12, 1)))
                    .isTrue();
        }

        @Test
        @DisplayName("probation ending mid-October means November earns two")
        void confirmedFromTheFollowingMonth() {
            // The one case where the rule and the payroll sheet parted: six
            // months were up, the confirmation had not been processed, and the
            // rule was chosen. Pinned so that if anyone later decides probation
            // ends at confirmation rather than at six months, this fails.
            assertThat(accrual.isConfirmed(joinedOn("2026-04-16"), LocalDate.of(2026, 11, 1)))
                    .isTrue();
        }

        @Test
        @DisplayName("somebody who joined years ago is confirmed throughout")
        void aLongServingEmployee() {
            assertThat(accruedInYear("2019-01-31", 2026, "2026-12-31"))
                    .isEqualByComparingTo("24.00");
        }

        @Test
        @DisplayName("a late join also delays the rate change by a month")
        void lateJoinAndProbationTogether() {
            // Joined 20 January: January earns nothing, probation runs to
            // 20 July. February to July are probation (6 at 1), August to
            // December confirmed (5 at 2).
            assertThat(accruedInYear("2026-01-20", 2026, "2026-12-31"))
                    .isEqualByComparingTo("16.00");
        }
    }

    @Nested
    @DisplayName("accrual window")
    class Window {

        @Test
        @DisplayName("the current month counts as soon as it begins")
        void theCurrentMonthCounts() {
            // Crediting only at month end would stop somebody who joined on
            // the 1st from taking a day in their first month.
            assertThat(accruedInYear("2026-03-01", 2026, "2026-03-01"))
                    .isEqualByComparingTo("1.00");
        }

        @Test
        @DisplayName("nothing accrues before the joining month")
        void beforeJoining() {
            assertThat(accruedInYear("2026-06-01", 2026, "2026-05-31"))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a date before the leave year accrues nothing")
        void beforeTheYear() {
            assertThat(accrual.accruedBy(joinedOn("2019-01-01"),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2025, 12, 31)))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a long-serving employee accrues from the year opening, not from joining")
        void accruesFromTheYearOpening() {
            // Two days for January alone, not seven years' worth.
            assertThat(accruedInYear("2019-01-01", 2026, "2026-01-31"))
                    .isEqualByComparingTo("2.00");
        }
    }

    @Nested
    @DisplayName("the handover date")
    class Handover {

        private static final LocalDate TAKEOVER = LocalDate.of(2026, 11, 1);

        @Test
        @DisplayName("nothing accrues before the system took the ledger over")
        void nothingBeforeTheHandover() {
            // The headline case. Balances up to October were kept by hand and
            // are loaded as carried-forward days, already net of this year's
            // accrual. Accruing from January as well would hand a long-serving
            // employee twenty days they have not earned — and it would look
            // like generosity, not a bug.
            assertThat(accrual.accruedBy(joinedOn("2019-01-01"),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 30), TAKEOVER))
                    .isEqualByComparingTo("2.00");   // November only
        }

        @Test
        @DisplayName("accrual continues normally after it")
        void continuesAfterwards() {
            // November and December at two a month.
            assertThat(accrual.accruedBy(joinedOn("2019-01-01"),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), TAKEOVER))
                    .isEqualByComparingTo("4.00");
        }

        @Test
        @DisplayName("somebody who joins after the handover accrues from their joining month")
        void aLaterJoiner() {
            // The handover is a floor, not a start date: a December joiner
            // accrues from December, not from November.
            assertThat(accrual.accruedBy(joinedOn("2026-12-01"),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), TAKEOVER))
                    .isEqualByComparingTo("1.00");
        }

        @Test
        @DisplayName("probation is still counted from joining, not from the handover")
        void probationIsUnaffected() {
            // Joined August 2026, so probation runs to February 2027. November
            // and December accrue at the probation rate even though the
            // handover is later than the joining date.
            assertThat(accrual.accruedBy(joinedOn("2026-08-01"),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), TAKEOVER))
                    .isEqualByComparingTo("2.00");   // 1 + 1, not 2 + 2
        }

        @Test
        @DisplayName("without a handover date it accrues from the year opening as before")
        void absentHandover() {
            assertThat(accrual.accruedBy(joinedOn("2019-01-01"),
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 30), null))
                    .isEqualByComparingTo("22.00");
        }
    }

    @Nested
    @DisplayName("year-end carry forward")
    class CarryForward {

        private static final BigDecimal CAP = new BigDecimal("10.00");

        @Test
        @DisplayName("fifteen days carry as ten")
        void aboveTheCap() {
            assertThat(accrual.carryForward(new BigDecimal("15.00"), CAP))
                    .isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("eight days carry as eight — the cap is a ceiling, not a target")
        void belowTheCap() {
            assertThat(accrual.carryForward(new BigDecimal("8.00"), CAP))
                    .isEqualByComparingTo("8.00");
        }

        @Test
        @DisplayName("half days survive the cap")
        void halfDays() {
            // Opening balances carry .5 values, so rounding here would quietly
            // take half a day off a good number of people.
            assertThat(accrual.carryForward(new BigDecimal("9.50"), CAP))
                    .isEqualByComparingTo("9.50");
            assertThat(accrual.carryForward(new BigDecimal("17.50"), CAP))
                    .isEqualByComparingTo("10.00");
        }

        @Test
        @DisplayName("nothing carries from a zero or negative balance")
        void nothingToCarry() {
            assertThat(accrual.carryForward(BigDecimal.ZERO, CAP)).isEqualByComparingTo("0.00");
            assertThat(accrual.carryForward(new BigDecimal("-2.00"), CAP))
                    .isEqualByComparingTo("0.00");
        }
    }

    @Test
    @DisplayName("an employee with no joining date accrues nothing rather than throwing")
    void noJoiningDate() {
        assertThat(accrual.accruedBy(new Employee(),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
                .isEqualByComparingTo("0.00");
    }
}
