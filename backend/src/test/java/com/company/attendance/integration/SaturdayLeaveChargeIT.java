package com.company.attendance.integration;

import com.company.attendance.entity.CompanySetting;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.LeaveRequest;
import com.company.attendance.entity.LeaveRequestDay;
import com.company.attendance.entity.LeaveType;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.WeeklyOff;
import com.company.attendance.enums.DayPortion;
import com.company.attendance.enums.DayType;
import com.company.attendance.enums.EmploymentType;
import com.company.attendance.enums.RequestStatus;
import com.company.attendance.repository.CompanySettingRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.LeaveRequestDayRepository;
import com.company.attendance.repository.LeaveRequestRepository;
import com.company.attendance.repository.LeaveTypeRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.WeeklyOffRepository;
import com.company.attendance.service.LeaveDayExpansionService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a leave day on a short Saturday takes off the balance.
 *
 * <p>This is the other half of V32's Saturday rule, and the half that is easy
 * to forget. The salary sheet credits a Saturday covered by approved leave as
 * half a day, because a short Saturday is half a working day. If the leave
 * module still debited a whole day for it, the employee would spend one day of
 * paid leave and be paid for half — and nothing anywhere would show the
 * missing half, because each side would be internally consistent.
 *
 * <p>The figure is asserted on the day rows and on the expansion total
 * together, because the total is what becomes {@code total_days} on the
 * request and what the ledger is actually debited by. The two disagreeing is
 * the specific bug this class exists to catch.
 *
 * <p>Saturday is a working day in this fixture — only Sunday is a weekly off —
 * because the rule is about the Saturdays somebody is rostered on. The one
 * test that needs a Saturday off adds that rule itself.
 */
@SpringBootTest
@ActiveProfiles("test")
class SaturdayLeaveChargeIT {

    /** A Friday, its Saturday, and the Monday after — well after the effective date. */
    private static final LocalDate FRIDAY = LocalDate.of(2027, 1, 8);
    private static final LocalDate SATURDAY = LocalDate.of(2027, 1, 9);
    private static final LocalDate MONDAY = LocalDate.of(2027, 1, 11);

    @Autowired LeaveDayExpansionService expansion;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired LeaveRequestRepository leaveRequestRepository;
    @Autowired LeaveRequestDayRepository leaveRequestDayRepository;
    @Autowired WeeklyOffRepository weeklyOffRepository;
    @Autowired CompanySettingRepository companySettingRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Employee employee;
    private LeaveType paidLeave;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        Office office = new Office();
        office.setCode("HO");
        office.setName("Head Office");
        office.setTimezone("Asia/Kolkata");
        office = officeRepository.save(office);

        employee = new Employee();
        employee.setEmployeeCode("EMP9001");
        employee.setFirstName("Asha");
        employee.setLastName("Rao");
        employee.setOfficialEmail("asha.saturday@company.com");
        employee.setJoiningDate(LocalDate.of(2022, 4, 1));
        employee.setOffice(office);
        employee.setEmploymentType(EmploymentType.PERMANENT);
        employee = employeeRepository.save(employee);

        // Sunday only. Saturday is a day this person is rostered to work.
        weeklyOff(DayOfWeek.SUNDAY);

        paidLeave = new LeaveType();
        paidLeave.setCode("PL");
        paidLeave.setName("Paid Leave");
        paidLeave.setAllowHalfDay(true);
        paidLeave.setCountsHolidaysWeeklyOffs(false);
        paidLeave = leaveTypeRepository.save(paidLeave);

