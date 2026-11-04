package com.company.attendance.integration;

import com.company.attendance.dto.response.dashboard.AttendanceTrendResponse;
import com.company.attendance.dto.response.dashboard.DashboardSummaryResponse;
import com.company.attendance.dto.response.dashboard.TeamMemberDayResponse;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.Department;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeManager;
import com.company.attendance.entity.EmployeeShift;
import com.company.attendance.entity.Holiday;
import com.company.attendance.entity.LeaveRequest;
import com.company.attendance.entity.LeaveRequestDay;
import com.company.attendance.entity.LeaveType;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.WeeklyOff;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.DayPortion;
import com.company.attendance.enums.RelationshipType;
import com.company.attendance.enums.RequestStatus;
import com.company.attendance.enums.WorkMode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.DepartmentRepository;
import com.company.attendance.repository.EmployeeManagerRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.HolidayRepository;
import com.company.attendance.repository.LeaveRequestDayRepository;
import com.company.attendance.repository.LeaveRequestRepository;
import com.company.attendance.repository.LeaveTypeRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.WeeklyOffRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.DashboardService;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dashboard's job is to tell a manager the truth about a day.
 *
 * <p>These tests exist mostly to hold one line: a person who is away for a
 * legitimate reason is never counted as absent. Every state is seeded
 * separately so the counts cannot pass by accident.
 */
@SpringBootTest
@ActiveProfiles("test")
class DashboardIT {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired DashboardService dashboardService;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired EmployeeManagerRepository employeeManagerRepository;
    @Autowired EmployeeShiftRepository employeeShiftRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired WeeklyOffRepository weeklyOffRepository;
    @Autowired HolidayRepository holidayRepository;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired LeaveRequestRepository leaveRequestRepository;
    @Autowired LeaveRequestDayRepository leaveRequestDayRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Office office;
    private Department engineering;
    private Department sales;
    private Shift shift;
    private LeaveType casualLeave;

    private Employee manager;
    private UserPrincipal hr;
    private UserPrincipal teamLead;

    /** A weekday in the past, so every shift on it has finished. */
    private LocalDate pastWorkday;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        pastWorkday = lastWorkingDay();
        while (pastWorkday.getDayOfWeek() == DayOfWeek.SATURDAY
                || pastWorkday.getDayOfWeek() == DayOfWeek.SUNDAY) {
            pastWorkday = pastWorkday.minusDays(1);
        }

        office = new Office();
        office.setCode("HO");
        office.setName("Head Office");
        office.setTimezone("Asia/Kolkata");
        office = officeRepository.save(office);

        engineering = department("ENG", "Engineering");
        sales = department("SLS", "Sales");

        shift = new Shift();
        shift.setCode("GEN");
        shift.setName("General Shift");
        shift.setStartTime(LocalTime.of(9, 30));
        shift.setEndTime(LocalTime.of(18, 30));
        shift.setStandardWorkMinutes((short) 480);
        shift.setFullDayMinutes((short) 480);
        shift.setHalfDayMinutes((short) 240);
        shift.setMinAttendanceMinutes((short) 120);
        shift = shiftRepository.save(shift);

        weeklyOffRepository.save(weeklyOff(DayOfWeek.SATURDAY));
        weeklyOffRepository.save(weeklyOff(DayOfWeek.SUNDAY));

        casualLeave = new LeaveType();
        casualLeave.setCode("CL");
        casualLeave.setName("Casual Leave");
        casualLeave = leaveTypeRepository.save(casualLeave);

        manager = employee("EMP9100", "Team", "Lead", engineering);

