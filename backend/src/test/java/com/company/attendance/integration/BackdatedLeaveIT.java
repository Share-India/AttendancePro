package com.company.attendance.integration;

import com.company.attendance.dto.request.leave.LeaveApplyRequest;
import com.company.attendance.entity.CompanySetting;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.LeaveBalance;
import com.company.attendance.entity.LeaveType;
import com.company.attendance.entity.Office;
import com.company.attendance.enums.EmploymentType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.CompanySettingRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.LeaveBalanceRepository;
import com.company.attendance.repository.LeaveTypeRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.LeaveService;
import com.company.attendance.service.PayrollRulesService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Applying for leave you have already taken.
 *
 * <p>V32 made an absence nobody applied for a loss of pay, and the salary
 * sheet says in as many words that applying for the leave and having it
 * approved converts it back. That was not possible: Paid Leave is the only
 * everyday type that is active and it could not be backdated, so the rule
 * named a remedy nobody could reach.
 *
 * <p>Two things had to change together, and the second is the one that is
 * easy to miss. Turning on {@code allow_backdated} by itself leaves the
 * notice check in the way — the days between today and a past date are
 * negative, so any type asking for notice refuses every backdated request,
 * with a message about "-3 days notice" that nobody can act on.
 *
 * <p>How far back is a payroll question rather than a leave one, which is why
 * the floor comes from {@link PayrollRulesService}: the salary sheet
 * recomputes past months every time it runs, so a leave approved into a month
 * that has been paid would rewrite a figure somebody has banked.
 */
@SpringBootTest
@ActiveProfiles("test")
class BackdatedLeaveIT {

    @Autowired LeaveService leaveService;
    @Autowired PayrollRulesService payrollRulesService;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired LeaveBalanceRepository leaveBalanceRepository;
    @Autowired CompanySettingRepository companySettingRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Employee employee;
    private LeaveType paidLeave;
    private UserPrincipal principal;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        Office office = new Office();
        office.setCode("HO");
        office.setName("Head Office");
        office.setTimezone("Asia/Kolkata");
        office = officeRepository.save(office);

        employee = new Employee();
        employee.setEmployeeCode("EMP9201");
        employee.setFirstName("Asha");
        employee.setLastName("Rao");
        employee.setOfficialEmail("asha.backdated@company.com");
        employee.setJoiningDate(LocalDate.of(2022, 4, 1));
        employee.setOffice(office);
        employee.setEmploymentType(EmploymentType.PERMANENT);
        employee = employeeRepository.save(employee);

        // Paid Leave as production has it: one day's notice, and now
        // backdatable. The notice is the part that used to make the two
        // settings cancel each other out.
        paidLeave = new LeaveType();
        paidLeave.setCode("PL");
        paidLeave.setName("Paid Leave");
        paidLeave.setAllowHalfDay(true);
        paidLeave.setMinNoticeDays((short) 1);
        paidLeave.setAllowBackdated(true);
        paidLeave.setCountsHolidaysWeeklyOffs(false);
        paidLeave.setHrApprovalThresholdDays(new BigDecimal("5"));
        paidLeave = leaveTypeRepository.save(paidLeave);

        LeaveBalance balance = new LeaveBalance();
        balance.setEmployee(employee);
        balance.setLeaveType(paidLeave);
        balance.setLeaveYear((short) 2026);
        balance.setCarriedForwardDays(new BigDecimal("20"));
        leaveBalanceRepository.save(balance);

        principal = UserPrincipal.fromClaims(1L, employee.getId(), "EMP9201",
                Set.of("Employee"), Set.of());

