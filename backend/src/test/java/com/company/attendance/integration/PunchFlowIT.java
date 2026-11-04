package com.company.attendance.integration;

import com.company.attendance.dto.request.attendance.BreakRequest;
import com.company.attendance.dto.request.attendance.PunchRequest;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.BreakType;
import com.company.attendance.entity.Employee;
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
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendanceBreakRepository;
import com.company.attendance.repository.AttendancePolicyRepository;
import com.company.attendance.repository.AttendancePunchEventRepository;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.BreakTypeRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.HolidayRepository;
import com.company.attendance.repository.LocationPolicyRepository;
import com.company.attendance.repository.OfficeLocationRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.OvertimePolicyRepository;
import com.company.attendance.repository.OvertimeRecordRepository;
import com.company.attendance.repository.PasswordResetTokenRepository;
import com.company.attendance.repository.RefreshTokenRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.repository.PolicyAssignmentRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.WeeklyOffRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.support.DatabaseCleaner;
import com.company.attendance.service.BreakService;
import com.company.attendance.service.PunchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PunchFlowIT {

    // The seeded head-office coordinates.
    private static final BigDecimal OFFICE_LAT = new BigDecimal("19.0759837");
    private static final BigDecimal OFFICE_LNG = new BigDecimal("72.8776559");

    @Autowired PunchService punchService;
    @Autowired BreakService breakService;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired AttendancePunchEventRepository punchEventRepository;
    @Autowired AttendanceBreakRepository breakRepository;
    @Autowired OvertimeRecordRepository overtimeRecordRepository;
    @Autowired BreakTypeRepository breakTypeRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired EmployeeShiftRepository employeeShiftRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired OfficeLocationRepository officeLocationRepository;
    @Autowired AttendancePolicyRepository attendancePolicyRepository;
    @Autowired LocationPolicyRepository locationPolicyRepository;
    @Autowired OvertimePolicyRepository overtimePolicyRepository;
    @Autowired PolicyAssignmentRepository policyAssignmentRepository;
    @Autowired WeeklyOffRepository weeklyOffRepository;
    @Autowired HolidayRepository holidayRepository;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired PasswordResetTokenRepository passwordResetTokenRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Employee employee;
    private UserPrincipal principal;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

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

        // A shift that is running right now, so punch-window checks pass
        // regardless of when the suite happens to run.
        Shift shift = new Shift();
        shift.setCode("ALLDAY");
        shift.setName("All Day");
        shift.setStartTime(LocalTime.of(0, 1));
        shift.setEndTime(LocalTime.of(23, 59));
        shift.setStandardWorkMinutes((short) 480);
        shift.setFullDayMinutes((short) 480);
        shift.setHalfDayMinutes((short) 240);
        shift.setMinAttendanceMinutes((short) 120);
        shift.setPunchInWindowBeforeMinutes((short) 720);
        shift = shiftRepository.save(shift);

        employee = new Employee();
        employee.setEmployeeCode("EMP9001");
        employee.setFirstName("Punch");
        employee.setLastName("Tester");
        employee.setOfficialEmail("punch.tester@company.com");
        employee.setJoiningDate(LocalDate.now().minusYears(1));
        employee.setOffice(office);
        employee = employeeRepository.save(employee);

        var assignment = new com.company.attendance.entity.EmployeeShift();
        assignment.setEmployee(employee);
        assignment.setShift(shift);
        assignment.setEffectiveFrom(employee.getJoiningDate());
        employeeShiftRepository.save(assignment);

        AttendancePolicy attendancePolicy = new AttendancePolicy();
        attendancePolicy.setCode("TEST");
        attendancePolicy.setName("Test Attendance Policy");
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

        BreakType lunch = new BreakType();
        lunch.setCode("LUNCH");
        lunch.setName("Lunch Break");
        lunch.setMaxMinutes((short) 45);
        lunch.setDeductedFromWorkingHours(true);
        breakTypeRepository.save(lunch);

        BreakType personal = new BreakType();
        personal.setCode("PERSONAL");
        personal.setName("Personal Break");
        personal.setMaxMinutes((short) 30);
        personal.setDeductedFromWorkingHours(true);
        breakTypeRepository.save(personal);

        principal = UserPrincipal.fromClaims(
                1L, employee.getId(), "EMP9001",
                Set.of("EMPLOYEE"), Set.of("ATTENDANCE_PUNCH", "ATTENDANCE_VIEW_SELF"));
    }

    private void assign(PolicyType type, Long policyId) {
        PolicyAssignment assignment = new PolicyAssignment();
        assignment.setPolicyType(type);
        assignment.setPolicyId(policyId);
        assignment.setScopeType("GLOBAL");
        policyAssignmentRepository.save(assignment);
    }

    private PunchRequest atOffice() {
        return new PunchRequest(OFFICE_LAT, OFFICE_LNG, new BigDecimal("12.5"),
                java.time.Instant.now(), "OFFICE", "JUnit");
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("punching in inside the geofence creates the day and records an event")
    void punchInSucceedsInsideGeofence() {
        Attendance attendance = punchService.punchIn(principal, atOffice());

        assertThat(attendance.getId()).isNotNull();
        assertThat(attendance.getAttendanceStatus()).isEqualTo(AttendanceStatus.PUNCHED_IN);
        assertThat(attendance.getPunchInTime()).isNotNull();
        assertThat(attendance.getPunchInLocation()).isEqualTo("Main Gate");
        // Standing on the office coordinates is essentially zero distance.
        assertThat(attendance.getPunchInDistanceMeters()).isNotNull();
        assertThat(attendance.getPunchInDistanceMeters().doubleValue()).isLessThan(1);

        var events = punchEventRepository.findByAttendanceIdOrderByEventTimeAsc(attendance.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getWithinGeofence()).isTrue();
    }

    @Test
    @DisplayName("punching in from outside the radius is refused, with the distance in the payload")
    void punchInRejectedOutsideGeofence() {
        // Roughly 400 m north of the office, outside the 200 m circle.
        PunchRequest faraway = new PunchRequest(
                OFFICE_LAT.add(new BigDecimal("0.0036")), OFFICE_LNG,
                new BigDecimal("10"), java.time.Instant.now(), "OFFICE", "JUnit");

        assertThatThrownBy(() -> punchService.punchIn(principal, faraway))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> {
                    BusinessException e = (BusinessException) thrown;
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OUTSIDE_GEOFENCE);
                    // The employee is told how far out they are, so the message
                    // is actionable rather than a dead end.
                    assertThat(e.getDetails()).containsKeys(
                            "nearestOffice", "distanceMeters", "allowedRadiusMeters");
                });

        assertThat(attendanceRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a GPS fix too vague to place inside the radius is refused")
    void punchInRejectedOnPoorAccuracy() {
        PunchRequest vague = new PunchRequest(OFFICE_LAT, OFFICE_LNG,
                new BigDecimal("500"), java.time.Instant.now(), "OFFICE", "JUnit");

        assertThatThrownBy(() -> punchService.punchIn(principal, vague))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.INSUFFICIENT_LOCATION_ACCURACY));
    }

    @Test
    @DisplayName("a second punch-in on the same day is refused by the unique constraint")
    void duplicatePunchInIsRefused() {
        punchService.punchIn(principal, atOffice());

        assertThatThrownBy(() -> punchService.punchIn(principal, atOffice()))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.ALREADY_PUNCHED_IN));

        assertThat(attendanceRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("punching out without punching in is refused")
    void punchOutRequiresPunchIn() {
        assertThatThrownBy(() -> punchService.punchOut(principal, atOffice()))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_PUNCHED_IN));
    }

    @Test
    @DisplayName("punching out twice is refused")
    void doublePunchOutIsRefused() {
        punchService.punchIn(principal, atOffice());
        punchService.punchOut(principal, atOffice());

        assertThatThrownBy(() -> punchService.punchOut(principal, atOffice()))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.ALREADY_PUNCHED_OUT));
    }

    @Test
    @DisplayName("punch-out settles the day and appends a second event")
    void punchOutSettlesTheDay() {
        punchService.punchIn(principal, atOffice());
        Attendance attendance = punchService.punchOut(principal, atOffice());

        assertThat(attendance.getPunchOutTime()).isNotNull();
        // Punching straight back out is a very short day, so it settles as absent.
        assertThat(attendance.getAttendanceStatus()).isEqualTo(AttendanceStatus.ABSENT);
        assertThat(attendance.getWorkingMinutes()).isZero();

        assertThat(punchEventRepository.findByAttendanceIdOrderByEventTimeAsc(attendance.getId()))
                .hasSize(2);
    }

    // -----------------------------------------------------------------
    // Breaks
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a break can be started and ended, and both are recorded as events")
    void breakLifecycle() {
        Attendance attendance = punchService.punchIn(principal, atOffice());

        var started = breakService.startBreak(principal, new BreakRequest("LUNCH"));
        assertThat(started.isOpen()).isTrue();
        assertThat(attendanceRepository.findById(attendance.getId()).orElseThrow()
                .getAttendanceStatus()).isEqualTo(AttendanceStatus.ON_BREAK);

        var ended = breakService.endBreak(principal);
        assertThat(ended.isOpen()).isFalse();
        assertThat(attendanceRepository.findById(attendance.getId()).orElseThrow()
                .getAttendanceStatus()).isEqualTo(AttendanceStatus.PUNCHED_IN);

        // Punch in, break start, break end.
        assertThat(punchEventRepository.findByAttendanceIdOrderByEventTimeAsc(attendance.getId()))
                .hasSize(3);
    }

    @Test
    @DisplayName("a second break cannot be opened while one is running")
    void onlyOneBreakAtATime() {
        punchService.punchIn(principal, atOffice());
        breakService.startBreak(principal, new BreakRequest("LUNCH"));

        assertThatThrownBy(() -> breakService.startBreak(principal, new BreakRequest("TEA")))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.BREAK_ALREADY_OPEN));
    }

    @Test
    @DisplayName("ending a break that was never started is refused")
    void cannotEndABreakThatIsNotRunning() {
        punchService.punchIn(principal, atOffice());

        assertThatThrownBy(() -> breakService.endBreak(principal))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a break left open at punch-out is closed automatically and marked as such")
    void punchOutClosesAnOpenBreak() {
        Attendance attendance = punchService.punchIn(principal, atOffice());
        breakService.startBreak(principal, new BreakRequest("LUNCH"));

        punchService.punchOut(principal, atOffice());

        var breaks = breakRepository.findByAttendanceIdOrderByBreakStartTimeAsc(attendance.getId());
        assertThat(breaks).hasSize(1);
        assertThat(breaks.get(0).isOpen()).isFalse();
        // Distinguishable from a break the employee ended themselves.
        assertThat(breaks.get(0).isAutoClosed()).isTrue();
    }

    @Test
    @DisplayName("breaks cannot be started before punching in")
    void breakRequiresAnOpenDay() {
        assertThatThrownBy(() -> breakService.startBreak(principal, new BreakRequest("LUNCH")))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_PUNCHED_IN));
    }

    // -----------------------------------------------------------------
    // Guards
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an employee with no shift cannot punch, and is told why")
    void noShiftMeansNoPunch() {
        employeeShiftRepository.deleteAll();

        assertThatThrownBy(() -> punchService.punchIn(principal, atOffice()))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> {
                    BusinessException e = (BusinessException) thrown;
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NO_SHIFT_ASSIGNED);
                    assertThat(e.getMessage()).contains("Ask HR");
                });
    }

    @Test
    @DisplayName("a missing policy is reported as configuration, not silently defaulted")
    void missingPolicyIsReported() {
        policyAssignmentRepository.deleteAll();

        assertThatThrownBy(() -> punchService.punchIn(principal, atOffice()))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> {
                    BusinessException e = (BusinessException) thrown;
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.POLICY_VIOLATION);
                    assertThat(e.getMessage()).contains("administrator");
                });
    }

    @Test
    @DisplayName("an account with no employee record cannot punch")
    void systemAccountCannotPunch() {
        UserPrincipal systemAccount = UserPrincipal.fromClaims(
                99L, null, "SYSTEM", Set.of("ADMIN"), Set.of("ATTENDANCE_PUNCH"));

        assertThatThrownBy(() -> punchService.punchIn(systemAccount, atOffice()))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.EMPLOYEE_NOT_FOUND));
    }

    @Test
    @DisplayName("a weekly off blocks punching when the policy says so")
    void weeklyOffCanBlockPunching() {
        // Make today a weekly off, and forbid punching on one.
        WeeklyOff rule = new WeeklyOff();
        rule.setScopeType("GLOBAL");
        rule.setDayOfWeek((byte) LocalDate.now().getDayOfWeek().getValue());
        weeklyOffRepository.save(rule);

        AttendancePolicy policy = attendancePolicyRepository.findByCode("TEST").orElseThrow();
        policy.setAllowPunchOnWeeklyOff(false);
        attendancePolicyRepository.save(policy);

        assertThatThrownBy(() -> punchService.punchIn(principal, atOffice()))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("weekly off"));
    }

    @Test
    @DisplayName("working a weekly off is allowed by default and keeps the day's identity")
    void weeklyOffAllowedByDefault() {
        WeeklyOff rule = new WeeklyOff();
        rule.setScopeType("GLOBAL");
        rule.setDayOfWeek((byte) LocalDate.now().getDayOfWeek().getValue());
        weeklyOffRepository.save(rule);

        punchService.punchIn(principal, atOffice());
        Attendance attendance = punchService.punchOut(principal, atOffice());

        // The hours still count, but the day does not become an ordinary
        // present day just because someone worked it.
        assertThat(attendance.getAttendanceStatus()).isEqualTo(AttendanceStatus.WEEKLY_OFF);
    }

    @Test
    @DisplayName("field attendance is refused unless the location policy enables it")
    void fieldPunchNeedsPolicy() {
        PunchRequest field = new PunchRequest(
                new BigDecimal("19.2000000"), new BigDecimal("72.9000000"),
                new BigDecimal("15"), java.time.Instant.now(), "FIELD", "JUnit");

        assertThatThrownBy(() -> punchService.punchIn(principal, field))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("Field attendance"));
    }

    @Test
    @DisplayName("with field attendance enabled, a punch far from any office is accepted")
    void fieldPunchSkipsTheRadius() {
        LocationPolicy policy = locationPolicyRepository.findByCode("TEST").orElseThrow();
        policy.setAllowFieldPunch(true);
        locationPolicyRepository.save(policy);

        PunchRequest field = new PunchRequest(
                new BigDecimal("19.2000000"), new BigDecimal("72.9000000"),
                new BigDecimal("15"), java.time.Instant.now(), "FIELD", "JUnit");

        Attendance attendance = punchService.punchIn(principal, field);

        assertThat(attendance.getPunchInLocation()).isEqualTo("Field location");
        // Coordinates are still recorded, even though no radius applied.
        assertThat(attendance.getPunchInLatitude()).isEqualByComparingTo("19.2000000");
    }

    @Test
    @DisplayName("work from home is refused without an approved request")
    void workFromHomeNeedsApproval() {
        PunchRequest wfh = new PunchRequest(null, null, null,
                java.time.Instant.now(), "WFH", "JUnit");

        assertThatThrownBy(() -> punchService.punchIn(principal, wfh))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.WFH_NOT_APPROVED));
    }
}