        hr = UserPrincipal.fromClaims(1L, null, "hr-system",
                Set.of("HR"), Set.of("ATTENDANCE_VIEW_ALL"));
        teamLead = UserPrincipal.fromClaims(2L, manager.getId(), "EMP9100",
                Set.of("MANAGER"), Set.of("ATTENDANCE_VIEW_TEAM"));
    }

    // -----------------------------------------------------------------
    // Seeding helpers
    // -----------------------------------------------------------------

    private Department department(String code, String name) {
        Department dept = new Department();
        dept.setCode(code);
        dept.setName(name);
        return departmentRepository.save(dept);
    }

    private WeeklyOff weeklyOff(DayOfWeek day) {
        WeeklyOff rule = new WeeklyOff();
        rule.setScopeType("GLOBAL");
        rule.setDayOfWeek((byte) day.getValue());
        return rule;
    }

    private Employee employee(String code, String first, String last, Department department) {
        Employee person = new Employee();
        person.setEmployeeCode(code);
        person.setFirstName(first);
        person.setLastName(last);
        person.setOfficialEmail(code.toLowerCase() + "@company.com");
        person.setJoiningDate(LocalDate.now().minusYears(1));
        person.setOffice(office);
        person.setDepartment(department);
        person = employeeRepository.save(person);

        EmployeeShift assignment = new EmployeeShift();
        assignment.setEmployee(person);
        assignment.setShift(shift);
        assignment.setEffectiveFrom(person.getJoiningDate());
        employeeShiftRepository.save(assignment);
        return person;
    }

    /** Makes someone report to the team lead. */
    private Employee report(String code, String first, String last, Department department) {
        Employee person = employee(code, first, last, department);
        EmployeeManager reporting = new EmployeeManager();
        reporting.setEmployee(person);
        reporting.setManager(manager);
        reporting.setRelationshipType(RelationshipType.PRIMARY);
        reporting.setEffectiveFrom(LocalDate.now().minusMonths(6));
        employeeManagerRepository.save(reporting);
        return person;
    }

    private Instant at(LocalDate date, int hour, int minute) {
        return LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(IST).toInstant();
    }

    private void recordAttendance(Employee person, LocalDate date, WorkMode mode, int lateMinutes) {
        Attendance attendance = new Attendance();
        attendance.setEmployee(person);
        attendance.setAttendanceDate(date);
        attendance.setShift(shift);
        attendance.setPunchInTime(at(date, 9, 30).plusSeconds(lateMinutes * 60L));
        attendance.setPunchOutTime(at(date, 18, 30));
        attendance.setWorkingMinutes(480);
        attendance.setLateMinutes(lateMinutes);
        attendance.setWorkMode(mode);
        attendance.setAttendanceStatus(
                mode == WorkMode.WFH ? AttendanceStatus.WFH : AttendanceStatus.PRESENT);
        attendance.setPunchInLatitude(new BigDecimal("19.0759837"));
        attendance.setPunchInLongitude(new BigDecimal("72.8776559"));
        attendanceRepository.save(attendance);
    }

    private void grantLeave(Employee person, LocalDate date) {
        LeaveRequest request = new LeaveRequest();
        request.setRequestNumber("LV-TEST-" + person.getEmployeeCode() + "-" + date);
        request.setEmployee(person);
        request.setLeaveType(casualLeave);
        request.setFromDate(date);
        request.setToDate(date);
        request.setTotalDays(BigDecimal.ONE);
        request.setReason("Seeded");
        request.setStatus(RequestStatus.APPROVED);
        request = leaveRequestRepository.save(request);

        LeaveRequestDay day = new LeaveRequestDay();
        day.setLeaveRequest(request);
        day.setEmployeeId(person.getId());
        day.setLeaveDate(date);
        day.setDayPortion(DayPortion.FULL_DAY);
        day.setDeductible(true);
        leaveRequestDayRepository.save(day);
    }

    private DashboardSummaryResponse summaryFor(UserPrincipal principal, LocalDate date) {
        return dashboardService.getSummary(principal, date, null, null);
    }

    // -----------------------------------------------------------------
    // The distinction the whole screen rests on
    // -----------------------------------------------------------------

    @Test
    @DisplayName("someone on approved leave is never counted absent")
    void approvedLeaveIsNotAbsence() {
        Employee present = report("EMP9101", "Present", "Person", engineering);
        Employee onLeave = report("EMP9102", "On", "Leave", engineering);

        recordAttendance(present, pastWorkday, WorkMode.OFFICE, 0);
        grantLeave(onLeave, pastWorkday);
        // The team lead is in the org-wide population too; give them a punch so
        // the only person away is the one deliberately put on leave.
        recordAttendance(manager, pastWorkday, WorkMode.OFFICE, 0);

        DashboardSummaryResponse summary = summaryFor(hr, pastWorkday);

        assertThat(summary.onLeave()).isEqualTo(1);
        assertThat(summary.absent()).isZero();
        // The person on leave is not part of the denominator either.
        assertThat(summary.expected()).isEqualTo(2);
        assertThat(summary.present()).isEqualTo(2);
    }

    @Test
    @DisplayName("a weekly off is counted as a weekly off, not an absence")
    void weeklyOffIsNotAbsence() {
        report("EMP9103", "Weekend", "Worker", engineering);

        LocalDate saturday = pastWorkday;
        while (saturday.getDayOfWeek() != DayOfWeek.SATURDAY) {
            saturday = saturday.minusDays(1);
        }

        DashboardSummaryResponse summary = summaryFor(hr, saturday);

        assertThat(summary.weeklyOff()).isEqualTo(2);
        assertThat(summary.absent()).isZero();
        // Nobody was expected, so the rate is not a division by a phantom.
        assertThat(summary.expected()).isZero();
        assertThat(summary.attendanceRate()).isZero();
    }

    @Test
    @DisplayName("a public holiday is counted as a holiday, not an absence")
    void holidayIsNotAbsence() {
        report("EMP9104", "Holiday", "Person", engineering);

        Holiday holiday = new Holiday();
        holiday.setName("Founders Day");
        holiday.setHolidayDate(pastWorkday);
        holidayRepository.save(holiday);

        DashboardSummaryResponse summary = summaryFor(hr, pastWorkday);

        assertThat(summary.holiday()).isEqualTo(2);
        assertThat(summary.absent()).isZero();
        assertThat(summary.expected()).isZero();
    }

    @Test
    @DisplayName("a working day with no punch and a finished shift is an absence")
    void unexplainedAbsenceIsCounted() {
        report("EMP9105", "Missing", "Person", engineering);

        DashboardSummaryResponse summary = summaryFor(hr, pastWorkday);

        assertThat(summary.absent()).isEqualTo(2);
        assertThat(summary.notYetIn()).isZero();
        assertThat(summary.expected()).isEqualTo(2);
    }

    @Test
    @DisplayName("today's no-shows are 'not yet in' while their shift is still running")
    void todaysNoShowIsNotYetAnAbsence() {
        report("EMP9106", "Arriving", "Later", engineering);

        LocalDate today = LocalDate.now();
        if (today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return;
        }
        // Only meaningful before the shift ends; after 18:30 IST they really are absent.
        if (Instant.now().isAfter(at(today, 18, 30))) {
            return;
        }

        DashboardSummaryResponse summary = summaryFor(hr, today);

        assertThat(summary.notYetIn()).isEqualTo(2);
        assertThat(summary.absent()).isZero();
    }

    @Test
    @DisplayName("someone who worked a holiday is present, not on holiday")
    void workingAHolidayCountsAsPresent() {
        Employee worker = report("EMP9107", "Holiday", "Worker", engineering);

        Holiday holiday = new Holiday();
        holiday.setName("Founders Day");
        holiday.setHolidayDate(pastWorkday);
        holidayRepository.save(holiday);

        recordAttendance(worker, pastWorkday, WorkMode.OFFICE, 0);

        DashboardSummaryResponse summary = summaryFor(hr, pastWorkday);

        assertThat(summary.present()).isEqualTo(1);
        assertThat(summary.holiday()).isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // Counting
    // -----------------------------------------------------------------

    @Test
    @DisplayName("work from home is counted apart from office attendance but still counts as working")
    void workFromHomeIsItsOwnCategory() {
        Employee inOffice = report("EMP9108", "In", "Office", engineering);
        Employee atHome = report("EMP9109", "At", "Home", engineering);

        recordAttendance(inOffice, pastWorkday, WorkMode.OFFICE, 0);
        recordAttendance(atHome, pastWorkday, WorkMode.WFH, 0);

        DashboardSummaryResponse summary = summaryFor(hr, pastWorkday);

        assertThat(summary.present()).isEqualTo(1);
        assertThat(summary.workingFromHome()).isEqualTo(1);
        // Three expected: the two above plus the team lead, who did not punch.
        assertThat(summary.expected()).isEqualTo(3);
        assertThat(summary.attendanceRate()).isEqualTo(66.7);
    }

    @Test
    @DisplayName("the rate is measured against people who were meant to work")
    void rateIgnoresPeopleWhoWereNeverExpected() {
        Employee working = report("EMP9110", "Working", "Person", engineering);
        Employee onLeave = report("EMP9111", "Away", "Person", engineering);

        recordAttendance(working, pastWorkday, WorkMode.OFFICE, 0);
        recordAttendance(manager, pastWorkday, WorkMode.OFFICE, 0);
        grantLeave(onLeave, pastWorkday);

        DashboardSummaryResponse summary = summaryFor(hr, pastWorkday);

        // Two of two expected turned up. Counting the person on leave in the
        // denominator would report 66.7% and invent a problem.
        assertThat(summary.expected()).isEqualTo(2);
        assertThat(summary.attendanceRate()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("late arrivals are counted among the present, not against them")
    void lateArrivalsAreStillPresent() {
        Employee late = report("EMP9112", "Late", "Person", engineering);
        recordAttendance(late, pastWorkday, WorkMode.OFFICE, 45);

        DashboardSummaryResponse summary = summaryFor(hr, pastWorkday);

        assertThat(summary.present()).isEqualTo(1);
        assertThat(summary.late()).isEqualTo(1);
    }

    @Test
    @DisplayName("the department breakdown splits the same population")
    void departmentBreakdownAddsUp() {
        Employee engineer = report("EMP9113", "An", "Engineer", engineering);
        Employee seller = report("EMP9114", "A", "Seller", sales);

        recordAttendance(engineer, pastWorkday, WorkMode.OFFICE, 0);
        grantLeave(seller, pastWorkday);

        DashboardSummaryResponse summary = summaryFor(hr, pastWorkday);

        assertThat(summary.byDepartment()).hasSize(2);
        assertThat(summary.byDepartment().stream().mapToInt(
                DashboardSummaryResponse.GroupCount::expected).sum())
                .isEqualTo(summary.expected());

        var salesRow = summary.byDepartment().stream()
                .filter(group -> group.name().equals("Sales")).findFirst().orElseThrow();
        assertThat(salesRow.onLeave()).isEqualTo(1);
        assertThat(salesRow.absent()).isZero();
    }

    // -----------------------------------------------------------------
    // Scope
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a manager sees their own team and not the rest of the company")
    void managerScopeIsTheirTeam() {
        report("EMP9115", "My", "Report", engineering);
        // Reports to nobody, so outside the team lead's scope entirely.
        employee("EMP9116", "Someone", "Else", sales);

        DashboardSummaryResponse asManager = summaryFor(teamLead, pastWorkday);
        DashboardSummaryResponse asHr = summaryFor(hr, pastWorkday);

        assertThat(asManager.scope()).isEqualTo("TEAM");
        assertThat(asManager.headcount()).isEqualTo(1);
        assertThat(asHr.scope()).isEqualTo("ORG");
        assertThat(asHr.headcount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a manager is not counted inside their own team roll-up")
    void managerIsNotInTheirOwnRollUp() {
        report("EMP9117", "My", "Report", engineering);

        DashboardSummaryResponse asManager = summaryFor(teamLead, pastWorkday);

        assertThat(asManager.headcount()).isEqualTo(1);
        assertThat(dashboardService.getRoster(teamLead, pastWorkday, null, null, null, null,
                        PageRequest.of(0, 20)).content())
                .extracting(TeamMemberDayResponse::employeeCode)
                .doesNotContain("EMP9100");
    }

    @Test
    @DisplayName("an ordinary employee cannot open the team view at all")
    void selfScopeIsRefused() {
        Employee ordinary = report("EMP9118", "Ordinary", "Employee", engineering);
        UserPrincipal principal = UserPrincipal.fromClaims(9L, ordinary.getId(), "EMP9118",
                Set.of("EMPLOYEE"), Set.of("ATTENDANCE_VIEW_SELF"));

        assertThatThrownBy(() -> summaryFor(principal, pastWorkday))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("managers and HR"));
    }

    @Test
    @DisplayName("a department filter narrows what is permitted, it never widens it")
    void filtersNarrowWithinScope() {
        report("EMP9119", "An", "Engineer", engineering);
        employee("EMP9120", "Outside", "TheTeam", sales);

        // The team lead asks for Sales, where they have no reports at all.
        DashboardSummaryResponse filtered =
                dashboardService.getSummary(teamLead, pastWorkday, sales.getId(), null);

        assertThat(filtered.headcount()).isZero();
    }

    // -----------------------------------------------------------------
    // Roster
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the roster leads with the people who need attention")
    void rosterSortsByUrgency() {
        Employee present = report("EMP9121", "Aaa", "Present", engineering);
        report("EMP9122", "Zzz", "Absent", engineering);

        recordAttendance(present, pastWorkday, WorkMode.OFFICE, 0);

        var roster = dashboardService.getRoster(hr, pastWorkday, null, null, null, null,
                PageRequest.of(0, 20));

        // Absent first despite sorting last alphabetically.
        assertThat(roster.content().get(0).dayState()).isEqualTo("ABSENT");
    }

    @Test
    @DisplayName("the roster can be filtered to one state")
    void rosterFiltersByState() {
        Employee present = report("EMP9123", "Here", "Person", engineering);
        Employee onLeave = report("EMP9124", "Away", "Person", engineering);

        recordAttendance(present, pastWorkday, WorkMode.OFFICE, 0);
        grantLeave(onLeave, pastWorkday);

        var leaveOnly = dashboardService.getRoster(hr, pastWorkday, "ON_LEAVE", null, null, null,
                PageRequest.of(0, 20));

        assertThat(leaveOnly.content()).hasSize(1);
        assertThat(leaveOnly.content().get(0).employeeCode()).isEqualTo("EMP9124");
        // The leave type is named, so the manager does not have to go and look.
        assertThat(leaveOnly.content().get(0).leaveTypeName()).isEqualTo("Casual Leave");
    }

    @Test
    @DisplayName("every roster row names the day it describes, punch or no punch")
    void rosterRowsAlwaysCarryTheirDate() {
        report("EMP9125", "No", "Punch", engineering);

        var roster = dashboardService.getRoster(hr, pastWorkday, null, null, null, null,
                PageRequest.of(0, 20));

        assertThat(roster.content()).isNotEmpty();
        assertThat(roster.content()).allSatisfy(
                row -> assertThat(row.date()).isEqualTo(pastWorkday));
    }

    @Test
    @DisplayName("an unknown day state is refused rather than silently ignored")
    void unknownStateIsRefused() {
        assertThatThrownBy(() -> dashboardService.getRoster(hr, pastWorkday, "SLACKING",
                null, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("Unknown day state"));
    }

    // -----------------------------------------------------------------
    // Trend
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the trend marks non-working days rather than dropping them")
    void trendKeepsNonWorkingDays() {
        report("EMP9126", "Trend", "Person", engineering);

        LocalDate to = pastWorkday;
        LocalDate from = to.minusDays(13);

        AttendanceTrendResponse trend = dashboardService.getTrend(hr, from, to);

        assertThat(trend.points()).hasSize(14);
        // Two weekends in a fortnight, so at least four non-working days.
        assertThat(trend.points().stream().filter(point -> !point.working()).count())
                .isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("the trend counts a day's attendance against that day's expectation")
    void trendCountsPerDay() {
        Employee person = report("EMP9127", "Trend", "Person", engineering);
        recordAttendance(person, pastWorkday, WorkMode.OFFICE, 0);

        AttendanceTrendResponse trend = dashboardService.getTrend(hr, pastWorkday, pastWorkday);

        assertThat(trend.points()).hasSize(1);
        var point = trend.points().get(0);
        assertThat(point.working()).isTrue();
        assertThat(point.present()).isEqualTo(1);
        assertThat(point.expected()).isEqualTo(2);
        assertThat(point.absent()).isEqualTo(1);
    }

    @Test
    @DisplayName("an over-long trend is refused rather than quietly truncated")
    void trendRangeIsCapped() {
        assertThatThrownBy(() -> dashboardService.getTrend(hr,
                LocalDate.now().minusYears(1), LocalDate.now()))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("at most"));
    }

    @Test
    @DisplayName("a backwards range is refused")
    void backwardsRangeIsRefused() {
        assertThatThrownBy(() -> dashboardService.getTrend(hr,
                LocalDate.now(), LocalDate.now().minusDays(7)))
                .isInstanceOf(BusinessException.class);
    }

    // -----------------------------------------------------------------
    // The employment window
    //
    // Found by looking at a real chart: every new joiner was reported absent
    // for the weeks before they started, which on a young system paints the
    // whole trend red.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("someone who has not started yet is not absent, and is not on the books")
    void futureJoinerIsNotCounted() {
        Employee starter = report("EMP9128", "Not", "Started", engineering);
        starter.setJoiningDate(LocalDate.now().plusMonths(1));
        employeeRepository.save(starter);

        recordAttendance(manager, pastWorkday, WorkMode.OFFICE, 0);

        DashboardSummaryResponse summary = summaryFor(hr, pastWorkday);

        assertThat(summary.absent()).isZero();
        assertThat(summary.expected()).isEqualTo(1);
        // They are not in the day's headcount either, so the numbers add up.
        assertThat(summary.headcount()).isEqualTo(1);
    }

    @Test
    @DisplayName("someone who has left is not absent for the days after they left")
    void leaverIsNotCountedAfterTheirExit() {
        Employee leaver = report("EMP9129", "Already", "Gone", engineering);
        leaver.setExitDate(pastWorkday.minusDays(1));
        employeeRepository.save(leaver);

        recordAttendance(manager, pastWorkday, WorkMode.OFFICE, 0);

        DashboardSummaryResponse summary = summaryFor(hr, pastWorkday);

        assertThat(summary.absent()).isZero();
        assertThat(summary.headcount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the trend does not count anyone before their joining date")
    void trendRespectsTheJoiningDate() {
        Employee joiner = report("EMP9130", "Recent", "Joiner", engineering);
        joiner.setJoiningDate(pastWorkday);
        employeeRepository.save(joiner);

        LocalDate from = pastWorkday.minusDays(6);
        AttendanceTrendResponse trend = dashboardService.getTrend(hr, from, pastWorkday);

        var beforeTheyJoined = trend.points().stream()
                .filter(point -> point.date().isBefore(pastWorkday))
                .filter(AttendanceTrendResponse.Point::working)
                .toList();

        // The team lead is still expected on those days; the joiner is not.
        assertThat(beforeTheyJoined).isNotEmpty();
        assertThat(beforeTheyJoined).allSatisfy(
                point -> assertThat(point.expected()).isEqualTo(1));

        var theDayTheyJoined = trend.points().stream()
                .filter(point -> point.date().equals(pastWorkday))
                .findFirst().orElseThrow();
        assertThat(theDayTheyJoined.expected()).isEqualTo(2);
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
