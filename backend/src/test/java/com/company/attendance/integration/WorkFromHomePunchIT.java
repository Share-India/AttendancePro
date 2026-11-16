package com.company.attendance.integration;

import com.company.attendance.dto.request.attendance.PunchRequest;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.LocationPolicy;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.OfficeLocation;
import com.company.attendance.entity.OvertimePolicy;
import com.company.attendance.entity.PolicyAssignment;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.WfhRequestDay;
import com.company.attendance.entity.WorkFromHomeRequest;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.PolicyType;
import com.company.attendance.enums.RequestStatus;
import com.company.attendance.enums.WorkMode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendancePolicyRepository;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.LocationPolicyRepository;
import com.company.attendance.repository.OfficeLocationRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.OvertimePolicyRepository;
import com.company.attendance.repository.PolicyAssignmentRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.WfhRequestDayRepository;
import com.company.attendance.repository.WorkFromHomeRequestRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.AttendanceService;
import com.company.attendance.service.PunchService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Punching on a day somebody is approved to work from home.
 *
 * <p>This used to be impossible. The browser sent {@code workMode: "OFFICE"}
 * on every punch and the server defaulted to the same, so an approved
 * work-from-home day was still measured against the office geofence: the
 * employee was refused for being too far from a building they had permission
 * not to be in, no attendance was recorded, and the day was later counted
 * absent -- which on the salary sheet costs them a day of leave balance.
 *
 * <p>The mode is now decided by the server from the approval and from where
 * the employee actually is. These tests pin that down, because the failure it
 * replaces was silent: every punch succeeded, nobody saw an error, and the
 * only symptom was that work-from-home never once appeared in the data.
 */
@SpringBootTest
@ActiveProfiles("test")
class WorkFromHomePunchIT {

    private static final BigDecimal OFFICE_LAT = new BigDecimal("19.0759837");
    private static final BigDecimal OFFICE_LNG = new BigDecimal("72.8776559");

    /** Roughly 400 m north of the office: outside the 200 m circle. */
    private static final BigDecimal AWAY_LAT = OFFICE_LAT.add(new BigDecimal("0.0036"));

    @Autowired PunchService punchService;
    @Autowired AttendanceService attendanceService;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired EmployeeShiftRepository employeeShiftRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired OfficeLocationRepository officeLocationRepository;
    @Autowired AttendancePolicyRepository attendancePolicyRepository;
    @Autowired LocationPolicyRepository locationPolicyRepository;
    @Autowired OvertimePolicyRepository overtimePolicyRepository;
    @Autowired PolicyAssignmentRepository policyAssignmentRepository;
    @Autowired WorkFromHomeRequestRepository wfhRequestRepository;
    @Autowired WfhRequestDayRepository wfhRequestDayRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Employee employee;
    private UserPrincipal principal;
    private LocationPolicy locationPolicy;

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
        employee.setEmployeeCode("EMP9100");
        employee.setFirstName("Remote");
        employee.setLastName("Tester");
        employee.setOfficialEmail("remote.tester@company.com");
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

        locationPolicy = new LocationPolicy();
        locationPolicy.setCode("TEST");
        locationPolicy.setName("Test Location Policy");
        locationPolicy = locationPolicyRepository.save(locationPolicy);
        assign(PolicyType.LOCATION, locationPolicy.getId());

        OvertimePolicy overtimePolicy = new OvertimePolicy();
        overtimePolicy.setCode("TEST");
        overtimePolicy.setName("Test Overtime Policy");
        overtimePolicy = overtimePolicyRepository.save(overtimePolicy);
        assign(PolicyType.OVERTIME, overtimePolicy.getId());

