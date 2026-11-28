package com.company.attendance.integration;

import com.company.attendance.dto.request.report.ReportFilter;
import com.company.attendance.dto.response.report.ReportResult;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.AttendancePolicy;
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
import com.company.attendance.repository.AttendancePolicyRepository;
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
import com.company.attendance.service.LeaveDayExpansionService;
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

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The salary sheet under the rules dated from V32.
 *
 * <p>{@link SalaryAttendanceReportIT} is the same October 2026 fixture with
 * these rules off, holding payroll's own figures: 23 working days, 31 payable
 * days, an absence charged to the leave balance. The two classes are meant to
 * disagree, and the arithmetic below is what the disagreement is supposed to
 * be. Four rules are in play:
 *
 * <ol>
 *   <li>A short Saturday is half a working day — owed, paid, charged and
 *       missed in halves.
 *   <li>An absence nobody applied for is loss of pay, and the leave balance
 *       is untouched.
 *   <li>Every second late mark in the month costs a day's pay.
 *   <li>The sheet says how late somebody was, and how much of the month's
 *       30-minute pool the day spent.
 * </ol>
 *
 * <p>The month is October 2026 so the figures can be read against the other
 * class line for line. Its Saturdays are the 3rd, 10th, 17th, 24th and 31st;
 * the 10th and 24th are off under the 2nd-and-4th roster and the other three
 * are worked, which is what makes 1.5 days of Saturday halves the number to
 * look for.
 */
@SpringBootTest
@ActiveProfiles("test")
class SalaryAttendanceRulesIT {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static final LocalDate OCTOBER = LocalDate.of(2026, 10, 1);

    /** Public holidays, the same two as payroll's sheet. */
    private static final List<Integer> HOLIDAYS = List.of(2, 20);

    /** Every day of October 2026 that is neither a holiday nor an off. */
    private static final List<Integer> WORKING_DAYS = List.of(
            1, 3, 5, 6, 7, 8, 9, 12, 13, 14, 15, 16, 17, 19, 21, 22, 23, 26, 27, 28, 29, 30, 31);

    /**
     * 20 full weekdays plus three Saturdays at a half each.
     *
     * <p>21.5 owed + 8 offs + 1.5 Saturday halves = 31, the length of the
     * month. That identity is the whole reason the Saturday halves column
     * exists: without it payroll is left to account for a missing 1.5 days.
     */
    private static final String WORKING_DAYS_WEIGHTED = "21.5";

    /** Perfect attendance: every weighted working day worked, plus the offs. */
    private static final String PAYABLE_WHEN_PERFECT = "29.5";

    @Autowired ReportService reportService;
    @Autowired LeaveDayExpansionService leaveDayExpansionService;
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
    @Autowired AttendancePolicyRepository attendancePolicyRepository;
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

        // The late-mark penalty is read off the policy, and nothing is
        // deducted without a policy to read it from. Share India's rule is
        // two marks to a day.
        AttendancePolicy policy = new AttendancePolicy();
        policy.setCode("DEFAULT");
        policy.setName("Default Attendance Policy");
        policy.setLateMarksPerPenalty((short) 2);
        policy.setLateMarkPenaltyDays(new BigDecimal("1.0"));
        attendancePolicyRepository.save(policy);

        setting("leave.accrual_start_date", "2026-10-01");
        setting("attendance.tracking_start_date", "2026-10-01");
        // The rules under test, dated to the first of the month so the whole
        // sheet falls under them and no day has to be reasoned about twice.
        setting("payroll.rules_effective_date", "2026-10-01");
        setting("payroll.saturday_day_value", "0.5");