        setting("payroll.backdated_leave_grace_days", "5");
    }

    /** company_settings survives {@link DatabaseCleaner}. */
    @AfterEach
    void forgetSettings() {
        companySettingRepository.findBySettingKey("payroll.backdated_leave_grace_days")
                .ifPresent(companySettingRepository::delete);
    }

    private void setting(String key, String value) {
        CompanySetting setting = companySettingRepository.findBySettingKey(key)
                .orElseGet(CompanySetting::new);
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        setting.setValueType("INT");
        setting.setCategory("PAYROLL");
        companySettingRepository.save(setting);
    }

    private void apply(LocalDate from, LocalDate to) {
        leaveService.apply(principal, new LeaveApplyRequest(
                paidLeave.getId(), from, to, null, null, "Backdated", null, null, null));
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("a past date inside the current month is accepted, notice rule and all")
    void aPastDateThisMonthIsAccepted() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        // With five days of grace, yesterday is reachable on every day of the
        // year: inside the first five days the previous month is open, and
        // after them yesterday is in this one. Asserted rather than guarded,
        // because a test that quietly skips itself on certain dates is worse
        // than no test at all.
        assertThat(payrollRulesService.earliestBackdatedLeaveDate(today))
                .isBeforeOrEqualTo(yesterday);

        // The regression this class exists for. Before the fix this threw
        // "needs 1 day(s) notice; this request gives -1".
        assertThatCode(() -> apply(yesterday, yesterday)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("notice is still required for a future date")
    void noticeStillAppliesForwards() {
        LocalDate today = LocalDate.now();

        // Skipping the notice check for backdated requests must not skip it
        // for everything: same-day leave still gives zero days' notice.
        assertThatThrownBy(() -> apply(today, today))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("notice");
    }

    @Test
    @DisplayName("a date before the floor is refused, and the message names the floor")
    void tooFarBackIsRefused() {
        LocalDate wayBack = LocalDate.now().minusMonths(6);

        assertThatThrownBy(() -> apply(wayBack, wayBack))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Leave can only be applied for back to")
                // Tells them what to do instead rather than leaving them stuck.
                .hasMessageContaining("ask HR");
    }

    @Test
    @DisplayName("a type that does not allow backdating still refuses, with its own message")
    void aForwardOnlyTypeStillRefuses() {
        LeaveType earned = new LeaveType();
        earned.setCode("EL");
        earned.setName("Earned Leave");
        earned.setMinNoticeDays((short) 7);
        earned.setAllowBackdated(false);
        earned.setHrApprovalThresholdDays(new BigDecimal("5"));
        LeaveType saved = leaveTypeRepository.save(earned);

        LocalDate yesterday = LocalDate.now().minusDays(1);

        assertThatThrownBy(() -> leaveService.apply(principal, new LeaveApplyRequest(
                saved.getId(), yesterday, yesterday, null, null, "No", null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be applied for retrospectively");
    }

    // -----------------------------------------------------------------
    // The floor itself
    // -----------------------------------------------------------------

    @Test
    @DisplayName("inside the grace window the previous month is still reachable")
    void theGraceOpensThePreviousMonth() {
        setting("payroll.backdated_leave_grace_days", "5");

        // The 3rd, with five days of grace: December's payroll has not run,
        // so November is still open.
        LocalDate third = LocalDate.of(2027, 1, 3);
        assertThat(payrollRulesService.earliestBackdatedLeaveDate(third))
                .isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    @DisplayName("past the grace window only the current month is reachable")
    void pastTheGraceOnlyThisMonth() {
        setting("payroll.backdated_leave_grace_days", "5");

        // The 6th is past it: December has been paid and must not move.
        LocalDate sixth = LocalDate.of(2027, 1, 6);
        assertThat(payrollRulesService.earliestBackdatedLeaveDate(sixth))
                .isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    @DisplayName("the boundary day itself is inside the window")
    void theGraceBoundaryIsInclusive() {
        setting("payroll.backdated_leave_grace_days", "5");

        assertThat(payrollRulesService.earliestBackdatedLeaveDate(LocalDate.of(2027, 1, 5)))
                .isEqualTo(LocalDate.of(2026, 12, 1));
    }

    @Test
    @DisplayName("a grace of zero means the current month and nothing earlier")
    void zeroGraceMeansThisMonthOnly() {
        setting("payroll.backdated_leave_grace_days", "0");

        assertThat(payrollRulesService.earliestBackdatedLeaveDate(LocalDate.of(2027, 1, 2)))
                .isEqualTo(LocalDate.of(2027, 1, 1));
    }

    @Test
    @DisplayName("an unreadable grace falls back to the default rather than failing the form")
    void anUnreadableGraceFallsBack() {
        setting("payroll.backdated_leave_grace_days", "five");

        // Five is the default, so the 3rd still reaches the previous month.
        assertThat(payrollRulesService.earliestBackdatedLeaveDate(LocalDate.of(2027, 1, 3)))
                .isEqualTo(LocalDate.of(2026, 12, 1));
    }
}
