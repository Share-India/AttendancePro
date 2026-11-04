package com.company.attendance.integration;

import com.company.attendance.dto.request.leave.LeaveApplyRequest;
import com.company.attendance.dto.request.leave.LeaveCancelRequest;
import com.company.attendance.dto.request.leave.LeaveDecisionRequest;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeManager;
import com.company.attendance.entity.Holiday;
import com.company.attendance.entity.LeavePolicy;
import com.company.attendance.entity.LeaveType;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.WeeklyOff;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.RelationshipType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.CompanySettingRepository;
import com.company.attendance.repository.EmployeeManagerRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.HolidayRepository;
import com.company.attendance.repository.LeaveApprovalRepository;
import com.company.attendance.repository.LeaveBalanceRepository;
import com.company.attendance.repository.LeavePolicyRepository;
import com.company.attendance.repository.LeaveRequestDayRepository;
import com.company.attendance.repository.LeaveRequestRepository;
import com.company.attendance.repository.LeaveTypeRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.repository.WeeklyOffRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.support.DatabaseCleaner;
import com.company.attendance.service.LeaveBalanceService;
import com.company.attendance.service.LeaveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class LeaveFlowIT {

    @Autowired LeaveService leaveService;
    @Autowired LeaveBalanceService leaveBalanceService;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired LeavePolicyRepository leavePolicyRepository;
    @Autowired LeaveBalanceRepository leaveBalanceRepository;
    @Autowired LeaveRequestRepository leaveRequestRepository;
    @Autowired LeaveRequestDayRepository leaveRequestDayRepository;
    @Autowired LeaveApprovalRepository leaveApprovalRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired EmployeeManagerRepository employeeManagerRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired UserRepository userRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired HolidayRepository holidayRepository;
    @Autowired WeeklyOffRepository weeklyOffRepository;
    @Autowired CompanySettingRepository companySettingRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Employee employee;
    private Employee manager;
    private UserPrincipal applicant;
    private UserPrincipal approver;
    private LeaveType casualLeave;

    /** A Monday comfortably in the future, so notice rules always pass. */
    private LocalDate nextMonday;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        nextMonday = LocalDate.now().plusWeeks(3).with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        Office office = new Office();
        office.setCode("HO");
        office.setName("Head Office");
        office.setTimezone("Asia/Kolkata");
        office = officeRepository.save(office);

        employee = new Employee();
        employee.setEmployeeCode("EMP7001");
        employee.setFirstName("Leave");
        employee.setLastName("Applicant");
        employee.setOfficialEmail("leave.applicant@company.com");
        employee.setJoiningDate(LocalDate.now().minusYears(3));
        employee.setOffice(office);
        employee = employeeRepository.save(employee);

        manager = new Employee();
        manager.setEmployeeCode("EMP7002");
        manager.setFirstName("Line");
        manager.setLastName("Manager");
        manager.setOfficialEmail("line.manager@company.com");
        manager.setJoiningDate(LocalDate.now().minusYears(5));
        manager.setOffice(office);
        manager = employeeRepository.save(manager);

        EmployeeManager reporting = new EmployeeManager();
        reporting.setEmployee(employee);
        reporting.setManager(manager);
        reporting.setRelationshipType(RelationshipType.PRIMARY);
        reporting.setEffectiveFrom(LocalDate.now().minusYears(2));
        employeeManagerRepository.save(reporting);

        // Saturday and Sunday off, so weekend handling is exercised.
        weeklyOffRepository.save(weeklyOff(DayOfWeek.SATURDAY));
        weeklyOffRepository.save(weeklyOff(DayOfWeek.SUNDAY));

        casualLeave = new LeaveType();
        casualLeave.setCode("CL");
        casualLeave.setName("Casual Leave");
        casualLeave.setAllowHalfDay(true);
        casualLeave.setMinNoticeDays((short) 1);
        casualLeave.setCountsHolidaysWeeklyOffs(false);
        casualLeave.setHrApprovalThresholdDays(new BigDecimal("5"));
        casualLeave = leaveTypeRepository.save(casualLeave);

        LeavePolicy policy = new LeavePolicy();
        policy.setLeaveType(casualLeave);
        policy.setScopeType("GLOBAL");
        policy.setAnnualQuota(new BigDecimal("12"));
        policy.setProRataOnJoining(false);
        leavePolicyRepository.save(policy);

        applicant = UserPrincipal.fromClaims(1L, employee.getId(), "EMP7001",
                Set.of("EMPLOYEE"), Set.of("LEAVE_APPLY"));
        approver = UserPrincipal.fromClaims(2L, manager.getId(), "EMP7002",
                Set.of("MANAGER"), Set.of("LEAVE_APPLY", "LEAVE_APPROVE_L1"));
    }

    private WeeklyOff weeklyOff(DayOfWeek day) {
        WeeklyOff rule = new WeeklyOff();
        rule.setScopeType("GLOBAL");
        rule.setDayOfWeek((byte) day.getValue());
        return rule;
    }

    private LeaveApplyRequest apply(LocalDate from, LocalDate to) {
        return new LeaveApplyRequest(casualLeave.getId(), from, to,
                "FULL_DAY", "FULL_DAY", "Family matter", null, null, null);
    }

    private BigDecimal available() {
        return leaveService.getMyBalances(applicant).stream()
                .filter(balance -> balance.leaveTypeCode().equals("CL"))
                .findFirst().orElseThrow().availableDays();
    }

    // -----------------------------------------------------------------
    // Balance ledger
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a balance is provisioned from policy on first use")
    void balanceIsProvisionedFromPolicy() {
        assertThat(available()).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("applying reserves days immediately, before anyone approves")
    void applyingReservesDays() {
        leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(1)));

        // Two working days reserved: available drops although nothing is approved.
        assertThat(available()).isEqualByComparingTo("10");

        var balance = leaveBalanceRepository
                .findByEmployeeIdAndLeaveTypeIdAndLeaveYear(
                        employee.getId(), casualLeave.getId(),
                        leaveBalanceService.resolveLeaveYear(nextMonday))
                .orElseThrow();

        assertThat(balance.getPendingDays()).isEqualByComparingTo("2");
        assertThat(balance.getUsedDays()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the reservation stops a second request spending the same days")
    void reservationPreventsOverspending() {
        // 12 days available; book 10 across two separate requests.
        leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(4)));
        leaveService.apply(applicant, apply(nextMonday.plusWeeks(1), nextMonday.plusWeeks(1).plusDays(4)));

        assertThat(available()).isEqualByComparingTo("2");

        // A third five-day request now exceeds what is left, even though none
        // of the earlier ones has been approved.
        assertThatThrownBy(() -> leaveService.apply(applicant,
                apply(nextMonday.plusWeeks(2), nextMonday.plusWeeks(2).plusDays(4))))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> {
                    BusinessException e = (BusinessException) thrown;
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LEAVE_BALANCE_INSUFFICIENT);
                    assertThat(e.getDetails()).containsKeys("availableDays", "shortfallDays");
                });
    }

    @Test
    @DisplayName("approval turns the reservation into consumption")
    void approvalConsumesTheReservation() {
        var result = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(1)));
        leaveService.approve(approver, result.request().id(), new LeaveDecisionRequest("Fine"));

        var balance = leaveBalanceRepository
                .findByEmployeeIdAndLeaveTypeIdAndLeaveYear(
                        employee.getId(), casualLeave.getId(),
                        leaveBalanceService.resolveLeaveYear(nextMonday))
                .orElseThrow();

        assertThat(balance.getPendingDays()).isEqualByComparingTo("0");
        assertThat(balance.getUsedDays()).isEqualByComparingTo("2");
        assertThat(available()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("rejection releases the reservation and restores the balance")
    void rejectionReleasesTheReservation() {
        var result = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(1)));
        assertThat(available()).isEqualByComparingTo("10");

        leaveService.reject(approver, result.request().id(), new LeaveDecisionRequest("Team is short-staffed"));

        assertThat(available()).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("withdrawing releases the reservation too")
    void withdrawalReleasesTheReservation() {
        var result = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(1)));
        leaveService.withdraw(applicant, result.request().id(), new LeaveCancelRequest("Changed my mind"));

        assertThat(available()).isEqualByComparingTo("12");
    }

    // -----------------------------------------------------------------
    // Day counting
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a Friday-to-Monday leave costs two days when weekends are not charged")
    void weekendIsNotChargedWhenTypeSaysSo() {
        LocalDate friday = nextMonday.plusDays(4);
        LocalDate monday = nextMonday.plusDays(7);

        var result = leaveService.apply(applicant, apply(friday, monday));

        // Friday and Monday only; Saturday and Sunday are free.
        assertThat(result.request().totalDays()).isEqualByComparingTo("2");
        assertThat(result.request().calendarDays()).isEqualTo(4);
        assertThat(result.countExplanation()).contains("2 weekly offs");
    }

    @Test
    @DisplayName("the same range costs four days when the type does charge for weekends")
    void weekendIsChargedWhenTypeSaysSo() {
        casualLeave.setCountsHolidaysWeeklyOffs(true);
        leaveTypeRepository.save(casualLeave);

        LocalDate friday = nextMonday.plusDays(4);
        LocalDate monday = nextMonday.plusDays(7);

        var result = leaveService.apply(applicant, apply(friday, monday));

        assertThat(result.request().totalDays()).isEqualByComparingTo("4");
        assertThat(result.countExplanation()).isNull();
    }

    @Test
    @DisplayName("a holiday inside the range is not charged, and the reason is explained")
    void holidayIsNotCharged() {
        Holiday holiday = new Holiday();
        holiday.setName("Founders Day");
        holiday.setHolidayDate(nextMonday.plusDays(1));
        holiday.setHolidayYear((short) nextMonday.getYear());
        holidayRepository.save(holiday);

        var result = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(2)));

        // Three weekdays, one of which is a holiday.
        assertThat(result.request().totalDays()).isEqualByComparingTo("2");
        assertThat(result.countExplanation()).contains("1 holiday");
    }

    @Test
    @DisplayName("non-deductible days are still recorded, so the count can be explained")
    void nonDeductibleDaysAreKept() {
        LocalDate friday = nextMonday.plusDays(4);
        var result = leaveService.apply(applicant, apply(friday, friday.plusDays(3)));

        var days = result.request().days();
        assertThat(days).hasSize(4);
        assertThat(days.stream().filter(day -> !day.deductible())).hasSize(2);
    }

    @Test
    @DisplayName("a half day costs half a day")
    void halfDayCostsHalf() {
        var request = new LeaveApplyRequest(casualLeave.getId(), nextMonday, nextMonday,
                "FIRST_HALF", "FIRST_HALF", "Appointment", null, null, null);

        var result = leaveService.apply(applicant, request);

        assertThat(result.request().totalDays()).isEqualByComparingTo("0.5");
        assertThat(available()).isEqualByComparingTo("11.5");
    }

    @Test
    @DisplayName("a range entirely of weekends is refused rather than booked as zero days")
    void allNonWorkingRangeIsRefused() {
        LocalDate saturday = nextMonday.plusDays(5);

        assertThatThrownBy(() -> leaveService.apply(applicant, apply(saturday, saturday.plusDays(1))))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("nothing to apply for"));
    }

    // -----------------------------------------------------------------
    // Overlap
    // -----------------------------------------------------------------

    @Test
    @DisplayName("overlapping an existing pending request is refused")
    void overlapWithPendingIsRefused() {
        leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(2)));

        assertThatThrownBy(() -> leaveService.apply(applicant,
                apply(nextMonday.plusDays(2), nextMonday.plusDays(3))))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.OVERLAPPING_LEAVE));
    }

    @Test
    @DisplayName("dates freed by a rejection can be requested again")
    void rejectedDatesBecomeFree() {
        var result = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(2)));
        leaveService.reject(approver, result.request().id(), new LeaveDecisionRequest("No"));

        // The same dates are now available again.
        var second = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(2)));
        assertThat(second.request().id()).isNotNull();
    }

    // -----------------------------------------------------------------
    // Approval routing
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a request routes to the applicant's own manager")
    void routesToTheReportingManager() {
        var result = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(1)));

        assertThat(result.request().currentApproverEmployeeId()).isEqualTo(manager.getId());
        assertThat(result.request().status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("nobody can approve their own leave, whatever they hold")
    void nobodyApprovesTheirOwnLeave() {
        var result = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(1)));

        UserPrincipal selfApprover = UserPrincipal.fromClaims(1L, employee.getId(), "EMP7001",
                Set.of("MANAGER"), Set.of("LEAVE_APPLY", "LEAVE_APPROVE_L1", "LEAVE_APPROVE_L2"));

        assertThatThrownBy(() -> leaveService.approve(selfApprover, result.request().id(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> {
                    BusinessException e = (BusinessException) thrown;
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_AUTHORIZED_APPROVER);
                    assertThat(e.getMessage()).contains("your own leave");
                });
    }

    @Test
    @DisplayName("an unrelated manager cannot approve someone else's team's leave")
    void unrelatedManagerCannotApprove() {
        var result = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(1)));

        Employee outsider = new Employee();
        outsider.setEmployeeCode("EMP7003");
        outsider.setFirstName("Other");
        outsider.setLastName("Manager");
        outsider.setOfficialEmail("other.manager@company.com");
        outsider.setJoiningDate(LocalDate.now().minusYears(1));
        outsider = employeeRepository.save(outsider);

        UserPrincipal stranger = UserPrincipal.fromClaims(3L, outsider.getId(), "EMP7003",
                Set.of("MANAGER"), Set.of("LEAVE_APPROVE_L1"));

        assertThatThrownBy(() -> leaveService.approve(stranger, result.request().id(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_AUTHORIZED_APPROVER));
    }

    @Test
    @DisplayName("a long request needs HR as well as the manager")
    void longRequestAddsAnHrLevel() {
        // Six working days, over the five-day threshold.
        var result = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(7)));

        assertThat(result.request().approvals()).hasSize(2);
        assertThat(result.request().approvals().get(1).approverRole()).isEqualTo("HR");

        // The manager's approval forwards rather than settling it.
        var afterManager = leaveService.approve(approver, result.request().id(),
                new LeaveDecisionRequest("Approved by line manager"));

        assertThat(afterManager.status()).isEqualTo("PENDING");
        assertThat(afterManager.currentLevel()).isEqualTo(2);
    }

    @Test
    @DisplayName("a rejection must state a reason")
    void rejectionRequiresAReason() {
        var result = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(1)));

        assertThatThrownBy(() -> leaveService.reject(approver, result.request().id(),
                new LeaveDecisionRequest("   ")))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("reason"));
    }

    @Test
    @DisplayName("a decided request cannot be decided again")
    void decidedRequestsAreFinal() {
        var result = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(1)));
        leaveService.approve(approver, result.request().id(), null);

        assertThatThrownBy(() -> leaveService.approve(approver, result.request().id(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("already been approved"));
    }

    @Test
    @DisplayName("the approver's inbox shows only what is routed to them")
    void approverInboxIsScoped() {
        leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(1)));

        var inbox = leaveService.getPendingApprovals(approver, PageRequest.of(0, 20));
        assertThat(inbox.totalElements()).isEqualTo(1);

        var applicantInbox = leaveService.getPendingApprovals(applicant, PageRequest.of(0, 20));
        assertThat(applicantInbox.totalElements()).isZero();
    }

    // -----------------------------------------------------------------
    // Attendance and cancellation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("approval marks the attendance calendar for each deductible day")
    void approvalMarksAttendance() {
        var result = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(1)));
        leaveService.approve(approver, result.request().id(), null);

        var marked = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employee.getId(), nextMonday);

        assertThat(marked).isPresent();
        assertThat(marked.get().getAttendanceStatus().name()).isEqualTo("ON_LEAVE");
    }

    @Test
    @DisplayName("cancelling future leave refunds it")
    void cancellingFutureLeaveRefunds() {
        var result = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(1)));
        leaveService.approve(approver, result.request().id(), null);
        assertThat(available()).isEqualByComparingTo("10");

        leaveService.cancel(applicant, result.request().id(), new LeaveCancelRequest("Trip cancelled"));

        assertThat(available()).isEqualByComparingTo("12");
        // The leave marking is cleared so those days can be worked.
        assertThat(attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employee.getId(), nextMonday)).isEmpty();
    }

    @Test
    @DisplayName("a pending request must be withdrawn, not cancelled")
    void pendingCannotBeCancelled() {
        var result = leaveService.apply(applicant, apply(nextMonday, nextMonday.plusDays(1)));

        assertThatThrownBy(() -> leaveService.cancel(applicant, result.request().id(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("withdraw"));
    }

    @Test
    @DisplayName("notice rules are enforced")
    void noticePeriodIsEnforced() {
        // Casual leave needs a day's notice; today gives none.
        assertThatThrownBy(() -> leaveService.apply(applicant, apply(LocalDate.now(), LocalDate.now())))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("notice"));
    }

    @Test
    @DisplayName("backdated leave is refused unless the type allows it")
    void backdatedLeaveIsRefused() {
        LocalDate lastWeek = LocalDate.now().minusDays(7);

        assertThatThrownBy(() -> leaveService.apply(applicant, apply(lastWeek, lastWeek)))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("retrospectively"));
    }
}
