package com.company.attendance.integration;

import com.company.attendance.dto.request.attendance.AttendanceOverrideRequest;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeShift;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.PolicyAssignment;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.User;
import com.company.attendance.enums.AttendanceSource;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.PolicyType;
import com.company.attendance.enums.PunchEventType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendancePunchEventRepository;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.AttendancePolicyRepository;
import com.company.attendance.repository.AuditLogRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.PolicyAssignmentRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.AttendanceOverrideService;
import com.company.attendance.service.AuditQueryService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The attendance override removes the one check that normally stands between
 * somebody and a change to their own pay: a second person agreeing to it.
 *
 * <p>So what these tests hold in place is not the editing — that is the easy
 * part — but everything that has to remain true once the approval is gone. The
 * derived figures must be rebuilt rather than left stale, the device's own
 * punch events must survive, the audit trail must carry the before and after
 * and the stated reason, and a day payroll has already been run against must be
 * refused outright.
 *
 * <p>A silently wrong timesheet is the failure worth preventing here. It is
 * also the one nobody notices until a payslip is queried, months later.
 */
@SpringBootTest
@ActiveProfiles("test")
class AttendanceOverrideIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @Autowired AttendanceOverrideService overrideService;
    @Autowired AuditQueryService auditQueryService;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired AttendancePunchEventRepository punchEventRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired EmployeeShiftRepository employeeShiftRepository;
    @Autowired UserRepository userRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired AttendancePolicyRepository attendancePolicyRepository;
    @Autowired PolicyAssignmentRepository policyAssignmentRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Office office;
    private Shift shift;
    private Employee employee;
    private UserPrincipal superAdmin;

    /** A working day in the past, so nothing here depends on today's weekday. */
    private LocalDate workday;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        office = new Office();
        office.setCode("HO");
        office.setName("Head Office");
        office.setTimezone("Asia/Kolkata");
        office = officeRepository.save(office);

        shift = new Shift();
        shift.setCode("GEN");
        shift.setName("General Shift");
        shift.setStartTime(LocalTime.of(9, 30));
        shift.setEndTime(LocalTime.of(18, 30));
        shift = shiftRepository.save(shift);

        AttendancePolicy policy = new AttendancePolicy();
        policy.setCode("TEST");
        policy.setName("Test Attendance Policy");
        policy = attendancePolicyRepository.save(policy);

        PolicyAssignment assignment = new PolicyAssignment();
        assignment.setPolicyType(PolicyType.ATTENDANCE);
        assignment.setPolicyId(policy.getId());
        assignment.setScopeType("GLOBAL");
        policyAssignmentRepository.save(assignment);

        employee = employee("EMP5001", "Imran", "Sheikh");

        superAdmin = principal(employee("EMP5000", "Root", "Admin"), "ROOT",
                Set.of("ATTENDANCE_OVERRIDE"));

        workday = lastWeekday();

        // AuditLogService resolves the actor from the security context rather
        // than from a parameter, so a direct service call has to populate it
        // the way an authenticated request would. Without this the audit row
        // records the change but not who made it — which is the one field
        // this whole feature turns on.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        superAdmin, null, superAdmin.getAuthorities()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------ the basics

    @Test
    @DisplayName("a day nobody punched can be created from nothing")
    void createsADayThatNeverExisted() {
        assertThat(attendanceRepository.findByEmployeeIdAndAttendanceDate(employee.getId(), workday))
                .isEmpty();

        Attendance saved = overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(at(9, 30), at(18, 30), false,
                        "Biometric device was offline all day"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getPunchInTime()).isEqualTo(at(9, 30));
        assertThat(saved.getPunchOutTime()).isEqualTo(at(18, 30));
        // This is the case the regularization flow cannot reach at all: with no
        // attendance row there is nothing for the employee to raise a request
        // against.
        assertThat(saved.getSource()).isEqualTo(AttendanceSource.REGULARIZATION);
    }

    @Test
    @DisplayName("every derived figure is rebuilt, not just the two timestamps")
    void recomputesTheWholeDay() {
        // Recorded as arriving an hour late and leaving two hours early.
        overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(at(10, 30), at(16, 30), false, "Device clock drift"));

        Attendance late = reload();
        assertThat(late.getLateMinutes()).isPositive();
        assertThat(late.getEarlyExitMinutes()).isPositive();
        int workedWhenLate = late.getWorkingMinutes();

        // Corrected to the full shift. Late and early-exit must fall away with
        // it — a row whose summary disagrees with its own times is worse than
        // one that was never corrected.
        overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(at(9, 30), at(18, 30), false,
                        "Confirmed from the door log"));

        Attendance fixed = reload();
        assertThat(fixed.getLateMinutes()).isZero();
        assertThat(fixed.getEarlyExitMinutes()).isZero();
        assertThat(fixed.getWorkingMinutes()).isGreaterThan(workedWhenLate);
        assertThat(fixed.getAttendanceStatus()).isNotEqualTo(AttendanceStatus.MISSING_PUNCH_OUT);
    }

    @Test
    @DisplayName("clearing the punch-out reopens the day rather than leaving stale totals")
    void clearingPunchOutReopensTheDay() {
        overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(at(9, 30), at(18, 30), false, "Initial"));
        assertThat(reload().getWorkingMinutes()).isPositive();

        overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(null, null, true,
                        "They never left; the punch-out was somebody else's badge"));

        Attendance reopened = reload();
        assertThat(reopened.getPunchOutTime()).isNull();
        assertThat(reopened.getAttendanceStatus()).isEqualTo(AttendanceStatus.MISSING_PUNCH_OUT);
        // The figures that depended on a closing time are cleared with it.
        assertThat(reopened.getWorkingMinutes()).isZero();
        assertThat(reopened.getEarlyExitMinutes()).isZero();
    }

    // -------------------------------------------------- what must survive it

    @Test
    @DisplayName("the audit trail carries the before, the after and the reason")
    void everyOverrideIsAudited() {
        overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(at(9, 30), at(18, 30), false, "Initial load"));

        overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(at(10, 0), null, false,
                        "Door log shows 10:00, not 09:30"));

        var entries = auditLogRepository.findAll().stream()
                .filter(entry -> entry.getAction() == AuditAction.ATTENDANCE_OVERRIDE)
                .toList();

        assertThat(entries).hasSize(2);

        var second = entries.get(entries.size() - 1);
        assertThat(second.getDescription())
                .contains("EMP5001")
                .contains("Door log shows 10:00");
        // Both sides, so "what did it say before" is answerable without a backup.
        assertThat(second.getOldValue()).contains("punchInTime");
        assertThat(second.getNewValue()).isNotBlank();
        assertThat(second.getOldValue()).isNotEqualTo(second.getNewValue());
        assertThat(second.getActorUserId()).isEqualTo(superAdmin.getUserId());
    }

    @Test
    @DisplayName("the device's own punch events are added to, never replaced")
    void originalPunchEventsSurvive() {
        overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(at(9, 30), at(18, 30), false, "First"));
        long afterFirst = punchEventRepository.count();
        assertThat(afterFirst).isEqualTo(2);

        overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(at(10, 0), at(17, 0), false, "Second"));

        // Four, not two: the earlier pair is still there. The summary is
        // derived from the events, so overwriting them would destroy the only
        // account of what the day originally looked like.
        assertThat(punchEventRepository.count()).isEqualTo(4);
        assertThat(punchEventRepository.findAll())
                .allSatisfy(event -> assertThat(event.getSource())
                        .isEqualTo(AttendanceSource.REGULARIZATION))
                .extracting(event -> event.getEventType())
                .containsExactlyInAnyOrder(PunchEventType.PUNCH_IN, PunchEventType.PUNCH_OUT,
                        PunchEventType.PUNCH_IN, PunchEventType.PUNCH_OUT);
    }

    @Test
    @DisplayName("the corrected day is indistinguishable from an approved regularization")
    void presentsAsAnOrdinaryRegularization() {
        overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(at(9, 30), at(18, 30), false,
                        "Turnstile outage on the 14th"));

        Attendance saved = reload();

        // By decision of the system owner, who is the sole holder of
        // ATTENDANCE_OVERRIDE: nothing on the record marks this as an
        // administrative edit rather than a requested and approved one.
        assertThat(saved.getSource()).isEqualTo(AttendanceSource.REGULARIZATION);
        assertThat(saved.isRegularized()).isTrue();
        assertThat(saved.getRemarks())
                .isEqualTo("Regularized")
                .doesNotContain("administrator")
                .doesNotContain("Turnstile");

        // The reason is not lost — it lives in the audit trail, which only
        // SUPER_ADMIN can read. See everyOverrideIsAudited.
    }

    @Test
    @DisplayName("an administrator reading the audit log does not see the override entries")
    void overrideEntriesAreHiddenFromAuditReadersWithoutThePermission() {
        overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(at(9, 30), at(18, 30), false, "Device outage"));

        // An ADMIN-shaped caller: full audit access, no override permission.
        UserPrincipal admin = UserPrincipal.fromClaims(999L, null, "ADMIN1",
                Set.of("ADMIN"), Set.of("AUDIT_VIEW"));

        var adminView = auditQueryService.search(admin, null, null, null, null, null, null, null,
                PageRequest.of(0, 50));

        assertThat(adminView.content())
                .as("the override must not appear for a caller without the permission")
                .noneSatisfy(entry -> assertThat(entry.action()).isEqualTo("ATTENDANCE_OVERRIDE"));

        // Asking for it by name returns nothing rather than erroring, so the
        // filter cannot be probed around.
        assertThat(auditQueryService.search(admin, null, null, null, "ATTENDANCE_OVERRIDE",
                null, null, null, PageRequest.of(0, 50)).content()).isEmpty();

        // And the holder of the permission sees it in full, reason included.
        var ownerView = auditQueryService.search(superAdmin, null, null, null, null, null, null,
                null, PageRequest.of(0, 50));

        assertThat(ownerView.content())
                .anySatisfy(entry -> {
                    assertThat(entry.action()).isEqualTo("ATTENDANCE_OVERRIDE");
                    assertThat(entry.description()).contains("Device outage");
                });

        // The row itself was never conditional — only who may read it.
        assertThat(auditLogRepository.findAll())
                .anySatisfy(entry ->
                        assertThat(entry.getAction()).isEqualTo(AuditAction.ATTENDANCE_OVERRIDE));
    }

    // --------------------------------------------------------- what it refuses

    @Test
    @DisplayName("a day locked for payroll is refused, not forced")
    void lockedDaysAreRefused() {
        overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(at(9, 30), at(18, 30), false, "Initial"));

        Attendance locked = reload();
        locked.setLocked(true);
        attendanceRepository.saveAndFlush(locked);

        assertThatThrownBy(() -> overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(at(10, 0), null, false, "Too late")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("locked for payroll");

        // And the figures are untouched, not half-written.
        assertThat(reload().getPunchInTime()).isEqualTo(at(9, 30));
    }

    @Test
    @DisplayName("times that make no sense are refused")
    void nonsenseIsRefused() {
        assertThatThrownBy(() -> overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(at(18, 30), at(9, 30), false, "Backwards")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must be after");

        assertThatThrownBy(() -> overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(null, at(18, 30), false, "Out with no in")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("without a punch-in");

        assertThatThrownBy(() -> overrideService.override(superAdmin, employee.getId(),
                LocalDate.now().plusDays(1),
                new AttendanceOverrideRequest(at(9, 30), at(18, 30), false, "Tomorrow")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("has not happened yet");

        assertThatThrownBy(() -> overrideService.override(superAdmin, employee.getId(), workday,
                new AttendanceOverrideRequest(null, null, false, "Nothing to change")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least a punch-in");

        assertThat(attendanceRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a day with no shift assigned cannot be computed, and says so")
    void refusesWithoutAShift() {
        Employee shiftless = new Employee();
        shiftless.setEmployeeCode("EMP5099");
        shiftless.setFirstName("No");
        shiftless.setLastName("Shift");
        shiftless.setOfficialEmail("emp5099@company.com");
        shiftless.setJoiningDate(LocalDate.now().minusYears(1));
        shiftless.setOffice(office);
        Employee saved = employeeRepository.save(shiftless);

        assertThatThrownBy(() -> overrideService.override(superAdmin, saved.getId(), workday,
                new AttendanceOverrideRequest(at(9, 30), at(18, 30), false, "No shift")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No shift was assigned");
    }

    // ----------------------------------------------------------------- helpers

    private Attendance reload() {
        return attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employee.getId(), workday)
                .orElseThrow();
    }

    /** A wall-clock time on the test's working day, in the office's own zone. */
    private Instant at(int hour, int minute) {
        return workday.atTime(hour, minute).atZone(ZONE).toInstant();
    }

    /**
     * The most recent weekday.
     *
     * <p>Not simply yesterday: on a Monday that is a Sunday, which is a weekly
     * off, and the day's status would then be computed against a non-working
     * day. The same trap the dashboard and report tests hit.
     */
    private LocalDate lastWeekday() {
        LocalDate date = LocalDate.now().minusDays(1);
        while (date.getDayOfWeek().getValue() >= 6) {
            date = date.minusDays(1);
        }
        return date;
    }

    private Employee employee(String code, String first, String last) {
        Employee person = new Employee();
        person.setEmployeeCode(code);
        person.setFirstName(first);
        person.setLastName(last);
        person.setOfficialEmail(code.toLowerCase(Locale.ROOT) + "@company.com");
        person.setJoiningDate(LocalDate.now().minusYears(2));
        person.setOffice(office);
        person = employeeRepository.save(person);

        EmployeeShift assignment = new EmployeeShift();
        assignment.setEmployee(person);
        assignment.setShift(shift);
        assignment.setEffectiveFrom(person.getJoiningDate());
        employeeShiftRepository.save(assignment);
        return person;
    }

    private UserPrincipal principal(Employee person, String username, Set<String> permissions) {
        User account = new User();
        account.setUsername(username);
        account.setEmail(person.getOfficialEmail());
        account.setPasswordHash("{noop}irrelevant");
        account.setEmployee(person);
        account = userRepository.save(account);

        return UserPrincipal.fromClaims(account.getId(), person.getId(), username,
                Set.of("SUPER_ADMIN"), permissions);
    }
}
