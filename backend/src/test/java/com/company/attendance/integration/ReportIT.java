package com.company.attendance.integration;

import com.company.attendance.dto.request.report.ReportFilter;
import com.company.attendance.dto.response.report.ReportResult;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.Department;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeManager;
import com.company.attendance.entity.EmployeeShift;
import com.company.attendance.entity.LeaveRequest;
import com.company.attendance.entity.LeaveRequestDay;
import com.company.attendance.entity.LeaveType;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.WeeklyOff;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.DayPortion;
import com.company.attendance.enums.RelationshipType;
import com.company.attendance.enums.ReportType;
import com.company.attendance.enums.RequestStatus;
import com.company.attendance.enums.WorkMode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.DepartmentRepository;
import com.company.attendance.repository.EmployeeManagerRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.LeaveRequestDayRepository;
import com.company.attendance.repository.LeaveRequestRepository;
import com.company.attendance.repository.LeaveTypeRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.WeeklyOffRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.ReportExporter;
import com.company.attendance.service.ReportService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reports are read by people making decisions about other people, so the tests
 * here care about two things above all: that a report never shows rows the
 * reader is not entitled to, and that an export contains the same numbers as
 * the screen it was launched from.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReportIT {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired ReportService reportService;
    @Autowired ReportExporter reportExporter;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired EmployeeManagerRepository employeeManagerRepository;
    @Autowired EmployeeShiftRepository employeeShiftRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired WeeklyOffRepository weeklyOffRepository;
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

    private LocalDate workday;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        workday = lastWorkingDay();
        while (workday.getDayOfWeek() == DayOfWeek.SATURDAY
                || workday.getDayOfWeek() == DayOfWeek.SUNDAY) {
            workday = workday.minusDays(1);
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
        shift = shiftRepository.save(shift);

        weeklyOffRepository.save(weeklyOff(DayOfWeek.SATURDAY));
        weeklyOffRepository.save(weeklyOff(DayOfWeek.SUNDAY));

        casualLeave = new LeaveType();
        casualLeave.setCode("CL");
        casualLeave.setName("Casual Leave");
        casualLeave = leaveTypeRepository.save(casualLeave);

        manager = employee("EMP6100", "Team", "Lead", engineering);

        hr = UserPrincipal.fromClaims(1L, null, "hr",
                Set.of("HR"), Set.of("REPORT_VIEW_ALL", "REPORT_EXPORT"));
        teamLead = UserPrincipal.fromClaims(2L, manager.getId(), "EMP6100",
                Set.of("MANAGER"), Set.of("REPORT_VIEW_TEAM"));
    }

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

    private void recordAttendance(Employee person, LocalDate date, WorkMode mode,
                                  int late, int earlyExit, int overtime) {
        Attendance attendance = new Attendance();
        attendance.setEmployee(person);
        attendance.setAttendanceDate(date);
        attendance.setShift(shift);
        attendance.setPunchInTime(at(date, 9, 30).plusSeconds(late * 60L));
        attendance.setPunchOutTime(at(date, 18, 30));
        attendance.setWorkingMinutes(480);
        attendance.setLateMinutes(late);
        attendance.setEarlyExitMinutes(earlyExit);
        attendance.setOvertimeMinutes(overtime);
        attendance.setWorkMode(mode);
        attendance.setAttendanceStatus(
                mode == WorkMode.WFH ? AttendanceStatus.WFH : AttendanceStatus.PRESENT);
        attendanceRepository.save(attendance);
    }

    private void grantLeave(Employee person, LocalDate date) {
        LeaveRequest request = new LeaveRequest();
        request.setRequestNumber("LV-RPT-" + person.getEmployeeCode() + "-" + date);
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

    private ReportResult run(UserPrincipal principal, ReportType type) {
        return reportService.generate(principal, type,
                new ReportFilter(workday, workday, null, null, null, null, null),
                PageRequest.of(0, 100));
    }

    // -----------------------------------------------------------------
    // Scope
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a manager's report covers their team and nobody else")
    void managerReportIsScopedToTheirTeam() {
        Employee mine = report("EMP6101", "My", "Report", engineering);
        Employee theirs = employee("EMP6102", "Someone", "Else", sales);

        recordAttendance(mine, workday, WorkMode.OFFICE, 0, 0, 0);
        recordAttendance(theirs, workday, WorkMode.OFFICE, 0, 0, 0);

        ReportResult asManager = run(teamLead, ReportType.DAILY_ATTENDANCE);
        ReportResult asHr = run(hr, ReportType.DAILY_ATTENDANCE);

        assertThat(asManager.scope()).isEqualTo("TEAM");
        assertThat(asManager.rows()).hasSize(1);
        assertThat(asManager.rows().get(0).get("employeeCode")).isEqualTo("EMP6101");

        assertThat(asHr.scope()).isEqualTo("ORG");
        assertThat(asHr.rows()).hasSize(2);
    }

    @Test
    @DisplayName("an ordinary employee cannot run reports at all")
    void selfScopeIsRefused() {
        UserPrincipal ordinary = UserPrincipal.fromClaims(9L, manager.getId(), "EMP6100",
                Set.of("EMPLOYEE"), Set.of("ATTENDANCE_VIEW_SELF"));

        assertThatThrownBy(() -> run(ordinary, ReportType.DAILY_ATTENDANCE))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("managers and HR"));
    }

    @Test
    @DisplayName("a department filter narrows within scope, it never widens it")
    void filtersNeverWiden() {
        report("EMP6103", "An", "Engineer", engineering);
        employee("EMP6104", "Outside", "TheTeam", sales);

        ReportResult filtered = reportService.generate(teamLead, ReportType.MONTHLY_ATTENDANCE,
                new ReportFilter(workday, workday, null, sales.getId(), null, null, null),
                PageRequest.of(0, 100));

        // The manager has nobody in Sales, so asking for Sales returns nothing
        // rather than somebody else's team.
        assertThat(filtered.rows()).isEmpty();
    }

    // -----------------------------------------------------------------
    // The numbers
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the absentee report leaves out anyone with an approved reason")
    void absenteesExcludesLeaveAndWeeklyOffs() {
        Employee present = report("EMP6105", "Here", "Person", engineering);
        Employee onLeave = report("EMP6106", "Away", "Person", engineering);
        Employee missing = report("EMP6107", "Missing", "Person", engineering);

        recordAttendance(present, workday, WorkMode.OFFICE, 0, 0, 0);
        grantLeave(onLeave, workday);
        recordAttendance(manager, workday, WorkMode.OFFICE, 0, 0, 0);

        ReportResult absentees = run(hr, ReportType.ABSENTEES);

        assertThat(absentees.rows()).hasSize(1);
        assertThat(absentees.rows().get(0).get("employeeCode")).isEqualTo(missing.getEmployeeCode());
    }

    @Test
    @DisplayName("late arrivals lists only days that were actually late")
    void lateArrivalsAreOnlyLateDays() {
        Employee late = report("EMP6108", "Late", "Person", engineering);
        Employee punctual = report("EMP6109", "On", "Time", engineering);

        recordAttendance(late, workday, WorkMode.OFFICE, 45, 0, 0);
        recordAttendance(punctual, workday, WorkMode.OFFICE, 0, 0, 0);

        ReportResult report = run(hr, ReportType.LATE_ARRIVALS);

        assertThat(report.rows()).hasSize(1);
        assertThat(report.rows().get(0).get("employeeCode")).isEqualTo("EMP6108");
        assertThat(report.totals().get("late")).isEqualTo(45L);
    }

    @Test
    @DisplayName("working hours are averaged over days worked, not calendar days")
    void averageIgnoresDaysNotWorked() {
        Employee person = report("EMP6110", "Part", "Timer", engineering);
        recordAttendance(person, workday, WorkMode.OFFICE, 0, 0, 0);

        ReportResult report = reportService.generate(hr, ReportType.WORKING_HOURS,
                new ReportFilter(workday.minusDays(6), workday, null, null, null, null, null),
                PageRequest.of(0, 100));

        var row = report.rows().stream()
                .filter(r -> "EMP6110".equals(r.get("employeeCode")))
                .findFirst().orElseThrow();

        // One day worked, 480 minutes. Dividing by the seven-day range instead
        // would report a 68-minute average and make a normal week look alarming.
        assertThat(row.get("daysWorked")).isEqualTo(1L);
        assertThat(row.get("worked")).isEqualTo(480L);
        assertThat(row.get("average")).isEqualTo(480L);
    }

    @Test
    @DisplayName("the monthly summary counts leave as leave, not absence")
    void monthlySummarySeparatesLeaveFromAbsence() {
        Employee onLeave = report("EMP6111", "Away", "Person", engineering);
        grantLeave(onLeave, workday);
        recordAttendance(manager, workday, WorkMode.OFFICE, 0, 0, 0);

        ReportResult report = run(hr, ReportType.MONTHLY_ATTENDANCE);

        var row = report.rows().stream()
                .filter(r -> "EMP6111".equals(r.get("employeeCode")))
                .findFirst().orElseThrow();

        assertThat(row.get("leave")).isEqualTo(1L);
        assertThat(row.get("absent")).isEqualTo(0L);
    }

    // -----------------------------------------------------------------
    // Export
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an export contains every row, not just the first page")
    void exportIsNeverPaged() {
        for (int i = 0; i < 12; i++) {
            Employee person = report("EMP62" + (10 + i), "Person", String.valueOf(i), engineering);
            recordAttendance(person, workday, WorkMode.OFFICE, 0, 0, 0);
        }

        ReportResult paged = reportService.generate(hr, ReportType.DAILY_ATTENDANCE,
                new ReportFilter(workday, workday, null, null, null, null, null),
                PageRequest.of(0, 5));
        ReportResult whole = reportService.generateAll(hr, ReportType.DAILY_ATTENDANCE,
                new ReportFilter(workday, workday, null, null, null, null, null));

        assertThat(paged.rows()).hasSize(5);
        assertThat(paged.totalRows()).isEqualTo(12);
        // The export must never quietly be the first page.
        assertThat(whole.rows()).hasSize(12);
    }

    @Test
    @DisplayName("the CSV export holds the same rows the screen showed")
    void csvMatchesTheReport() {
        Employee person = report("EMP6120", "Csv", "Person", engineering);
        recordAttendance(person, workday, WorkMode.OFFICE, 0, 0, 0);

        ReportResult report = reportService.generateAll(hr, ReportType.DAILY_ATTENDANCE,
                new ReportFilter(workday, workday, null, null, null, null, null));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        reportExporter.write(report, ReportExporter.Format.CSV, IST, out);
        String csv = out.toString(StandardCharsets.UTF_8);

        List<String> lines = csv.lines().toList();
        assertThat(lines).hasSize(report.rows().size() + 1);
        assertThat(lines.get(0)).contains("Employee").contains("Worked");
        assertThat(csv).contains("EMP6120");
        // Durations read as hours and minutes for a person, not raw minutes.
        assertThat(csv).contains("8h 0m");
    }

    @Test
    @DisplayName("a value that looks like a formula cannot execute in a spreadsheet")
    void csvNeutralisesFormulaInjection() {
        Employee person = report("EMP6121", "Formula", "Person", engineering);
        // A reason field is free text, and free text reaches a spreadsheet.
        LeaveRequest request = new LeaveRequest();
        request.setRequestNumber("LV-RPT-INJECT");
        request.setEmployee(person);
        request.setLeaveType(casualLeave);
        request.setFromDate(workday);
        request.setToDate(workday);
        request.setTotalDays(BigDecimal.ONE);
        request.setReason("=1+1");
        request.setStatus(RequestStatus.APPROVED);
        leaveRequestRepository.save(request);

        ReportResult report = reportService.generateAll(hr, ReportType.LEAVES,
                new ReportFilter(workday, workday, null, null, null, null, null));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        reportExporter.write(report, ReportExporter.Format.CSV, IST, out);
        String csv = out.toString(StandardCharsets.UTF_8);

        assertThat(csv).contains("'=1+1");
        assertThat(csv).doesNotContain(",=1+1");
    }

    @Test
    @DisplayName("Excel and PDF produce files of their own format")
    void binaryExportsAreWellFormed() {
        Employee person = report("EMP6122", "Export", "Person", engineering);
        recordAttendance(person, workday, WorkMode.OFFICE, 0, 0, 0);

        ReportResult report = reportService.generateAll(hr, ReportType.DAILY_ATTENDANCE,
                new ReportFilter(workday, workday, null, null, null, null, null));

        ByteArrayOutputStream excel = new ByteArrayOutputStream();
        reportExporter.write(report, ReportExporter.Format.EXCEL, IST, excel);
        byte[] xlsx = excel.toByteArray();
        // An xlsx is a zip; "PK" is its signature.
        assertThat(xlsx).hasSizeGreaterThan(1000);
        assertThat(new String(xlsx, 0, 2, StandardCharsets.ISO_8859_1)).isEqualTo("PK");

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        reportExporter.write(report, ReportExporter.Format.PDF, IST, pdf);
        assertThat(new String(pdf.toByteArray(), 0, 5, StandardCharsets.ISO_8859_1))
                .isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("an unreasonable date range is refused rather than left to time out")
    void rangeIsCapped() {
        assertThatThrownBy(() -> reportService.generate(hr, ReportType.DAILY_ATTENDANCE,
                new ReportFilter(LocalDate.now().minusYears(3), LocalDate.now(),
                        null, null, null, null, null),
                PageRequest.of(0, 50)))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("at most"));
    }

    @Test
    @DisplayName("a report about one person insists on being told which person")
    void employeeReportRequiresAnEmployee() {
        assertThatThrownBy(() -> run(hr, ReportType.EMPLOYEE_ATTENDANCE))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(thrown.getMessage()).contains("Choose an employee"));
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