        setting("payroll.rules_effective_date", "2026-11-01");
        setting("payroll.saturday_day_value", "0.5");
    }

    /** company_settings survives {@link DatabaseCleaner}. */
    @AfterEach
    void forgetSettings() {
        clearSetting("payroll.rules_effective_date");
        clearSetting("payroll.saturday_day_value");
    }

    @Test
    @DisplayName("a whole day of leave on a working Saturday debits half a day")
    void saturdayLeaveDebitsHalfADay() {
        LeaveDayExpansionService.Expansion result = expand(SATURDAY, SATURDAY);

        assertThat(result.days()).hasSize(1);
        LeaveRequestDay day = result.days().get(0);

        assertThat(day.getDayType()).isEqualTo(DayType.WORKING);
        assertThat(day.isDeductible()).isTrue();
        // Still applied for as a whole day: the employee asked for the
        // Saturday off, and the whole of a short Saturday is half a day.
        assertThat(day.getDayPortion()).isEqualTo(DayPortion.FULL_DAY);
        assertThat(day.getDeductibleDays()).isEqualByComparingTo("0.5");

        // The total is what the ledger is debited by. If this and the day
        // above ever disagree, the difference comes out of the employee.
        assertThat(result.deductibleDays()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("half a day of leave on a working Saturday debits a quarter")
    void halfOfAShortSaturdayIsAQuarter() {
        LeaveDayExpansionService.Expansion result = expansion.expand(
                employee, paidLeave, SATURDAY, SATURDAY,
                DayPortion.FIRST_HALF, DayPortion.FIRST_HALF);

        // Half of a half-length shift. Odd to read, and the only figure that
        // is consistent with the salary sheet, which credits the same day as
        // 0.25 worked.
        assertThat(result.deductibleDays()).isEqualByComparingTo("0.25");
    }

    @Test
    @DisplayName("a quarter day survives being written to the database and read back")
    void aQuarterDaySurvivesTheDatabase() {
        // The figure is computed in BigDecimal and stored in a DECIMAL column,
        // and the column is where a quarter day goes wrong: at one decimal
        // place the database rounds 0.25 to 0.3 and says nothing. Nothing
        // fails, no test on the in-memory value notices, and every half day
        // of Saturday leave quietly costs 0.05 of a day too much.
        LeaveDayExpansionService.Expansion result = expansion.expand(
                employee, paidLeave, SATURDAY, SATURDAY,
                DayPortion.FIRST_HALF, DayPortion.FIRST_HALF);

        LeaveRequest request = new LeaveRequest();
        request.setRequestNumber("LV-QUARTER-1");
        request.setEmployee(employee);
        request.setLeaveType(paidLeave);
        request.setFromDate(SATURDAY);
        request.setToDate(SATURDAY);
        request.setFromDayPortion(DayPortion.FIRST_HALF);
        request.setToDayPortion(DayPortion.FIRST_HALF);
        request.setTotalDays(result.deductibleDays());
        request.setReason("Round trip");
        request.setStatus(RequestStatus.APPROVED);
        LeaveRequest savedRequest = leaveRequestRepository.save(request);

        LeaveRequestDay day = result.days().get(0);
        day.setLeaveRequest(savedRequest);
        Long id = leaveRequestDayRepository.saveAndFlush(day).getId();

        leaveRequestDayRepository.flush();
        LeaveRequestDay reloaded = leaveRequestDayRepository.findById(id).orElseThrow();

        assertThat(reloaded.getDeductibleDays()).isEqualByComparingTo("0.25");
    }

    @Test
    @DisplayName("an ordinary weekday still debits a whole day")
    void aWeekdayIsUnchanged() {
        LeaveDayExpansionService.Expansion result = expand(FRIDAY, FRIDAY);

        assertThat(result.days().get(0).getDeductibleDays()).isEqualByComparingTo("1.0");
        assertThat(result.deductibleDays()).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("Friday to Saturday debits one and a half days")
    void aRangeMixesWeightsCorrectly() {
        LeaveDayExpansionService.Expansion result = expand(FRIDAY, SATURDAY);

        assertThat(result.days()).hasSize(2);
        assertThat(result.days().get(0).getDeductibleDays()).isEqualByComparingTo("1.0");
        assertThat(result.days().get(1).getDeductibleDays()).isEqualByComparingTo("0.5");
        assertThat(result.deductibleDays()).isEqualByComparingTo("1.5");
    }

    @Test
    @DisplayName("a Saturday the employee is rostered off is charged as a whole day when bridged")
    void aBridgedSaturdayOffIsAWholeDay() {
        // This person has Saturdays off, so the Saturday is not a short shift
        // they can miss. Taking Friday and Monday bridges it, and the sandwich
        // rule prices a bridged non-working day as a whole day — halving it
        // would make a long weekend cheaper than the days either side.
        weeklyOff(DayOfWeek.SATURDAY);

        LeaveDayExpansionService.Expansion result = expand(FRIDAY, MONDAY);

        LeaveRequestDay saturday = result.days().stream()
                .filter(day -> day.getLeaveDate().equals(SATURDAY))
                .findFirst()
                .orElseThrow();

        assertThat(saturday.isDeductible()).isTrue();
        assertThat(saturday.getDeductibleDays()).isEqualByComparingTo("1.0");
        // Friday, Saturday, Sunday, Monday — four whole days.
        assertThat(result.deductibleDays()).isEqualByComparingTo("4.0");
    }

    @Test
    @DisplayName("a Saturday off that is not bridged is charged nothing at all")
    void anUnbridgedSaturdayOffChargesNothing() {
        weeklyOff(DayOfWeek.SATURDAY);

        LeaveDayExpansionService.Expansion result = expand(SATURDAY, SATURDAY);

        assertThat(result.days().get(0).isDeductible()).isFalse();
        assertThat(result.days().get(0).getDeductibleDays()).isEqualByComparingTo("0.0");
        assertThat(result.deductibleDays()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("before the effective date a working Saturday still debits a whole day")
    void aSaturdayBeforeTheEffectiveDateIsUnchanged() {
        // 10 January 2026 is a Saturday, and well before the rules begin. A
        // leave granted then was debited a whole day, and re-reading it must
        // keep saying so.
        LocalDate earlier = LocalDate.of(2026, 1, 10);
        assertThat(earlier.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);

        LeaveDayExpansionService.Expansion result = expand(earlier, earlier);

        assertThat(result.deductibleDays()).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("with no effective date set, a working Saturday debits a whole day")
    void noEffectiveDateMeansNoHalving() {
        clearSetting("payroll.rules_effective_date");

        LeaveDayExpansionService.Expansion result = expand(SATURDAY, SATURDAY);

        assertThat(result.deductibleDays()).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("a Saturday value of 1.0 debits a whole day again")
    void theSaturdayValueIsConfigurable() {
        setting("payroll.saturday_day_value", "1.0");

        LeaveDayExpansionService.Expansion result = expand(SATURDAY, SATURDAY);

        assertThat(result.deductibleDays()).isEqualByComparingTo("1.0");
    }

    // -----------------------------------------------------------------
    // Seeding
    // -----------------------------------------------------------------

    private LeaveDayExpansionService.Expansion expand(LocalDate from, LocalDate to) {
        return expansion.expand(employee, paidLeave, from, to,
                DayPortion.FULL_DAY, DayPortion.FULL_DAY);
    }

    private WeeklyOff weeklyOff(DayOfWeek day) {
        WeeklyOff rule = new WeeklyOff();
        rule.setScopeType("GLOBAL");
        rule.setDayOfWeek((byte) day.getValue());
        return weeklyOffRepository.save(rule);
    }

    private void setting(String key, String value) {
        CompanySetting setting = companySettingRepository.findBySettingKey(key)
                .orElseGet(CompanySetting::new);
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        setting.setValueType("STRING");
        setting.setCategory("PAYROLL");
        companySettingRepository.save(setting);
    }

    private void clearSetting(String key) {
        companySettingRepository.findBySettingKey(key)
                .ifPresent(companySettingRepository::delete);
    }
}