        principal = UserPrincipal.fromClaims(
                1L, employee.getId(), "EMP9100",
                Set.of("EMPLOYEE"), Set.of("ATTENDANCE_PUNCH", "ATTENDANCE_VIEW_SELF"));
    }

    private void assign(PolicyType type, Long policyId) {
        PolicyAssignment assignment = new PolicyAssignment();
        assignment.setPolicyType(type);
        assignment.setPolicyId(policyId);
        assignment.setScopeType("GLOBAL");
        policyAssignmentRepository.save(assignment);
    }

    /** An approved work-from-home request covering today. */
    private void approveWorkFromHomeToday() {
        LocalDate today = LocalDate.now();

        WorkFromHomeRequest request = new WorkFromHomeRequest();
        request.setRequestNumber("WFH-TEST-1");
        request.setEmployee(employee);
        request.setFromDate(today);
        request.setToDate(today);
        request.setTotalDays((short) 1);
        request.setReason("Plumber coming");
        request.setStatus(RequestStatus.APPROVED);
        request.setDecidedAt(Instant.now());
        request = wfhRequestRepository.save(request);

        WfhRequestDay day = new WfhRequestDay();
        day.setWfhRequest(request);
        day.setEmployeeId(employee.getId());
        day.setWfhDate(today);
        wfhRequestDayRepository.save(day);
    }

    /** A punch that states no work mode, as the browser now sends. */
    private PunchRequest from(BigDecimal latitude) {
        return new PunchRequest(latitude, OFFICE_LNG, new BigDecimal("12.5"),
                Instant.now(), null, "JUnit");
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("an approved day punched from home records work from home, not a rejection")
    void approvedAndAway() {
        approveWorkFromHomeToday();

        Attendance attendance = punchService.punchIn(principal, from(AWAY_LAT));

        assertThat(attendance.getWorkMode()).isEqualTo(WorkMode.WFH);
        assertThat(attendance.getPunchInTime()).isNotNull();
        assertThat(attendance.getPunchInLocation()).isEqualTo("Work from home");
    }

    @Test
    @DisplayName("an approved day punched at the office records the office")
    void approvedButCameInAnyway() {
        // Approval is permission to be away, not an instruction to stay away.
        // The record has to say where they were, or a manager looking at the
        // day is told something untrue.
        approveWorkFromHomeToday();

        Attendance attendance = punchService.punchIn(principal, from(OFFICE_LAT));

        assertThat(attendance.getWorkMode()).isEqualTo(WorkMode.OFFICE);
        assertThat(attendance.getPunchInLocation()).isEqualTo("Main Gate");
        assertThat(attendance.getPunchInDistanceMeters()).isNotNull();
    }

    @Test
    @DisplayName("without an approval, punching from home is still refused")
    void notApprovedAndAway() {
        // The fix must not become a way around the geofence for everybody.
        assertThatThrownBy(() -> punchService.punchIn(principal, from(AWAY_LAT)))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.OUTSIDE_GEOFENCE));

        assertThat(attendanceRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("an approved day survives a refused or unusable location")
    void approvedWithNoLocationAtAll() {
        // Somebody at home may decline the browser's location prompt, or have
        // a fix too vague to place. Neither should block a day they already
        // have permission to work remotely -- the approval has answered the
        // question the geofence exists to ask.
        approveWorkFromHomeToday();

        Attendance attendance = punchService.punchIn(principal,
                new PunchRequest(null, null, null, Instant.now(), null, "JUnit"));

        assertThat(attendance.getWorkMode()).isEqualTo(WorkMode.WFH);
    }

    @Test
    @DisplayName("punching out inherits the mode the day was punched in with")
    void punchOutKeepsTheMode() {
        approveWorkFromHomeToday();
        punchService.punchIn(principal, from(AWAY_LAT));

        Attendance attendance = punchService.punchOut(principal, from(AWAY_LAT));

        // Punch-out re-runs the geofence. If it did not inherit WFH it would
        // measure a home address against the office radius and refuse to let
        // somebody close their own day.
        assertThat(attendance.getWorkMode()).isEqualTo(WorkMode.WFH);
        assertThat(attendance.getPunchOutTime()).isNotNull();
    }

    @Test
    @DisplayName("a policy that forbids remote punching still forbids it")
    void policyCanStillRequireTheOffice() {
        locationPolicy.setAllowWfhWithoutGeofence(false);
        locationPolicyRepository.save(locationPolicy);
        approveWorkFromHomeToday();

        assertThatThrownBy(() -> punchService.punchIn(principal, from(AWAY_LAT)))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.POLICY_VIOLATION));
    }

    @Test
    @DisplayName("an explicitly stated work mode is honoured as given")
    void explicitModeWins() {
        // Resolution only fills in a mode the caller left open. A client that
        // states one -- a kiosk, an import, a future mobile app -- is obeyed.
        approveWorkFromHomeToday();

        Attendance attendance = punchService.punchIn(principal,
                new PunchRequest(null, null, null, Instant.now(), "WFH", "JUnit"));

        assertThat(attendance.getWorkMode()).isEqualTo(WorkMode.WFH);
    }

    @Test
    @DisplayName("today's status says work from home before the first punch")
    void statusAnnouncesItBeforePunching() {
        // So the punch card can show it. Without this the employee taps with
        // no idea which mode they are about to be recorded as.
        approveWorkFromHomeToday();

        var status = attendanceService.getCurrentStatus(principal);

        assertThat(status.workMode()).isEqualTo("WFH");
        assertThat(status.canPunchIn()).isTrue();
    }

    @Test
    @DisplayName("an ordinary day still reports the office before the first punch")
    void statusIsOfficeWithoutAnApproval() {
        var status = attendanceService.getCurrentStatus(principal);

        assertThat(status.workMode()).isEqualTo("OFFICE");
    }

    @Test
    @DisplayName("a pending request is not an approval")
    void pendingIsNotApproved() {
        LocalDate today = LocalDate.now();

        WorkFromHomeRequest request = new WorkFromHomeRequest();
        request.setRequestNumber("WFH-TEST-2");
        request.setEmployee(employee);
        request.setFromDate(today);
        request.setToDate(today);
        request.setTotalDays((short) 1);
        request.setReason("Awaiting a decision");
        request.setStatus(RequestStatus.PENDING);
        request = wfhRequestRepository.save(request);

        WfhRequestDay day = new WfhRequestDay();
        day.setWfhRequest(request);
        day.setEmployeeId(employee.getId());
        day.setWfhDate(today);
        wfhRequestDayRepository.save(day);

        assertThatThrownBy(() -> punchService.punchIn(principal, from(AWAY_LAT)))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.OUTSIDE_GEOFENCE));
    }
}
