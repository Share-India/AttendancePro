package com.company.attendance.integration;

import com.company.attendance.dto.request.attendance.PunchRequest;
import com.company.attendance.dto.request.request.RegularizationApplyRequest;
import com.company.attendance.dto.request.request.RequestDecisionRequest;
import com.company.attendance.dto.request.request.WorkFromHomeApplyRequest;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeManager;
import com.company.attendance.entity.EmployeeShift;
import com.company.attendance.entity.LocationPolicy;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.OfficeLocation;
import com.company.attendance.entity.OvertimePolicy;
import com.company.attendance.entity.PolicyAssignment;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.WeeklyOff;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.PolicyType;
import com.company.attendance.enums.RelationshipType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendancePolicyRepository;
import com.company.attendance.repository.AttendancePunchEventRepository;
import com.company.attendance.repository.AttendanceRegularizationRepository;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.EmployeeManagerRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.LocationPolicyRepository;
import com.company.attendance.repository.OfficeLocationRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.OvertimePolicyRepository;
import com.company.attendance.repository.PolicyAssignmentRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.WeeklyOffRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.PunchService;
import com.company.attendance.service.RegularizationService;
import com.company.attendance.service.WorkFromHomeService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class RequestFlowIT {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final BigDecimal OFFICE_LAT = new BigDecimal("19.0759837");
    private static final BigDecimal OFFICE_LNG = new BigDecimal("72.8776559");

    @Autowired RegularizationService regularizationService;
    @Autowired WorkFromHomeService workFromHomeService;
    @Autowired PunchService punchService;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired AttendancePunchEventRepository punchEventRepository;
    @Autowired AttendanceRegularizationRepository regularizationRepository;
    @Autowired AttendancePolicyRepository attendancePolicyRepository;
    @Autowired LocationPolicyRepository locationPolicyRepository;
    @Autowired OvertimePolicyRepository overtimePolicyRepository;
    @Autowired PolicyAssignmentRepository policyAssignmentRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired EmployeeManagerRepository employeeManagerRepository;
    @Autowired EmployeeShiftRepository employeeShiftRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired OfficeLocationRepository officeLocationRepository;
    @Autowired WeeklyOffRepository weeklyOffRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Employee employee;
    private Employee manager;
    private UserPrincipal applicant;
    private UserPrincipal approver;
    private LocalDate yesterday;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        yesterday = lastWorkingDay();

        Office office = new Office();
        office.setCode("HO");
        office.setName("Head Office");
        office.setTimezone("Asia/Kolkata");
        office = officeRepository.save(office);

        OfficeLocation location = new OfficeLocation();
        location.setOffice(office);
        location.setLocationName("Main Gate");
        location.setLatitude(OFFICE_LAT);
        location.setLongitude(OFFICE_LNG);
        location.setAllowedRadiusMeters(200);
        officeLocationRepository.save(location);

        Shift shift = new Shift();
        shift.setCode("GEN");
        shift.setName("General Shift");
        shift.setStartTime(LocalTime.of(9, 30));
        shift.setEndTime(LocalTime.of(18, 30));
        shift.setStandardWorkMinutes((short) 480);
        shift.setFullDayMinutes((short) 480);
        shift.setHalfDayMinutes((short) 240);
        shift.setMinAttendanceMinutes((short) 120);
        shift.setPunchInWindowBeforeMinutes((short) 720);
        shift = shiftRepository.save(shift);

        employee = new Employee();
        employee.setEmployeeCode("EMP8001");
        employee.setFirstName("Request");
        employee.setLastName("Raiser");
        employee.setOfficialEmail("request.raiser@company.com");
        employee.setJoiningDate(LocalDate.now().minusYears(2));
        employee.setOffice(office);
        employee = employeeRepository.save(employee);

        manager = new Employee();
        manager.setEmployeeCode("EMP8002");
        manager.setFirstName("Their");
        manager.setLastName("Manager");
        manager.setOfficialEmail("their.manager@company.com");
        manager.setJoiningDate(LocalDate.now().minusYears(4));
        manager.setOffice(office);
        manager = employeeRepository.save(manager);

        EmployeeManager reporting = new EmployeeManager();
        reporting.setEmployee(employee);
        reporting.setManager(manager);
        reporting.setRelationshipType(RelationshipType.PRIMARY);
        reporting.setEffectiveFrom(LocalDate.now().minusYears(1));
        employeeManagerRepository.save(reporting);

        for (Employee person : new Employee[]{employee, manager}) {
            EmployeeShift assignment = new EmployeeShift();
            assignment.setEmployee(person);
            assignment.setShift(shift);
            assignment.setEffectiveFrom(person.getJoiningDate());
            employeeShiftRepository.save(assignment);
        }

        weeklyOffRepository.save(weeklyOff(DayOfWeek.SATURDAY));
        weeklyOffRepository.save(weeklyOff(DayOfWeek.SUNDAY));

        AttendancePolicy attendancePolicy = new AttendancePolicy();
        attendancePolicy.setCode("TEST");
        attendancePolicy.setName("Test Attendance Policy");
        attendancePolicy.setRegularizationWindowDays((short) 7);
        attendancePolicy.setMaxRegularizationsPerMonth((short) 3);
        attendancePolicy.setMaxWfhDaysPerMonth((short) 8);
        attendancePolicy = attendancePolicyRepository.save(attendancePolicy);
        assign(PolicyType.ATTENDANCE, attendancePolicy.getId());

        LocationPolicy locationPolicy = new LocationPolicy();
        locationPolicy.setCode("TEST");
        locationPolicy.setName("Test Location Policy");
        locationPolicy = locationPolicyRepository.save(locationPolicy);
        assign(PolicyType.LOCATION, locationPolicy.getId());

        OvertimePolicy overtimePolicy = new OvertimePolicy();
        overtimePolicy.setCode("TEST");
        overtimePolicy.setName("Test Overtime Policy");
        overtimePolicy = overtimePolicyRepository.save(overtimePolicy);
        assign(PolicyType.OVERTIME, overtimePolicy.getId());

        applicant = UserPrincipal.fromClaims(1L, employee.getId(), "EMP8001",
                Set.of("EMPLOYEE"),
                Set.of("REGULARIZATION_APPLY", "WFH_APPLY", "ATTENDANCE_PUNCH", "ATTENDANCE_VIEW_SELF"));
        approver = UserPrincipal.fromClaims(2L, manager.getId(), "EMP8002",
                Set.of("MANAGER"),
                Set.of("REGULARIZATION_APPROVE_L1", "WFH_APPROVE", "ATTENDANCE_VIEW_TEAM"));
    }

    private WeeklyOff weeklyOff(DayOfWeek day) {
        WeeklyOff rule = new WeeklyOff();
        rule.setScopeType("GLOBAL");
        rule.setDayOfWeek((byte) day.getValue());
        return rule;
    }

    private void assign(PolicyType type, Long policyId) {
        PolicyAssignment assignment = new PolicyAssignment();
        assignment.setPolicyType(type);
        assignment.setPolicyId(policyId);
        assignment.setScopeType("GLOBAL");
        policyAssignmentRepository.save(assignment);
    }

    private Instant at(LocalDate date, int hour, int minute) {
        return LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(IST).toInstant();
    }

    private RegularizationApplyRequest correction(LocalDate date, Instant in, Instant out, String reason) {
        return new RegularizationApplyRequest(date, reason, in, out, null, "I forgot to punch", null);
    }

    /** A weekday far enough ahead that notice rules pass. */
    private LocalDate nextWorkingDay() {
        LocalDate date = LocalDate.now().plusDays(2);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.plusDays(1);
        }
        return date;
    }

    // -----------------------------------------------------------------
    // Regularization
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a correction for a day with no record is marked as creating the day")
    void correctionForMissingDay() {
        var result = regularizationService.apply(applicant,
                correction(yesterday, at(yesterday, 9, 30), at(yesterday, 18, 30), "FORGOT_PUNCH_IN"));

        assertThat(result.creatingMissingDay()).isTrue();
        assertThat(result.originalPunchInTime()).isNull();
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.currentApproverEmployeeId()).isEqualTo(manager.getId());
    }

    @Test
    @DisplayName("approving a correction creates the day and computes its hours")
    void approvalCreatesAndComputesTheDay() {
        var result = regularizationService.apply(applicant,
                correction(yesterday, at(yesterday, 9, 30), at(yesterday, 18, 30), "FORGOT_PUNCH_IN"));

        regularizationService.approve(approver, result.id(), new RequestDecisionRequest("Confirmed"));

        Attendance attendance = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employee.getId(), yesterday).orElseThrow();

        // 09:30 to 18:30 with no breaks is a full nine hours.
        assertThat(attendance.getWorkingMinutes()).isEqualTo(540);
        assertThat(attendance.getAttendanceStatus()).isEqualTo(AttendanceStatus.PRESENT);
        assertThat(attendance.isRegularized()).isTrue();
        assertThat(attendance.getSource().name()).isEqualTo("REGULARIZATION");
    }

    @Test
    @DisplayName("the before-image is frozen at submission, not read back after the rewrite")
    void beforeImageSurvivesTheRewrite() {
        // A real punched day that leaves early.
        Attendance existing = new Attendance();
        existing.setEmployee(employee);
        existing.setAttendanceDate(yesterday);
        existing.setPunchInTime(at(yesterday, 9, 30));
        existing.setPunchOutTime(at(yesterday, 13, 0));
        existing.setWorkingMinutes(210);
        existing.setAttendanceStatus(AttendanceStatus.HALF_DAY);
        attendanceRepository.save(existing);

        var result = regularizationService.apply(applicant,
                correction(yesterday, null, at(yesterday, 18, 30), "FORGOT_PUNCH_OUT"));

        assertThat(result.originalWorkingMinutes()).isEqualTo(210);
        assertThat(result.originalStatus()).isEqualTo("HALF_DAY");

        regularizationService.approve(approver, result.id(), null);

        // The day is rewritten...
        Attendance after = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employee.getId(), yesterday).orElseThrow();
        assertThat(after.getWorkingMinutes()).isEqualTo(540);
        assertThat(after.getAttendanceStatus()).isEqualTo(AttendanceStatus.PRESENT);

        // ...but the request still remembers what it replaced.
        var reloaded = regularizationService.getById(applicant, result.id());
        assertThat(reloaded.originalWorkingMinutes()).isEqualTo(210);
        assertThat(reloaded.originalStatus()).isEqualTo("HALF_DAY");
    }

    @Test
    @DisplayName("approval appends an event rather than replacing the original evidence")
    void originalPunchEvidenceSurvives() {
        Attendance existing = new Attendance();
        existing.setEmployee(employee);
        existing.setAttendanceDate(yesterday);
        existing.setPunchInTime(at(yesterday, 9, 30));
        existing.setAttendanceStatus(AttendanceStatus.MISSING_PUNCH_OUT);
        existing = attendanceRepository.save(existing);

        var original = new com.company.attendance.entity.AttendancePunchEvent();
        original.setAttendance(existing);
        original.setEmployeeId(employee.getId());
        original.setEventType(com.company.attendance.enums.PunchEventType.PUNCH_IN);
        original.setEventTime(at(yesterday, 9, 30));
        original.setSource(com.company.attendance.enums.AttendanceSource.WEB);
        original.setCreatedAt(Instant.now());
        punchEventRepository.save(original);

        var result = regularizationService.apply(applicant,
                correction(yesterday, null, at(yesterday, 18, 30), "FORGOT_PUNCH_OUT"));
        regularizationService.approve(approver, result.id(), null);

        var events = punchEventRepository.findByAttendanceIdOrderByEventTimeAsc(existing.getId());

        // Both the device's original punch and the correction are on record.
        assertThat(events).hasSize(2);
        assertThat(events).anyMatch(e -> e.getSource().name().equals("WEB"));
        assertThat(events).anyMatch(e -> e.getSource().name().equals("REGULARIZATION"));
    }

    @Test
    @DisplayName("corrections outside the policy window are refused")
    void windowIsEnforced() {
        LocalDate longAgo = LocalDate.now().minusDays(30);

        assertThatThrownBy(() -> regularizationService.apply(applicant,
                correction(longAgo, at(longAgo, 9, 30), at(longAgo, 18, 30), "FORGOT_PUNCH_IN")))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("within 7 day"));
    }

    @Test
    @DisplayName("a future day cannot be corrected")
    void futureDayIsRefused() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        assertThatThrownBy(() -> regularizationService.apply(applicant,
                correction(tomorrow, at(tomorrow, 9, 30), at(tomorrow, 18, 30), "FORGOT_PUNCH_IN")))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("has not happened yet"));
    }

    @Test
    @DisplayName("the monthly cap on corrections is enforced")
    void monthlyCapIsEnforced() {
        // The cap counts per calendar month, so all four dates have to sit in
        // the same month or the fourth simply starts a fresh count. Walking back
        // from today does not guarantee that — run this on the 2nd and three of
        // the four land in the previous month — so the dates are pinned inside
        // one month and the window widened to reach them.
        AttendancePolicy policy = attendancePolicyRepository.findByCode("TEST").orElseThrow();
        policy.setRegularizationWindowDays((short) 60);
        attendancePolicyRepository.save(policy);

        LocalDate anchor = LocalDate.now().withDayOfMonth(1).minusMonths(1).plusDays(9);

        for (int i = 0; i < 3; i++) {
            LocalDate date = anchor.plusDays(i);
            regularizationService.apply(applicant,
                    correction(date, at(date, 9, 30), at(date, 18, 30), "FORGOT_PUNCH_IN"));
        }

        LocalDate fourth = anchor.plusDays(3);
        assertThatThrownBy(() -> regularizationService.apply(applicant,
                correction(fourth, at(fourth, 9, 30), at(fourth, 18, 30), "FORGOT_PUNCH_IN")))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("all 3 corrections"));
    }

    @Test
    @DisplayName("only one open correction per day")
    void oneOpenRequestPerDay() {
        regularizationService.apply(applicant,
                correction(yesterday, at(yesterday, 9, 30), at(yesterday, 18, 30), "FORGOT_PUNCH_IN"));

        assertThatThrownBy(() -> regularizationService.apply(applicant,
                correction(yesterday, at(yesterday, 10, 0), at(yesterday, 19, 0), "WRONG_PUNCH")))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.DUPLICATE_REGULARIZATION));
    }

    @Test
    @DisplayName("a correction that changes nothing is refused")
    void noOpCorrectionIsRefused() {
        Attendance existing = new Attendance();
        existing.setEmployee(employee);
        existing.setAttendanceDate(yesterday);
        existing.setPunchInTime(at(yesterday, 9, 30));
        existing.setPunchOutTime(at(yesterday, 18, 30));
        existing.setAttendanceStatus(AttendanceStatus.PRESENT);
        attendanceRepository.save(existing);

        assertThatThrownBy(() -> regularizationService.apply(applicant,
                correction(yesterday, at(yesterday, 9, 30), at(yesterday, 18, 30), "WRONG_PUNCH")))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("nothing to correct"));
    }

    @Test
    @DisplayName("a punch-out before the punch-in is refused")
    void invertedTimesAreRefused() {
        assertThatThrownBy(() -> regularizationService.apply(applicant,
                correction(yesterday, at(yesterday, 18, 30), at(yesterday, 9, 30), "WRONG_PUNCH")))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("must be after"));
    }

    @Test
    @DisplayName("a forgotten punch-out needs a punch-out time")
    void reasonTypeMustMatchTheTimesGiven() {
        assertThatThrownBy(() -> regularizationService.apply(applicant,
                correction(yesterday, at(yesterday, 9, 30), null, "FORGOT_PUNCH_OUT")))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("needs a punch-out time"));
    }

    @Test
    @DisplayName("nobody approves their own correction")
    void nobodyApprovesTheirOwnCorrection() {
        var result = regularizationService.apply(applicant,
                correction(yesterday, at(yesterday, 9, 30), at(yesterday, 18, 30), "FORGOT_PUNCH_IN"));

        UserPrincipal self = UserPrincipal.fromClaims(1L, employee.getId(), "EMP8001",
                Set.of("MANAGER"),
                Set.of("REGULARIZATION_APPLY", "REGULARIZATION_APPROVE_L1", "REGULARIZATION_APPROVE_L2"));

        assertThatThrownBy(() -> regularizationService.approve(self, result.id(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("your own correction"));
    }

    @Test
    @DisplayName("a rejection must state a reason")
    void rejectionNeedsAReason() {
        var result = regularizationService.apply(applicant,
                correction(yesterday, at(yesterday, 9, 30), at(yesterday, 18, 30), "FORGOT_PUNCH_IN"));

        assertThatThrownBy(() -> regularizationService.reject(approver, result.id(),
                new RequestDecisionRequest("  ")))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("reason"));
    }

    @Test
    @DisplayName("a locked day cannot be corrected")
    void lockedDayIsProtected() {
        Attendance locked = new Attendance();
        locked.setEmployee(employee);
        locked.setAttendanceDate(yesterday);
        locked.setPunchInTime(at(yesterday, 9, 30));
        locked.setAttendanceStatus(AttendanceStatus.MISSING_PUNCH_OUT);
        locked.setLocked(true);
        attendanceRepository.save(locked);

        assertThatThrownBy(() -> regularizationService.apply(applicant,
                correction(yesterday, null, at(yesterday, 18, 30), "FORGOT_PUNCH_OUT")))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("locked for payroll"));
    }

    // -----------------------------------------------------------------
    // Work from home
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a work-from-home request counts working days only")
    void wfhCountsWorkingDaysOnly() {
        LocalDate friday = LocalDate.now().plusDays(2).with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        LocalDate monday = friday.plusDays(3);

        var result = workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(friday, monday, "Deliveries at home", "HOME", null));

        assertThat(result.totalDays()).isEqualTo(2);
        assertThat(result.calendarDays()).isEqualTo(4);
        assertThat(result.days()).hasSize(2);
    }

    @Test
    @DisplayName("work from home cannot be requested for a day that has passed")
    void wfhCannotBeBackdated() {
        assertThatThrownBy(() -> workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(yesterday, yesterday, "Was at home", "HOME", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("attendance correction"));
    }

    @Test
    @DisplayName("overlapping work-from-home dates are refused")
    void wfhOverlapIsRefused() {
        LocalDate day = nextWorkingDay();
        workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(day, day, "First", "HOME", null));

        assertThatThrownBy(() -> workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(day, day, "Second", "HOME", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("already have"));
    }

    @Test
    @DisplayName("a range with no working days is refused")
    void wfhAllWeekendIsRefused() {
        LocalDate saturday = LocalDate.now().plusDays(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));

        assertThatThrownBy(() -> workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(saturday, saturday.plusDays(1), "Weekend", "HOME", null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("nothing to request"));
    }

    @Test
    @DisplayName("the monthly work-from-home cap is enforced")
    void wfhMonthlyCapIsEnforced() {
        AttendancePolicy policy = attendancePolicyRepository.findByCode("TEST").orElseThrow();
        policy.setMaxWfhDaysPerMonth((short) 2);
        attendancePolicyRepository.save(policy);

        LocalDate first = nextWorkingDay();
        workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(first, first, "Day one", "HOME", null));

        LocalDate second = first.plusDays(1).getDayOfWeek() == DayOfWeek.SATURDAY
                ? first.plusDays(3) : first.plusDays(1);
        workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(second, second, "Day two", "HOME", null));

        LocalDate third = second.plusDays(1).getDayOfWeek() == DayOfWeek.SATURDAY
                ? second.plusDays(3) : second.plusDays(1);

        // Only refused if the third day lands in the same month as the cap.
        if (third.getMonth() == first.getMonth()) {
            assertThatThrownBy(() -> workFromHomeService.apply(applicant,
                    new WorkFromHomeApplyRequest(third, third, "Day three", "HOME", null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> assertThat(thrown.getMessage()).contains("limit is 2"));
        }
    }

    // -----------------------------------------------------------------
    // The reason this phase exists: WFH unlocks the geofence
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a work-from-home punch is refused without an approved request")
    void wfhPunchRefusedWithoutApproval() {
        PunchRequest wfhPunch = new PunchRequest(null, null, null, Instant.now(), "WFH", "JUnit");

        assertThatThrownBy(() -> punchService.punchIn(applicant, wfhPunch))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.WFH_NOT_APPROVED));
    }

    @Test
    @DisplayName("a pending request is not enough — only an approved one unlocks the punch")
    void pendingWfhDoesNotUnlockThePunch() {
        LocalDate today = LocalDate.now();
        // Requesting today is allowed; it is not in the past.
        if (today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return;
        }
        workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(today, today, "Working remotely", "HOME", null));

        assertThat(workFromHomeService.isApprovedOn(employee.getId(), today)).isFalse();

        PunchRequest wfhPunch = new PunchRequest(null, null, null, Instant.now(), "WFH", "JUnit");
        assertThatThrownBy(() -> punchService.punchIn(applicant, wfhPunch))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.WFH_NOT_APPROVED));
    }

    @Test
    @DisplayName("once approved, the same punch succeeds with no location at all")
    void approvedWfhUnlocksThePunch() {
        LocalDate today = LocalDate.now();
        if (today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return;
        }

        var request = workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(today, today, "Working remotely", "HOME", null));
        workFromHomeService.approve(approver, request.id(), new RequestDecisionRequest("Fine"));

        assertThat(workFromHomeService.isApprovedOn(employee.getId(), today)).isTrue();

        // No coordinates whatsoever, and the geofence no longer applies.
        PunchRequest wfhPunch = new PunchRequest(null, null, null, Instant.now(), "WFH", "JUnit");
        Attendance attendance = punchService.punchIn(applicant, wfhPunch);

        assertThat(attendance.getWorkMode().name()).isEqualTo("WFH");
        assertThat(attendance.getPunchInLocation()).isEqualTo("Work from home");
        assertThat(attendance.getPunchInLatitude()).isNull();
    }

    @Test
    @DisplayName("approval does not write attendance — the day still needs a punch")
    void wfhApprovalDoesNotFabricateAttendance() {
        LocalDate day = nextWorkingDay();
        var request = workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(day, day, "Remote", "HOME", null));
        workFromHomeService.approve(approver, request.id(), null);

        // Permission granted, but nothing recorded: hours still come from a punch.
        assertThat(attendanceRepository.findByEmployeeIdAndAttendanceDate(employee.getId(), day))
                .isEmpty();
    }

    @Test
    @DisplayName("nobody approves their own work-from-home request")
    void nobodyApprovesTheirOwnWfh() {
        LocalDate day = nextWorkingDay();
        var request = workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(day, day, "Remote", "HOME", null));

        UserPrincipal self = UserPrincipal.fromClaims(1L, employee.getId(), "EMP8001",
                Set.of("MANAGER"), Set.of("WFH_APPLY", "WFH_APPROVE"));

        assertThatThrownBy(() -> workFromHomeService.approve(self, request.id(), null))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("your own request"));
    }

    @Test
    @DisplayName("the approver's inbox shows only what is routed to them")
    void approverInboxIsScoped() {
        LocalDate day = nextWorkingDay();
        workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(day, day, "Remote", "HOME", null));

        assertThat(workFromHomeService.getPendingApprovals(approver, PageRequest.of(0, 20)).totalElements())
                .isEqualTo(1);
        assertThat(workFromHomeService.getPendingApprovals(applicant, PageRequest.of(0, 20)).totalElements())
                .isZero();
    }

    @Test
    @DisplayName("withdrawing a request releases its dates")
    void withdrawalReleasesDates() {
        LocalDate day = nextWorkingDay();
        var request = workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(day, day, "First", "HOME", null));

        workFromHomeService.withdraw(applicant, request.id(), "Plans changed");

        // The same date can be requested again.
        var second = workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(day, day, "Second", "HOME", null));
        assertThat(second.id()).isNotNull();
    }

    // -----------------------------------------------------------------
    // The approval inbox
    //
    // Every one of these was invisible to the suite until a request in the
    // running application turned out to be sitting PENDING in nobody's inbox.
    // -----------------------------------------------------------------

    /** Someone with no manager above them, so the resolver can route to nobody. */
    private Employee unmanagedEmployee() {
        Employee loner = new Employee();
        loner.setEmployeeCode("EMP8003");
        loner.setFirstName("No");
        loner.setLastName("Manager");
        loner.setOfficialEmail("no.manager@company.com");
        loner.setJoiningDate(LocalDate.now().minusYears(1));
        loner.setOffice(employee.getOffice());
        loner = employeeRepository.save(loner);

        EmployeeShift assignment = new EmployeeShift();
        assignment.setEmployee(loner);
        assignment.setShift(shiftRepository.findAll().get(0));
        assignment.setEffectiveFrom(loner.getJoiningDate());
        employeeShiftRepository.save(assignment);
        return loner;
    }

    private UserPrincipal principal(long userId, Employee person, String... permissions) {
        return UserPrincipal.fromClaims(userId, person.getId(), person.getEmployeeCode(),
                Set.of("EMPLOYEE"), Set.of(permissions));
    }

    @Test
    @DisplayName("a request reaches the inbox of the manager it was routed to")
    void routedRequestReachesItsApprover() {
        LocalDate day = nextWorkingDay();
        workFromHomeService.apply(applicant,
                new WorkFromHomeApplyRequest(day, day, "Remote", "HOME", null));

        assertThat(workFromHomeService.getPendingApprovals(approver, PageRequest.of(0, 20)).totalElements())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a manager sees requests from their team even when routing missed them")
    void managerSeesTheirTeamsUnroutedRequests() {
        // Routed to nobody, because the reporting line is added afterwards —
        // exactly what happens when someone is given a manager after applying.
        Employee loner = unmanagedEmployee();
        UserPrincipal lonerPrincipal = principal(3L, loner, "WFH_APPLY");

        LocalDate day = nextWorkingDay();
        var request = workFromHomeService.apply(lonerPrincipal,
                new WorkFromHomeApplyRequest(day, day, "Remote", "HOME", null));
        assertThat(request.currentApproverEmployeeId()).isNull();

        EmployeeManager reporting = new EmployeeManager();
        reporting.setEmployee(loner);
        reporting.setManager(manager);
        reporting.setRelationshipType(RelationshipType.PRIMARY);
        reporting.setEffectiveFrom(LocalDate.now().minusDays(1));
        employeeManagerRepository.save(reporting);

        assertThat(workFromHomeService.getPendingApprovals(approver, PageRequest.of(0, 20)).totalElements())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a request routed to nobody still reaches someone who may act on any request")
    void unroutedRequestIsNotStranded() {
        Employee loner = unmanagedEmployee();
        UserPrincipal lonerPrincipal = principal(3L, loner, "WFH_APPLY");

        LocalDate day = nextWorkingDay();
        var request = workFromHomeService.apply(lonerPrincipal,
                new WorkFromHomeApplyRequest(day, day, "Remote", "HOME", null));
        assertThat(request.currentApproverEmployeeId()).isNull();

        // A plain manager who does not manage them must not see it...
        assertThat(workFromHomeService.getPendingApprovals(approver, PageRequest.of(0, 20)).totalElements())
                .isZero();

        // ...but someone who may act on any request must, or it is stranded.
        UserPrincipal hr = principal(4L, manager, "WFH_APPROVE", "ATTENDANCE_VIEW_ALL");
        assertThat(workFromHomeService.getPendingApprovals(hr, PageRequest.of(0, 20)).totalElements())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("your own request never appears in your own inbox, whatever you hold")
    void ownRequestIsNeverInYourOwnInbox() {
        Employee loner = unmanagedEmployee();

        // Applies with one hat on, then looks at the inbox wearing every hat
        // there is. Self-approval is refused, so offering it would be a lie.
        UserPrincipal applyingSelf = principal(3L, loner, "WFH_APPLY");
        LocalDate day = nextWorkingDay();
        workFromHomeService.apply(applyingSelf,
                new WorkFromHomeApplyRequest(day, day, "Remote", "HOME", null));

        UserPrincipal approvingSelf =
                principal(3L, loner, "WFH_APPROVE", "ATTENDANCE_VIEW_ALL");
        assertThat(workFromHomeService.getPendingApprovals(approvingSelf, PageRequest.of(0, 20))
                .totalElements()).isZero();
    }

    @Test
    @DisplayName("corrections follow the same inbox rule as work from home")
    void correctionInboxMatchesTheDecisionRule() {
        Employee loner = unmanagedEmployee();
        UserPrincipal lonerPrincipal =
                principal(3L, loner, "REGULARIZATION_APPLY", "ATTENDANCE_PUNCH");

        var request = regularizationService.apply(lonerPrincipal,
                correction(yesterday, at(yesterday, 9, 30), at(yesterday, 18, 30), "FORGOT_PUNCH_IN"));
        assertThat(request.currentApproverEmployeeId()).isNull();

        // An L1 approver with no link to them sees nothing.
        UserPrincipal l1 = principal(4L, manager, "REGULARIZATION_APPROVE_L1");
        assertThat(regularizationService.getPendingApprovals(l1, PageRequest.of(0, 20)).totalElements())
                .isZero();

        // An L2 approver may act on any correction, so it reaches them.
        UserPrincipal l2 = principal(5L, manager, "REGULARIZATION_APPROVE_L2");
        assertThat(regularizationService.getPendingApprovals(l2, PageRequest.of(0, 20)).totalElements())
                .isEqualTo(1);
    }

    /**
     * The most recent day that is not a weekly off.
     *
     * <p>Not simply yesterday: on a Monday that is Sunday, and a test that
     * then asserts the day resolved to PRESENT fails once a week for a reason
     * that has nothing to do with what it is testing.
     */
    private static LocalDate lastWorkingDay() {
        LocalDate date = LocalDate.now().minusDays(1);
        while (date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.minusDays(1);
        }
        return date;
    }
}
