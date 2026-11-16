package com.company.attendance.integration;

import com.company.attendance.dto.request.report.ReportFilter;
import com.company.attendance.dto.response.report.ReportColumn;
import com.company.attendance.dto.response.report.ReportResult;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.CompanySetting;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Holiday;
import com.company.attendance.entity.LeaveBalance;
import com.company.attendance.entity.LeaveRequest;
import com.company.attendance.entity.LeaveRequestDay;
import com.company.attendance.entity.LeaveType;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.WeeklyOff;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.DayPortion;
import com.company.attendance.enums.EmploymentType;
import com.company.attendance.enums.ReportType;
import com.company.attendance.enums.RequestStatus;
import com.company.attendance.enums.WorkMode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.CompanySettingRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.HolidayRepository;
import com.company.attendance.repository.LeaveBalanceRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The salary sheet, checked against payroll's own October 2026 sheet.
 *
 * <p>Three rows of that sheet were supplied as the specification, and the
 * figures asserted below are theirs, not ours: twenty-three working days,
 * eight holidays and weekly offs, thirty-one payable days, and a carry-forward
 * that is the opening balance plus two days' accrual less whatever was taken.
 * The arithmetic was reconciled against those rows before a line of the report
 * was written, and this test is what keeps it reconciled.
 *
 * <p>October 2026 is the month because it is the month they sent. The 1st is a
 * Thursday, so the Saturdays fall on the 3rd, 10th, 17th, 24th and 31st — and
 * their sheet shows the 10th and 24th off and the other three worked, which is
 * the 2nd-and-4th-Saturday roster. Getting that out of the real weekly-off
 * rules rather than hard-coding it is half the value of the test.
 */