        hr = UserPrincipal.fromClaims(1L, null, "hr",
                Set.of("HR"), Set.of("REPORT_VIEW_ALL", "REPORT_EXPORT"));
    }

    /**
     * company_settings survives {@link DatabaseCleaner}, so every row seeded
     * here has to be removed again. A {@code payroll.rules_effective_date}
     * left behind would silently apply these rules to
     * {@link SalaryAttendanceReportIT}, whose figures are payroll's own and
     * are meant to be computed without them.
     */
    @AfterEach
    void forgetSettings() {
        clearSetting("attendance.tracking_start_date");
        clearSetting("leave.accrual_start_date");
        clearSetting("payroll.rules_effective_date");
        clearSetting("payroll.saturday_day_value");
    }

    // -----------------------------------------------------------------
    // Rule 1: a short Saturday is half a working day
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a worked Saturday is owed half a day and pays half a day")
    void saturdayIsHalfAWorkingDay() {
        Employee asha = employee("EMP8001", "Asha", "Rao", "7");
        presentAllMonthExcept(asha, Map.of(), Set.of());

        Map<String, Object> row = rowFor("Asha Rao");

        assertThat(number(row, "workingDays")).isEqualTo(WORKING_DAYS_WEIGHTED);
        assertThat(number(row, "daysWorked")).isEqualTo(WORKING_DAYS_WEIGHTED);
        assertThat(number(row, "holidaysAndOffs")).isEqualTo("8");
        assertThat(number(row, "saturdayHalfDays")).isEqualTo("1.5");
        assertThat(number(row, "payableDays")).isEqualTo(PAYABLE_WHEN_PERFECT);

        // Nothing goes missing: the three halves nobody is owed and nobody is
        // paid are the only difference between the payable figure and the
        // month.
        assertThat(total(row, "workingDays", "holidaysAndOffs", "saturdayHalfDays"))
                .isEqualTo(31.0);
    }

    @Test
    @DisplayName("a Saturday missed with no leave costs half a day, not a whole one")
    void missedSaturdayCostsHalfADay() {
        Employee bhavin = employee("EMP8002", "Bhavin", "Shah", "7");
        presentAllMonthExcept(bhavin, Map.of(), Set.of(31));

        Map<String, Object> row = rowFor("Bhavin Shah");

        assertThat(number(row, "daysWorked")).isEqualTo("21");
        assertThat(number(row, "unpaidDays")).isEqualTo("0.5");
        assertThat(number(row, "payableDays")).isEqualTo("29");
        assertThat(String.valueOf(row.get("comments")))
                .contains("0.5 days with no leave applied");
    }

    @Test
    @DisplayName("approved leave on a Saturday charges the half day, and the day pays in full")
    void saturdayLeaveIsChargedAsAHalfDay() {
        Employee chetan = employee("EMP8003", "Chetan", "Patil", "7");
        presentAllMonthExcept(chetan, Map.of(), Set.of(31));
        // Applied for as a whole day, which is what somebody applying for a
        // Saturday naturally does. The day is only worth half, so half is all
        // it can take off the balance.
        grantLeave(chetan, 31);

        Map<String, Object> row = rowFor("Chetan Patil");

        assertThat(number(row, "leavesTaken")).isEqualTo("0.5");
        assertThat(number(row, "unpaidDays")).isEqualTo("0");
        // Back to the perfect-attendance figure: this is the "once the leave
        // is applied and approved, the 0.5 is adjusted" case.
        assertThat(number(row, "payableDays")).isEqualTo(PAYABLE_WHEN_PERFECT);
    }

    // -----------------------------------------------------------------
    // Rule 2: an absence nobody applied for is loss of pay
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an absence with no leave applied is loss of pay and leaves the balance alone")
    void absenceIsUnpaidAndDoesNotTouchTheBalance() {
        Employee divya = employee("EMP8004", "Divya", "Joshi", "7");
        // The 30th is a Friday, so this is a whole working day.
        presentAllMonthExcept(divya, Map.of(), Set.of(30));

        Map<String, Object> row = rowFor("Divya Joshi");

        assertThat(number(row, "daysWorked")).isEqualTo("20.5");
        assertThat(number(row, "leavesTaken")).isEqualTo("0");
        assertThat(number(row, "unpaidDays")).isEqualTo("1");
        assertThat(number(row, "payableDays")).isEqualTo("28.5");

        // The point of the rule. Under the old one this absence came off the
        // balance by itself and the carry-forward was 8.
        assertThat(number(row, "openingBalance")).isEqualTo("7");
        assertThat(number(row, "carryForward")).isEqualTo("9");
        assertThat(String.valueOf(row.get("comments")))
                .contains("Absent")
                .contains("1 day with no leave applied")
                .contains("Applying for the leave and having it approved converts this back");
    }

    @Test
    @DisplayName("applying for the day and having it approved converts it back to a paid day")
    void approvedLeaveConvertsTheAbsenceBack() {
        Employee esha = employee("EMP8005", "Esha", "Kulkarni", "7");
        presentAllMonthExcept(esha, Map.of(), Set.of(30));
        grantLeave(esha, 30);

        Map<String, Object> row = rowFor("Esha Kulkarni");

        assertThat(number(row, "leavesTaken")).isEqualTo("1");
        assertThat(number(row, "unpaidDays")).isEqualTo("0");
        assertThat(number(row, "payableDays")).isEqualTo(PAYABLE_WHEN_PERFECT);
        // Now it does come off the balance, because somebody asked for it to.
        assertThat(number(row, "carryForward")).isEqualTo("8");
    }

    @Test
    @DisplayName("a half day worked is half a day's pay, and the rest is unpaid until applied for")
    void halfDayIsHalfUnpaid() {
        Employee farah = employee("EMP8006", "Farah", "Khan", "7");
        presentAllMonthExcept(farah, Map.of(), Set.of(29));
        halfDayOn(farah, 29);

        Map<String, Object> row = rowFor("Farah Khan");

        assertThat(number(row, "daysWorked")).isEqualTo("21");
        assertThat(number(row, "unpaidDays")).isEqualTo("0.5");
        assertThat(number(row, "payableDays")).isEqualTo("29");
    }

    // -----------------------------------------------------------------
    // A leave is only paid out of leave somebody actually has
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a Saturday leave with nothing in the balance is an absence, not a leave")
    void saturdayLeaveWithNoBalanceIsAnAbsence() {
        nothingAccruesThisMonth();
        Employee tara = employee("EMP8022", "Tara", "Menon", "0");
        presentAllMonthExcept(tara, Map.of(), Set.of(31));
        grantLeave(tara, 31);

        Map<String, Object> row = rowFor("Tara Menon");

        // The day itself says so. Reading "Leave" while the money came out in
        // the LOP column puts the two halves of one fact in different places.
        assertThat(row.get("d31")).isEqualTo("Absent");
        assertThat(number(row, "leavesTaken")).isEqualTo("0");
        assertThat(number(row, "unpaidDays")).isEqualTo("0.5");
        assertThat(number(row, "payableDays")).isEqualTo("29");
        assertThat(number(row, "carryForward")).isEqualTo("0");
        assertThat(String.valueOf(row.get("comments")))
                .contains("Absent")
                // They did apply, and it was approved. The note must not tell
                // them to apply for it.
                .contains("0.5 days of approved leave with no paid leave left")
                .doesNotContain("with no leave applied");
    }

    @Test
    @DisplayName("half a day in the balance covers a Saturday leave exactly")
    void halfADayCoversASaturdayLeave() {
        nothingAccruesThisMonth();
        Employee uday = employee("EMP8023", "Uday", "Hegde", "0.5");
        presentAllMonthExcept(uday, Map.of(), Set.of(31));
        grantLeave(uday, 31);

        Map<String, Object> row = rowFor("Uday Hegde");

        // Exactly enough: a short Saturday asks for half a day and half a day
        // is what is there.
        assertThat(row.get("d31")).isEqualTo("Leave");
        assertThat(number(row, "leavesTaken")).isEqualTo("0.5");
        assertThat(number(row, "unpaidDays")).isEqualTo("0");
        assertThat(number(row, "payableDays")).isEqualTo(PAYABLE_WHEN_PERFECT);
        assertThat(number(row, "carryForward")).isEqualTo("0");
    }

    @Test
    @DisplayName("a weekday leave the balance only half covers is half paid, and says which half")
    void aPartlyCoveredLeaveSaysSo() {
        nothingAccruesThisMonth();
        Employee vidya = employee("EMP8024", "Vidya", "Raut", "0.5");
        // The 30th is a Friday: a whole working day against half a day of
        // balance.
        presentAllMonthExcept(vidya, Map.of(), Set.of(30));
        grantLeave(vidya, 30);

        Map<String, Object> row = rowFor("Vidya Raut");

        // Neither "Leave" nor "Absent" is true of this day, so it says both.
        assertThat(row.get("d30")).isEqualTo("Leave 0.5 (0.5 unpaid)");
        assertThat(number(row, "leavesTaken")).isEqualTo("0.5");
        assertThat(number(row, "unpaidDays")).isEqualTo("0.5");
        assertThat(number(row, "payableDays")).isEqualTo("29");
        assertThat(number(row, "carryForward")).isEqualTo("0");
    }

    @Test
    @DisplayName("when the balance runs out mid-month the earlier day keeps it")
    void theBalanceIsSpentInDateOrder() {
        nothingAccruesThisMonth();
        Employee yash = employee("EMP8025", "Yash", "Borkar", "1");
        // Two whole working days of leave against one day of balance.
        presentAllMonthExcept(yash, Map.of(), Set.of(29, 30));
        grantLeave(yash, 29);
        grantLeave(yash, 30);

        Map<String, Object> row = rowFor("Yash Borkar");

        // First come, first served. Any other order would make the figures
        // depend on which row the database happened to return first.
        assertThat(row.get("d29")).isEqualTo("Leave");
        assertThat(row.get("d30")).isEqualTo("Absent");
        assertThat(number(row, "leavesTaken")).isEqualTo("1");
        assertThat(number(row, "unpaidDays")).isEqualTo("1");
        assertThat(number(row, "payableDays")).isEqualTo("28.5");
    }

    @Test
    @DisplayName("nothing is ever charged beyond the balance, so no carry-forward goes negative")
    void theBalanceIsNeverOverdrawn() {
        nothingAccruesThisMonth();
        Employee zoya = employee("EMP8026", "Zoya", "Mirza", "0");
        // Four whole days of approved leave against an empty balance. Approval
        // checks the balance at the time; this is the state the sheet has to
        // cope with when it has been spent since, or when the ledger and the
        // replayed figure have drifted.
        presentAllMonthExcept(zoya, Map.of(), Set.of(26, 27, 28, 29));
        for (int day : new int[]{26, 27, 28, 29}) {
            grantLeave(zoya, day);
        }

        Map<String, Object> row = rowFor("Zoya Mirza");

        assertThat(number(row, "leavesTaken")).isEqualTo("0");
        assertThat(number(row, "unpaidDays")).isEqualTo("4");
        assertThat(number(row, "carryForward")).isEqualTo("0");
        // 21.5 owed, 4 of it unpaid, plus 8 offs.
        assertThat(number(row, "payableDays")).isEqualTo("25.5");

        // The old sheet said this in a sentence and still paid the full month.
        // It is a column now, and the payable figure reflects it.
        assertThat(total(row, "daysWorked", "leavesTaken", "unpaidDays"))
                .isEqualTo(Double.parseDouble(number(row, "workingDays")));
    }

    // -----------------------------------------------------------------
    // Rule 3: every second late mark costs a day
    // -----------------------------------------------------------------

    @Test
    @DisplayName("one late mark costs nothing and says so")
    void oneLateMarkCostsNothing() {
        Employee gaurav = employee("EMP8007", "Gaurav", "Mehta", "7");
        presentAllMonthExcept(gaurav, Map.of(6, 12), Set.of());

        Map<String, Object> row = rowFor("Gaurav Mehta");

        assertThat(number(row, "lateMarks")).isEqualTo("1");
        assertThat(number(row, "latePenaltyDays")).isEqualTo("0");
        assertThat(number(row, "payableDays")).isEqualTo(PAYABLE_WHEN_PERFECT);
        assertThat(String.valueOf(row.get("comments")))
                .contains("1 late mark, below the threshold that costs a day");
    }

    @Test
    @DisplayName("two late marks cost a day's pay")
    void twoLateMarksCostADay() {
        Employee hema = employee("EMP8008", "Hema", "Iyer", "7");
        presentAllMonthExcept(hema, Map.of(6, 12, 13, 20), Set.of());

        Map<String, Object> row = rowFor("Hema Iyer");

        assertThat(number(row, "lateMarks")).isEqualTo("2");
        assertThat(number(row, "latePenaltyDays")).isEqualTo("1");
        // Every day was worked, so the deduction comes off the payable figure
        // rather than out of what was credited.
        assertThat(number(row, "daysWorked")).isEqualTo(WORKING_DAYS_WEIGHTED);
        assertThat(number(row, "payableDays")).isEqualTo("28.5");
        assertThat(String.valueOf(row.get("comments")))
                .contains("2 late marks — 1 day loss of pay");
    }

    @Test
    @DisplayName("an odd late mark left at month end does not carry into the next month")
    void theThirdLateMarkWaitsForAFourth() {
        Employee irfan = employee("EMP8009", "Irfan", "Sheikh", "7");
        presentAllMonthExcept(irfan, Map.of(6, 12, 13, 20, 14, 8), Set.of());

        Map<String, Object> row = rowFor("Irfan Sheikh");

        assertThat(number(row, "lateMarks")).isEqualTo("3");
        assertThat(number(row, "latePenaltyDays")).isEqualTo("1");
        assertThat(number(row, "payableDays")).isEqualTo("28.5");
    }

    @Test
    @DisplayName("four late marks cost two days")
    void fourLateMarksCostTwoDays() {
        Employee jaya = employee("EMP8010", "Jaya", "Nambiar", "7");
        presentAllMonthExcept(jaya, Map.of(6, 12, 13, 20, 14, 8, 15, 5), Set.of());

        Map<String, Object> row = rowFor("Jaya Nambiar");

        assertThat(number(row, "lateMarks")).isEqualTo("4");
        assertThat(number(row, "latePenaltyDays")).isEqualTo("2");
        assertThat(number(row, "payableDays")).isEqualTo("27.5");
    }

    @Test
    @DisplayName("lateness the month's pool absorbed is not a late mark")
    void absorbedLatenessIsNotALateMark() {
        Employee kiran = employee("EMP8011", "Kiran", "Desai", "7");
        presentAllMonthExcept(kiran, Map.of(), Set.of());
        // Late twice, and the 30-minute pool covered both: nothing is recorded
        // late, so neither day is a late mark. This is the case the buffer
        // exists for, and it must not cost anybody a day.
        absorbedLateness(kiran, 6, 10);
        absorbedLateness(kiran, 13, 15);

        Map<String, Object> row = rowFor("Kiran Desai");

        assertThat(number(row, "lateMarks")).isEqualTo("0");
        assertThat(number(row, "latePenaltyDays")).isEqualTo("0");
        assertThat(number(row, "graceUsedMinutes")).isEqualTo("25");
        assertThat(number(row, "payableDays")).isEqualTo(PAYABLE_WHEN_PERFECT);
    }

    // -----------------------------------------------------------------
    // Rule 4: the sheet says how late, and what the buffer absorbed
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a day cell says how late somebody was, and what the buffer took")
    void theCellSaysHowLate() {
        Employee lata = employee("EMP8012", "Lata", "Bhosale", "7");
        presentAllMonthExcept(lata, Map.of(), Set.of());

        // Nothing left in the pool: the whole twelve minutes are on the record.
        present(lata, 6, 12, 0);
        // Five minutes left, a twelve-minute arrival: five spent, seven recorded.
        present(lata, 13, 7, 5);
        // Entirely absorbed. Not a late mark, but the minutes are gone.
        present(lata, 14, 0, 9);

        Map<String, Object> row = rowFor("Lata Bhosale");

        assertThat(row.get("d6")).isEqualTo("Late by 12m");
        assertThat(row.get("d13")).isEqualTo("Late by 7m (5m adj)");
        assertThat(row.get("d14")).isEqualTo("Present (9m adj)");
        assertThat(row.get("d1")).isEqualTo("Present");

        assertThat(number(row, "lateMarks")).isEqualTo("2");
        assertThat(number(row, "graceUsedMinutes")).isEqualTo("14");
    }

    @Test
    @DisplayName("an hour late reads as an hour, not as sixty-something minutes")
    void longLatenessReadsInHours() {
        Employee manoj = employee("EMP8013", "Manoj", "Pawar", "7");
        presentAllMonthExcept(manoj, Map.of(), Set.of());
        present(manoj, 6, 65, 0);
        present(manoj, 7, 120, 0);

        Map<String, Object> row = rowFor("Manoj Pawar");

        assertThat(row.get("d6")).isEqualTo("Late by 1h 5m");
        assertThat(row.get("d7")).isEqualTo("Late by 2h");
    }

    // -----------------------------------------------------------------
    // The rules are dated, not switched
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a month before the effective date is settled under the old rules")
    void aMonthBeforeTheEffectiveDateDoesNotMove() {
        // Dated to November, so October — the month being reported — falls
        // entirely before it and must come out exactly as payroll's own sheet
        // does: full-value Saturdays, and an absence charged to the balance.
        setting("payroll.rules_effective_date", "2026-11-01");

        Employee nisha = employee("EMP8014", "Nisha", "Gokhale", "7");
        presentAllMonthExcept(nisha, Map.of(), Set.of(30));

        Map<String, Object> row = rowFor("Nisha Gokhale");

        assertThat(number(row, "workingDays")).isEqualTo("23");
        assertThat(number(row, "daysWorked")).isEqualTo("22");
        assertThat(number(row, "leavesTaken")).isEqualTo("1");
        assertThat(number(row, "unpaidDays")).isEqualTo("0");
        assertThat(number(row, "saturdayHalfDays")).isEqualTo("0");
        assertThat(number(row, "payableDays")).isEqualTo("31");
    }

    @Test
    @DisplayName("with no effective date set, none of the rules apply")
    void noEffectiveDateMeansNoNewRules() {
        clearSetting("payroll.rules_effective_date");

        Employee omkar = employee("EMP8015", "Omkar", "Jadhav", "7");
        presentAllMonthExcept(omkar, Map.of(6, 12, 13, 20), Set.of());

        Map<String, Object> row = rowFor("Omkar Jadhav");

        assertThat(number(row, "workingDays")).isEqualTo("23");
        assertThat(number(row, "lateMarks")).isEqualTo("0");
        assertThat(number(row, "latePenaltyDays")).isEqualTo("0");
        assertThat(number(row, "payableDays")).isEqualTo("31");

        // The minutes are still shown. Measuring lateness and charging for it
        // are different decisions, and only the second one is dated.
        assertThat(row.get("d6")).isEqualTo("Late by 12m");
    }

    @Test
    @DisplayName("an unreadable effective date turns the rules off rather than failing the report")
    void anUnreadableEffectiveDateIsTreatedAsOff() {
        setting("payroll.rules_effective_date", "the first of November");

        Employee pooja = employee("EMP8016", "Pooja", "Salvi", "7");
        presentAllMonthExcept(pooja, Map.of(), Set.of());

        Map<String, Object> row = rowFor("Pooja Salvi");

        assertThat(number(row, "payableDays")).isEqualTo("31");
    }

    @Test
    @DisplayName("a Saturday value of 1.0 pays a worked Saturday in full again")
    void saturdayValueIsConfigurable() {
        setting("payroll.saturday_day_value", "1.0");

        Employee rahul = employee("EMP8017", "Rahul", "Kadam", "7");
        presentAllMonthExcept(rahul, Map.of(), Set.of());

        Map<String, Object> row = rowFor("Rahul Kadam");

        assertThat(number(row, "workingDays")).isEqualTo("23");
        assertThat(number(row, "saturdayHalfDays")).isEqualTo("0");
        assertThat(number(row, "payableDays")).isEqualTo("31");
    }

    // -----------------------------------------------------------------
    // The identities, which are what make the sheet checkable
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every row's figures still reconcile to the month")
    void everyRowReconciles() {
        employee("EMP8018", "Sana", "Qureshi", "7");
        Employee tanmay = employee("EMP8019", "Tanmay", "Rane", "4");
        Employee uma = employee("EMP8020", "Uma", "Shetty", "9");

        presentAllMonthExcept(tanmay, Map.of(6, 12, 13, 20), Set.of(30, 31));
        presentAllMonthExcept(uma, Map.of(), Set.of(29));
        grantLeave(uma, 29);

        ReportResult result = october();
        assertThat(result.rows()).hasSize(3);

        for (Map<String, Object> row : result.rows()) {
            // What the month is made of, however the days turned out.
            assertThat(total(row, "workingDays", "holidaysAndOffs", "saturdayHalfDays"))
                    .as("the month, for %s", row.get("employeeName"))
                    .isEqualTo(31.0);

            // What is owed is worked, charged to leave, or unpaid. No day
            // falls between the three.
            assertThat(total(row, "daysWorked", "leavesTaken", "unpaidDays"))
                    .as("what is owed, for %s", row.get("employeeName"))
                    .isEqualTo(Double.parseDouble(number(row, "workingDays")));

            // And the payable figure is what was earned plus the offs, less
            // whatever lateness cost.
            double expected = Double.parseDouble(number(row, "daysWorked"))
                    + Double.parseDouble(number(row, "leavesTaken"))
                    + Double.parseDouble(number(row, "holidaysAndOffs"))
                    - Double.parseDouble(number(row, "latePenaltyDays"));
            assertThat(Double.parseDouble(number(row, "payableDays")))
                    .as("payable, for %s", row.get("employeeName"))
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("loss of pay never drives a payable figure below zero")
    void payableNeverGoesNegative() {
        Employee vijay = employee("EMP8021", "Vijay", "Naik", "0");
        // Late on all 23 working days: 11 days' penalty against 21.5 worked
        // and 8 offs. Still positive, but it is the shape of the arithmetic
        // that has to hold, not this particular month.
        Map<Integer, Integer> late = new HashMap<>();
        for (int day : WORKING_DAYS) {
            late.put(day, 10);
        }
        presentAllMonthExcept(vijay, late, Set.of());

        Map<String, Object> row = rowFor("Vijay Naik");

        assertThat(number(row, "lateMarks")).isEqualTo("23");
        assertThat(number(row, "latePenaltyDays")).isEqualTo("11");
        assertThat(number(row, "payableDays")).isEqualTo("18.5");
        assertThat(Double.parseDouble(number(row, "payableDays"))).isGreaterThanOrEqualTo(0.0);
    }

    // -----------------------------------------------------------------
    // Seeding
    // -----------------------------------------------------------------

    /**
     * Pushes the accrual start past this month, so the only balance a person
     * has is what they carried in.
     *
     * <p>Without this every employee accrues two days in October and no test
     * can produce an empty balance — which is the state the "if available"
     * rule exists for.
     */
    private void nothingAccruesThisMonth() {
        setting("leave.accrual_start_date", "2026-11-01");
    }

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

    /** A full day, recorded as {@code lateMinutes} late and nothing forgiven. */
    private void present(Employee person, int day, int lateMinutes) {
        present(person, day, lateMinutes, 0);
    }

    /**
     * A full day, with both halves of the grace decision recorded.
     *
     * <p>Upserts, so a test can lay down a whole month and then overwrite the
     * few days it actually cares about.
     *
     * @param lateMinutes  what is on the record, after the month's pool
     * @param graceMinutes what the month's pool absorbed
     */
    private void present(Employee person, int day, int lateMinutes, int graceMinutes) {
        LocalDate date = OCTOBER.withDayOfMonth(day);
        Attendance attendance = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(person.getId(), date)
                .orElseGet(Attendance::new);

        attendance.setEmployee(person);
        attendance.setAttendanceDate(date);
        attendance.setShift(shift);
        attendance.setPunchInTime(at(date, 9, 30)
                .plusSeconds((lateMinutes + graceMinutes) * 60L));
        attendance.setPunchOutTime(at(date, 18, 30));
        attendance.setWorkingMinutes(480);
        attendance.setLateMinutes(lateMinutes);
        attendance.setGraceUsedMinutes(graceMinutes);
        attendance.setWorkMode(WorkMode.OFFICE);
        attendance.setAttendanceStatus(AttendanceStatus.PRESENT);
        attendanceRepository.save(attendance);
    }

    /** Arrived late, and the month's pool covered all of it. */
    private void absorbedLateness(Employee person, int day, int graceMinutes) {
        present(person, day, 0, graceMinutes);
    }

    /** Worked, but only half the day. */
    private void halfDayOn(Employee person, int day) {
        LocalDate date = OCTOBER.withDayOfMonth(day);
        Attendance attendance = new Attendance();
        attendance.setEmployee(person);
        attendance.setAttendanceDate(date);
        attendance.setShift(shift);
        attendance.setPunchInTime(at(date, 9, 30));
        attendance.setPunchOutTime(at(date, 13, 30));
        attendance.setWorkingMinutes(240);
        attendance.setWorkMode(WorkMode.OFFICE);
        attendance.setAttendanceStatus(AttendanceStatus.HALF_DAY);
        attendanceRepository.save(attendance);
    }

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
        grant(person, date, date, DayPortion.FULL_DAY, DayPortion.FULL_DAY, "LV-RULES-");
    }

    /**
     * An approved leave, expanded by the real service.
     *
     * <p>Through {@link LeaveDayExpansionService} rather than by hand, so the
     * days carry the charge the application would actually have debited. A
     * fixture that set {@code deductibleDays} itself would assert the salary
     * sheet against a number the leave module never produces, which is how a
     * Saturday comes to be debited as a day and credited as a half.
     */
    private void grant(Employee person, LocalDate from, LocalDate to,
                       DayPortion fromPortion, DayPortion toPortion, String prefix) {
        LeaveDayExpansionService.Expansion expansion = leaveDayExpansionService
                .expand(person, paidLeave, from, to, fromPortion, toPortion);

        LeaveRequest request = new LeaveRequest();
        request.setRequestNumber(prefix + person.getEmployeeCode() + "-" + from.getDayOfMonth());
        request.setEmployee(person);
        request.setLeaveType(paidLeave);
        request.setFromDate(from);
        request.setToDate(to);
        request.setFromDayPortion(fromPortion);
        request.setToDayPortion(toPortion);
        request.setTotalDays(expansion.deductibleDays());
        request.setReason("Seeded");
        request.setStatus(RequestStatus.APPROVED);
        LeaveRequest saved = leaveRequestRepository.save(request);

        for (LeaveRequestDay day : expansion.days()) {
            day.setLeaveRequest(saved);
            leaveRequestDayRepository.save(day);
        }
    }

    private Instant at(LocalDate date, int hour, int minute) {
        return LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(IST).toInstant();
    }

    private ReportResult october() {
        return reportService.generate(hr, ReportType.SALARY_ATTENDANCE,
                new ReportFilter(OCTOBER, OCTOBER.withDayOfMonth(31), null, null, null, null, null),
                PageRequest.of(0, 100));
    }

    private Map<String, Object> rowFor(String name) {
        return october().rows().stream()
                .filter(row -> name.equals(row.get("employeeName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for " + name));
    }

    private static String number(Map<String, Object> row, String key) {
        return String.valueOf(row.get(key));
    }

    /** The sum of several of a row's figures, for the identities above. */
    private static double total(Map<String, Object> row, String... keys) {
        double sum = 0;
        for (String key : keys) {
            sum += Double.parseDouble(number(row, key));
        }
        return sum;
    }
}