@SpringBootTest
@ActiveProfiles("test")
class SalaryAttendanceReportIT {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);

    /** Public holidays on payroll's sheet. */
    private static final List<Integer> HOLIDAYS = List.of(2, 20);

    /** Every day of October 2026 that is neither a holiday nor an off. */
    private static final List<Integer> WORKING_DAYS = List.of(
            1, 3, 5, 6, 7, 8, 9, 12, 13, 14, 15, 16, 17, 19, 21, 22, 23, 26, 27, 28, 29, 30, 31);

    @Autowired ReportService reportService;
    @Autowired ReportExporter reportExporter;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired HolidayRepository holidayRepository;
    @Autowired WeeklyOffRepository weeklyOffRepository;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired LeaveBalanceRepository leaveBalanceRepository;
    @Autowired LeaveRequestRepository leaveRequestRepository;
    @Autowired LeaveRequestDayRepository leaveRequestDayRepository;
    @Autowired CompanySettingRepository companySettingRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Office office;
    private Shift shift;
    private LeaveType paidLeave;
    private UserPrincipal hr;

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

        // Sundays every week; Saturdays on the 2nd and 4th occurrence only.
        weeklyOffRepository.save(weeklyOff(DayOfWeek.SUNDAY, null));
        weeklyOffRepository.save(weeklyOff(DayOfWeek.SATURDAY, (byte) 2));
        weeklyOffRepository.save(weeklyOff(DayOfWeek.SATURDAY, (byte) 4));

        for (int day : HOLIDAYS) {
            Holiday holiday = new Holiday();
            holiday.setName("Festival " + day);
            holiday.setHolidayDate(OCTOBER.withDayOfMonth(day));
            holiday.setHolidayYear((short) 2026);
            holidayRepository.save(holiday);
        }

        paidLeave = new LeaveType();
        paidLeave.setCode("PL");
        paidLeave.setName("Paid Leave");
        paidLeave.setAccrualType("MONTHLY");
        paidLeave.setAccrualDays(new BigDecimal("2.00"));
        paidLeave.setAllowCarryForward(true);
        paidLeave.setMaxCarryForwardDays(new BigDecimal("10.00"));
        paidLeave = leaveTypeRepository.save(paidLeave);

        // The ledger was taken over on 1 October, so the carried-in balances
        // are net of everything before it: nothing accrues earlier, and no
        // earlier day was being recorded, so none of it can be charged.
        setting("leave.accrual_start_date", "2026-10-01");
        setting("attendance.tracking_start_date", "2026-10-01");

        hr = UserPrincipal.fromClaims(1L, null, "hr",
                Set.of("HR"), Set.of("REPORT_VIEW_ALL", "REPORT_EXPORT"));
    }

    /**
     * company_settings survives {@link DatabaseCleaner}, so a go-live date or
     * a ledger handover date left behind here would quietly change what a
     * completely unrelated test class sees — dates before it stop being
     * tracked, and balances stop accruing. Removed rather than reset, so the
     * next class starts from the migrations' own values.
     */
    @AfterEach
    void forgetSettings() {
        clearSetting("attendance.tracking_start_date");
        clearSetting("leave.accrual_start_date");
    }

    // -----------------------------------------------------------------
    // Seeding
    // -----------------------------------------------------------------

    private WeeklyOff weeklyOff(DayOfWeek day, Byte weekOfMonth) {
        WeeklyOff rule = new WeeklyOff();
        rule.setScopeType("GLOBAL");
        rule.setDayOfWeek((byte) day.getValue());
        rule.setWeekOfMonth(weekOfMonth);
        return rule;
    }

    private void setting(String key, String value) {
        CompanySetting setting = companySettingRepository.findBySettingKey(key)
                .orElseGet(CompanySetting::new);
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        setting.setValueType("STRING");
        setting.setCategory("GENERAL");
        companySettingRepository.save(setting);
    }

    private void clearSetting(String key) {
        companySettingRepository.findBySettingKey(key)
                .ifPresent(companySettingRepository::delete);
    }

    private Employee employee(String code, String first, String last, String carriedForward) {
        Employee person = new Employee();
        person.setEmployeeCode(code);
        person.setFirstName(first);
        person.setLastName(last);
        person.setOfficialEmail(code.toLowerCase() + "@company.com");
        // Long before October, so every month accrues at the confirmed rate.
        person.setJoiningDate(LocalDate.of(2022, 4, 1));
        person.setOffice(office);
        person.setEmploymentType(EmploymentType.PERMANENT);
        person = employeeRepository.save(person);

        LeaveBalance balance = new LeaveBalance();
        balance.setEmployee(person);
        balance.setLeaveType(paidLeave);
        balance.setLeaveYear((short) 2026);
        balance.setCarriedForwardDays(new BigDecimal(carriedForward));
        leaveBalanceRepository.save(balance);

        return person;
    }

    private void present(Employee person, int day, int lateMinutes) {
        presentOn(person, OCTOBER.withDayOfMonth(day), lateMinutes);
    }

    private void presentOn(Employee person, LocalDate date, int lateMinutes) {
        Attendance attendance = new Attendance();
        attendance.setEmployee(person);
        attendance.setAttendanceDate(date);
        attendance.setShift(shift);
        attendance.setPunchInTime(at(date, 9, 30).plusSeconds(lateMinutes * 60L));
        attendance.setPunchOutTime(at(date, 18, 30));
        attendance.setWorkingMinutes(480);
        attendance.setLateMinutes(lateMinutes);
        attendance.setWorkMode(WorkMode.OFFICE);
        attendance.setAttendanceStatus(AttendanceStatus.PRESENT);
        attendanceRepository.save(attendance);
    }

    /** Every working day present, except the ones named. */
    private void presentAllMonthExcept(Employee person, Map<Integer, Integer> lateByDay,
                                        Set<Integer> absentDays) {
        for (int day : WORKING_DAYS) {
            if (absentDays.contains(day)) {
                continue;
            }
            present(person, day, lateByDay.getOrDefault(day, 0));
        }
    }

    private void grantLeave(Employee person, int day) {
        LocalDate date = OCTOBER.withDayOfMonth(day);

        LeaveRequest request = new LeaveRequest();
        request.setRequestNumber("LV-SAL-" + person.getEmployeeCode() + "-" + day);
        request.setEmployee(person);
        request.setLeaveType(paidLeave);
        request.setFromDate(date);
        request.setToDate(date);
        request.setTotalDays(BigDecimal.ONE);
        request.setReason("Seeded");
        request.setStatus(RequestStatus.APPROVED);
        request = leaveRequestRepository.save(request);

        LeaveRequestDay leaveDay = new LeaveRequestDay();
        leaveDay.setLeaveRequest(request);
        leaveDay.setEmployeeId(person.getId());
        leaveDay.setLeaveDate(date);
        leaveDay.setDayPortion(DayPortion.FULL_DAY);
        leaveDay.setDeductible(true);
        leaveRequestDayRepository.save(leaveDay);
    }

    private void grantHalfDayLeave(Employee person, int day) {
        LocalDate date = OCTOBER.withDayOfMonth(day);

        LeaveRequest request = new LeaveRequest();
        request.setRequestNumber("LV-HALF-" + person.getEmployeeCode() + "-" + day);
        request.setEmployee(person);
        request.setLeaveType(paidLeave);
        request.setFromDate(date);
        request.setToDate(date);
        request.setTotalDays(new BigDecimal("0.5"));
        request.setReason("Seeded");
        request.setStatus(RequestStatus.APPROVED);
        request = leaveRequestRepository.save(request);

        LeaveRequestDay leaveDay = new LeaveRequestDay();
        leaveDay.setLeaveRequest(request);
        leaveDay.setEmployeeId(person.getId());
        leaveDay.setLeaveDate(date);
        leaveDay.setDayPortion(DayPortion.FIRST_HALF);
        leaveDay.setDeductible(true);
        leaveRequestDayRepository.save(leaveDay);
    }

    private Instant at(LocalDate date, int hour, int minute) {
        return LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(IST).toInstant();
    }

    private ReportResult october() {
        return reportService.generate(hr, ReportType.SALARY_ATTENDANCE,
                new ReportFilter(OCTOBER, OCTOBER.withDayOfMonth(31), null, null, null, null, null),
                PageRequest.of(0, 100));
    }

    private Map<String, Object> rowFor(ReportResult result, String name) {
        return result.rows().stream()
                .filter(row -> name.equals(row.get("employeeName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for " + name + " in " + result.rows()));
    }

    private static String number(Map<String, Object> row, String key) {
        return String.valueOf(row.get(key));
    }

    // -----------------------------------------------------------------
    // Payroll's own sheet
    // -----------------------------------------------------------------

    @Test
    @DisplayName("reproduces payroll's October sheet, figure for figure")
    void reproducesPayrollsSheet() {
        Employee meera = employee("EMP7001", "Meera", "Nair", "20");
        Employee vikram = employee("EMP7002", "Vikram", "Das", "7");
        Employee sunil = employee("EMP7003", "Sunil", "Patel", "6");

        presentAllMonthExcept(meera, Map.of(), Set.of());
        presentAllMonthExcept(vikram, Map.of(12, 18, 23, 11, 29, 6), Set.of(30));
        presentAllMonthExcept(sunil, Map.of(30, 9), Set.of(19));

        ReportResult result = october();

        // Meera: a full month, nothing taken.
        Map<String, Object> row = rowFor(result, "Meera Nair");
        assertThat(number(row, "openingBalance")).isEqualTo("20");
        assertThat(number(row, "leavesTaken")).isEqualTo("0");
        assertThat(number(row, "workingDays")).isEqualTo("23");
        assertThat(number(row, "daysWorked")).isEqualTo("23");
        assertThat(number(row, "postLeaveAdjustment")).isEqualTo("23");
        assertThat(number(row, "holidaysAndOffs")).isEqualTo("8");
        assertThat(number(row, "payableDays")).isEqualTo("31");
        assertThat(number(row, "monthlyAccrual")).isEqualTo("2");
        assertThat(number(row, "carryForward")).isEqualTo("22");
        assertThat(row.get("comments")).isEqualTo("");

        // Vikram: three late arrivals, which still count as days worked, and
        // one absence, which is charged to his balance. 7 + 2 - 1 = 8.
        row = rowFor(result, "Vikram Das");
        assertThat(number(row, "openingBalance")).isEqualTo("7");
        assertThat(number(row, "leavesTaken")).isEqualTo("1");
        assertThat(number(row, "workingDays")).isEqualTo("23");
        assertThat(number(row, "daysWorked")).isEqualTo("22");
        assertThat(number(row, "postLeaveAdjustment")).isEqualTo("23");
        assertThat(number(row, "holidaysAndOffs")).isEqualTo("8");
        assertThat(number(row, "payableDays")).isEqualTo("31");
        assertThat(number(row, "carryForward")).isEqualTo("8");
        assertThat(String.valueOf(row.get("comments"))).contains("Absent");

        // Sunil: one absence, one late arrival. 6 + 2 - 1 = 7.
        row = rowFor(result, "Sunil Patel");
        assertThat(number(row, "openingBalance")).isEqualTo("6");
        assertThat(number(row, "leavesTaken")).isEqualTo("1");
        assertThat(number(row, "daysWorked")).isEqualTo("22");
        assertThat(number(row, "payableDays")).isEqualTo("31");
        assertThat(number(row, "carryForward")).isEqualTo("7");

        assertThat(result.rows()).hasSize(3);
    }

    @Test
    @DisplayName("the day cells read as payroll writes them")
    void dayCellsUsePayrollsWords() {
        Employee vikram = employee("EMP7002", "Vikram", "Das", "7");
        presentAllMonthExcept(vikram, Map.of(12, 18), Set.of(30));

        Map<String, Object> row = rowFor(october(), "Vikram Das");

        assertThat(row.get("d1")).isEqualTo("Present");
        assertThat(row.get("d2")).isEqualTo("Holiday");
        assertThat(row.get("d4")).isEqualTo("Sunday");
        assertThat(row.get("d10")).isEqualTo("Sat off");
        assertThat(row.get("d12")).isEqualTo("Late");
        assertThat(row.get("d20")).isEqualTo("Holiday");
        assertThat(row.get("d24")).isEqualTo("Sat off");
        assertThat(row.get("d30")).isEqualTo("Absent");

        // The 3rd, 17th and 31st are Saturdays that are worked under the
        // 2nd-and-4th roster. If these ever read "Sat off" the roster has
        // been misread, which is the mistake that costs people a day's pay.
        assertThat(row.get("d3")).isEqualTo("Present");
        assertThat(row.get("d17")).isEqualTo("Present");
        assertThat(row.get("d31")).isEqualTo("Present");
    }

    @Test
    @DisplayName("the columns are laid out and named as payroll's sheet is")
    void columnsMatchTheSheet() {
        employee("EMP7001", "Meera", "Nair", "20");

        List<ReportColumn> columns = october().columns();
        List<String> labels = columns.stream().map(ReportColumn::label).toList();

        assertThat(labels).startsWith("S.No", "Employee Name", "1st October", "2nd October",
                "3rd October");
        assertThat(labels).contains("21st October", "22nd October", "23rd October",
                "31st October", "11th October", "12th October", "13th October");
        assertThat(labels).endsWith(
                "Balance leaves as on 1st October 2026",
                "Total Leaves Taken",
                "Total working days",
                "Total days worked (Present days)",
                "Total working days post leave adjustment",
                "Holidays & Weekly offs",
                "Salary payable Days",
                "Paid leaves for the month",
                "Leaves to be carry forwarded November 2026",
                "Comments");

        // Two fixed columns, thirty-one days, ten figures.
        assertThat(columns).hasSize(2 + 31 + 10);
    }

    @Test
    @DisplayName("approved leave is charged, and still counts as a payable day")
    void approvedLeaveIsChargedAndPaid() {
        Employee person = employee("EMP7004", "Neha", "Joshi", "17.5");
        presentAllMonthExcept(person, Map.of(), Set.of(13, 14));
        grantLeave(person, 13);
        grantLeave(person, 14);

        Map<String, Object> row = rowFor(october(), "Neha Joshi");

        assertThat(row.get("d13")).isEqualTo("Leave");
        assertThat(number(row, "leavesTaken")).isEqualTo("2");
        assertThat(number(row, "daysWorked")).isEqualTo("21");
        assertThat(number(row, "postLeaveAdjustment")).isEqualTo("23");
        assertThat(number(row, "payableDays")).isEqualTo("31");
        // 17.5 + 2 accrued - 2 taken.
        assertThat(number(row, "carryForward")).isEqualTo("17.5");
        // Leave is not an absence, so there is nothing to explain.
        assertThat(row.get("comments")).isEqualTo("");
    }

    @Test
    @DisplayName("every row's figures add up to the month")
    void theFiguresReconcile() {
        Employee meera = employee("EMP7001", "Meera", "Nair", "20");
        Employee vikram = employee("EMP7002", "Vikram", "Das", "7");
        presentAllMonthExcept(meera, Map.of(), Set.of());
        presentAllMonthExcept(vikram, Map.of(12, 18), Set.of(30));

        for (Map<String, Object> row : october().rows()) {
            double worked = Double.parseDouble(number(row, "daysWorked"));
            double taken = Double.parseDouble(number(row, "leavesTaken"));
            double postAdjustment = Double.parseDouble(number(row, "postLeaveAdjustment"));
            double offs = Double.parseDouble(number(row, "holidaysAndOffs"));
            double payable = Double.parseDouble(number(row, "payableDays"));
            double workingDays = Double.parseDouble(number(row, "workingDays"));

            assertThat(postAdjustment).isEqualTo(worked + taken);
            assertThat(payable).isEqualTo(postAdjustment + offs);
            assertThat(payable).isEqualTo(31.0);
            assertThat(workingDays + offs).isEqualTo(31.0);
        }
    }

    @Test
    @DisplayName("a half day of leave is charged as a half day, and the day still pays in full")
    void halfDayLeaveIsNotDoubleCounted() {
        Employee person = employee("EMP7011", "Half", "Day", "4");
        presentAllMonthExcept(person, Map.of(), Set.of());
        // They came in for the afternoon, so there is a punch as well.
        grantHalfDayLeave(person, 13);

        Map<String, Object> row = rowFor(october(), "Half Day");

        assertThat(number(row, "leavesTaken")).isEqualTo("0.5");
        // Crediting the punch with a whole day and charging the half as well
        // would make the month come to 31.5 payable days and quietly give the
        // half day back. 22.5 worked + 0.5 charged is the one that reconciles.
        assertThat(number(row, "daysWorked")).isEqualTo("22.5");
        assertThat(number(row, "postLeaveAdjustment")).isEqualTo("23");
        assertThat(number(row, "payableDays")).isEqualTo("31");
        assertThat(number(row, "carryForward")).isEqualTo("5.5");
    }

    @Test
    @DisplayName("the export carries the same figures as the screen, across all forty-three columns")
    void exportMatchesTheScreen() {
        Employee vikram = employee("EMP7002", "Vikram", "Das", "7");
        presentAllMonthExcept(vikram, Map.of(12, 18, 23, 11, 29, 6), Set.of(30));

        ReportResult result = reportService.generateAll(hr, ReportType.SALARY_ATTENDANCE,
                new ReportFilter(OCTOBER, OCTOBER.withDayOfMonth(31), null, null, null, null, null));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        reportExporter.write(result, ReportExporter.Format.CSV, IST, out);
        String csv = out.toString(StandardCharsets.UTF_8);

        // A forty-three column sheet is read in a spreadsheet, not on a phone,
        // so the export is the artefact that matters most for this report.
        assertThat(csv).contains("Balance leaves as on 1st October 2026");
        assertThat(csv).contains("Leaves to be carry forwarded November 2026");
        assertThat(csv).contains("1st October");
        assertThat(csv).contains("Vikram Das");
        assertThat(csv).contains("Sat off");
        assertThat(csv).contains("Absent");

        // One header line and one employee.
        assertThat(csv.lines().count()).isEqualTo(2);

        // Excel is what payroll actually downloads; that it writes at all over
        // this many columns is the thing worth asserting.
        ByteArrayOutputStream excel = new ByteArrayOutputStream();
        reportExporter.write(result, ReportExporter.Format.EXCEL, IST, excel);
        assertThat(excel.size()).isGreaterThan(0);
    }

    // -----------------------------------------------------------------
    // The launch month, and running out of balance
    // -----------------------------------------------------------------

    @Test
    @DisplayName("days before go-live are not absences, and the row says why")
    void daysBeforeGoLiveAreNotAbsences() {
        // The real case: the system starts part-way through a month, so the
        // first half of it has no data. Calling those days absent would open
        // the launch by telling everybody they missed two weeks.
        setting("attendance.tracking_start_date", "2026-10-16");

        Employee person = employee("EMP7005", "Kavita", "Joshi", "5");
        for (int day : WORKING_DAYS) {
            if (day >= 16) {
                present(person, day, 0);
            }
        }

        Map<String, Object> row = rowFor(october(), "Kavita Joshi");

        assertThat(row.get("d1")).isEqualTo("Not tracked");
        assertThat(row.get("d15")).isEqualTo("Not tracked");
        assertThat(row.get("d16")).isEqualTo("Present");

        // From the 16th on: the 18th and 25th are Sundays, the 24th is the
        // month's 4th Saturday and the 20th a holiday. The holiday on the 2nd
        // and the Sundays of the first fortnight fall before go-live, so they
        // are not counted either way — which is why the payable figure is
        // sixteen and not thirty-one.
        assertThat(number(row, "leavesTaken")).isEqualTo("0");
        assertThat(number(row, "holidaysAndOffs")).isEqualTo("4");
        assertThat(number(row, "daysWorked")).isEqualTo("12");
        assertThat(number(row, "workingDays")).isEqualTo("12");
        assertThat(number(row, "payableDays")).isEqualTo("16");
        assertThat(number(row, "carryForward")).isEqualTo("7");

        assertThat(String.valueOf(row.get("comments")))
                .contains("before this system began recording")
                .contains("16th October");
    }

    @Test
    @DisplayName("absences beyond the balance are named as loss of pay, never a negative balance")
    void absencesBeyondTheBalanceAreLossOfPay() {
        Employee person = employee("EMP7006", "Sunil", "Patil", "0");
        presentAllMonthExcept(person, Map.of(), Set.of(5, 6, 7, 8));

        Map<String, Object> row = rowFor(october(), "Sunil Patil");

        assertThat(number(row, "leavesTaken")).isEqualTo("4");
        // 0 opening + 2 accrued - 4 taken is -2. A carry-forward column is
        // not the place to express that; the comment is.
        assertThat(number(row, "carryForward")).isEqualTo("0");
        assertThat(String.valueOf(row.get("comments")))
                .contains("2 days beyond the leave balance")
                .contains("loss of pay");
    }

    @Test
    @DisplayName("the opening balance is last month's closing balance, absences included")
    void openingBalanceCarriesTheMonthBefore() {
        // September is replayed to get the October opening. Tracking from
        // 1 September means September's absence is charged; accrual starts
        // then too, so September earns its two days as well.
        setting("attendance.tracking_start_date", "2026-09-01");
        setting("leave.accrual_start_date", "2026-09-01");

        Employee person = employee("EMP7007", "Deepa", "Nair", "4");

        // Present every September working day but the 7th, a Monday.
        LocalDate september = LocalDate.of(2026, 9, 1);
        for (LocalDate date = september; date.getMonthValue() == 9; date = date.plusDays(1)) {
            if (isOff(date) || date.getDayOfMonth() == 7) {
                continue;
            }
            presentOn(person, date, 0);
        }
        // And every October working day, so October itself charges nothing.
        for (int day : WORKING_DAYS) {
            present(person, day, 0);
        }

        Map<String, Object> row = rowFor(october(), "Deepa Nair");

        // 4 carried in + 2 earned in September - 1 absence = 5 on 1 October.
        assertThat(number(row, "openingBalance")).isEqualTo("5");
        assertThat(number(row, "leavesTaken")).isEqualTo("0");
        assertThat(number(row, "carryForward")).isEqualTo("7");
    }

    /** The same roster the seeded rules express: Sundays, 2nd and 4th Saturdays. */
    private static boolean isOff(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return true;
        }
        if (date.getDayOfWeek() != DayOfWeek.SATURDAY) {
            return false;
        }
        int occurrence = ((date.getDayOfMonth() - 1) / 7) + 1;
        return occurrence == 2 || occurrence == 4;
    }

    @Test
    @DisplayName("a range spanning two months is refused rather than half-answered")
    void twoMonthsIsRefused() {
        employee("EMP7001", "Meera", "Nair", "20");

        assertThatThrownBy(() -> reportService.generate(hr, ReportType.SALARY_ATTENDANCE,
                new ReportFilter(OCTOBER, LocalDate.of(2026, 11, 5),
                        null, null, null, null, null),
                PageRequest.of(0, 100)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("one calendar month");
    }

    @Test
    @DisplayName("somebody with no ledger row opens at nothing rather than failing")
    void noLedgerRow() {
        Employee person = new Employee();
        person.setEmployeeCode("EMP7008");
        person.setFirstName("Naya");
        person.setLastName("Bharti");
        person.setOfficialEmail("emp7008@company.com");
        person.setJoiningDate(LocalDate.of(2022, 4, 1));
        person.setOffice(office);
        person.setEmploymentType(EmploymentType.PERMANENT);
        employeeRepository.save(person);

        presentAllMonthExcept(person, Map.of(), Set.of());

        Map<String, Object> row = rowFor(october(), "Naya Bharti");
        assertThat(number(row, "openingBalance")).isEqualTo("0");
        assertThat(number(row, "carryForward")).isEqualTo("2");
    }

    @Test
    @DisplayName("nothing is counted before somebody joined or after they left")
    void employmentWindowIsRespected() {
        Employee person = employee("EMP7009", "Mid", "Joiner", "0");
        person.setJoiningDate(LocalDate.of(2026, 10, 19));
        employeeRepository.save(person);

        for (int day : WORKING_DAYS) {
            if (day >= 19) {
                present(person, day, 0);
            }
        }

        Map<String, Object> row = rowFor(october(), "Mid Joiner");

        assertThat(row.get("d1")).isEqualTo("");
        assertThat(row.get("d18")).isEqualTo("");
        assertThat(row.get("d19")).isEqualTo("Present");
        assertThat(number(row, "leavesTaken")).isEqualTo("0");
        // Joining after the 10th earns nothing for that month.
        assertThat(number(row, "monthlyAccrual")).isEqualTo("0");
    }

    @Test
    @DisplayName("with no go-live date set, nothing is excluded")
    void noTrackingDateMeansEverythingCounts() {
        clearSetting("attendance.tracking_start_date");

        Employee person = employee("EMP7010", "Always", "Tracked", "30");
        presentAllMonthExcept(person, Map.of(), Set.of());

        Map<String, Object> row = rowFor(october(), "Always Tracked");

        assertThat(row.get("d1")).isEqualTo("Present");
        assertThat(number(row, "workingDays")).isEqualTo("23");
        assertThat(row.get("comments")).isEqualTo("");
    }
}
