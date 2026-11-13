package com.company.attendance.integration;

import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Holiday;
import com.company.attendance.entity.LeaveType;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.WeeklyOff;
import com.company.attendance.enums.DayPortion;
import com.company.attendance.enums.EmploymentType;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.HolidayRepository;
import com.company.attendance.repository.LeaveTypeRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.WeeklyOffRepository;
import com.company.attendance.service.LeaveDayExpansionService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sandwich rule: a holiday or weekly off with leave on the working day
 * either side of it is charged as leave.
 *
 * <p>The asymmetry is the rule. Friday and Monday together cost four days;
 * Friday alone costs one. Every test here is really checking one of those two
 * halves, because an implementation that charges the weekend unconditionally
 * passes "Friday to Monday is four" and is still badly wrong.
 *
 * <p>Dates are anchored to a Friday three weeks out, so the weekend always
 * falls where the test expects regardless of when the suite runs.
 */
@SpringBootTest
@ActiveProfiles("test")
class SandwichLeaveIT {

    @Autowired LeaveDayExpansionService expansion;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired LeaveTypeRepository leaveTypeRepository;
    @Autowired WeeklyOffRepository weeklyOffRepository;
    @Autowired HolidayRepository holidayRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Employee employee;
    private LeaveType paidLeave;

    /** A Friday far enough out that nothing else interferes. */
    private LocalDate friday;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        friday = LocalDate.now().plusWeeks(3).with(TemporalAdjusters.next(DayOfWeek.FRIDAY));

        Office office = new Office();
        office.setCode("HO");
        office.setName("Head Office");
        office.setTimezone("Asia/Kolkata");
        office = officeRepository.save(office);

        employee = new Employee();
        employee.setEmployeeCode("EMP8001");
        employee.setFirstName("Asha");
        employee.setLastName("Rao");
        employee.setOfficialEmail("asha.sandwich@company.com");
        employee.setJoiningDate(LocalDate.now().minusYears(3));
        employee.setOffice(office);
        employee.setEmploymentType(EmploymentType.PERMANENT);
        employee = employeeRepository.save(employee);

        weeklyOffRepository.save(weeklyOff(DayOfWeek.SATURDAY));
        weeklyOffRepository.save(weeklyOff(DayOfWeek.SUNDAY));

        // Paid Leave as V24 configures it: skips non-working days, except
        // where the sandwich rule says otherwise.
        paidLeave = new LeaveType();
        paidLeave.setCode("PL");
        paidLeave.setName("Paid Leave");
        paidLeave.setAllowHalfDay(true);
        paidLeave.setCountsHolidaysWeeklyOffs(false);
        paidLeave.setHrApprovalThresholdDays(new BigDecimal("5"));
        paidLeave = leaveTypeRepository.save(paidLeave);
    }

    private WeeklyOff weeklyOff(DayOfWeek day) {
        WeeklyOff rule = new WeeklyOff();
        rule.setScopeType("GLOBAL");
        rule.setDayOfWeek((byte) day.getValue());
        return rule;
    }

    private LeaveDayExpansionService.Expansion expand(LocalDate from, LocalDate to) {
        return expansion.expand(employee, paidLeave, from, to,
                DayPortion.FULL_DAY, DayPortion.FULL_DAY);
    }

    // -----------------------------------------------------------------
    // The rule bites
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Friday to Monday costs four days, not two")
    void bridgesAWeekend() {
        var result = expand(friday, friday.plusDays(3));

        assertThat(result.deductibleDays()).isEqualByComparingTo("4.00");
        assertThat(result.days()).hasSize(4).allMatch(day -> day.isDeductible());
        assertThat(result.explanation()).contains("2 non-working days");
    }

    @Test
    @DisplayName("a public holiday between two leave days is charged too")
    void bridgesAHoliday() {
        // A holiday on the Wednesday, leave Tuesday to Thursday.
        LocalDate wednesday = friday.minusDays(2);
        Holiday holiday = new Holiday();
        holiday.setName("Festival");
        holiday.setHolidayDate(wednesday);
        holiday.setHolidayYear((short) wednesday.getYear());
        holiday.setHolidayType("COMPANY");
        holidayRepository.save(holiday);

        var result = expand(wednesday.minusDays(1), wednesday.plusDays(1));

        assertThat(result.deductibleDays()).isEqualByComparingTo("3.00");
        assertThat(result.explanation()).contains("1 non-working day");
    }

    @Test
    @DisplayName("a half day on each side still bridges the weekend")
    void halfDaysBridgeToo() {
        // Friday afternoon off, Monday morning off: still a four-day absence
        // as far as the weekend is concerned.
        var result = expansion.expand(employee, paidLeave, friday, friday.plusDays(3),
                DayPortion.SECOND_HALF, DayPortion.FIRST_HALF);

        // 0.5 + 1 + 1 + 0.5 — the bridged days are whole, the boundaries are not.
        assertThat(result.deductibleDays()).isEqualByComparingTo("3.00");
        assertThat(result.days()).allMatch(day -> day.isDeductible());
    }

    // -----------------------------------------------------------------
    // The rule does not bite — the half that makes it a rule
    // -----------------------------------------------------------------

    @Test
    @DisplayName("Friday alone costs one day and leaves the weekend alone")
    void noLeaveAfterTheWeekend() {
        var result = expand(friday, friday);

        assertThat(result.deductibleDays()).isEqualByComparingTo("1.00");
        assertThat(result.explanation()).isNull();
    }

    @Test
    @DisplayName("Friday to Sunday costs one day: nothing follows the weekend")
    void leaveTrailingIntoTheWeekend() {
        // The weekend is at the end of the range, not between two leave days.
        // Charging it here would be the classic over-eager implementation.
        var result = expand(friday, friday.plusDays(2));

        assertThat(result.deductibleDays()).isEqualByComparingTo("1.00");
        assertThat(result.explanation()).contains("Not charged for");
        assertThat(result.explanation()).doesNotContain("Charged for");
    }

    @Test
    @DisplayName("Saturday to Monday costs one day: nothing precedes the weekend")
    void leaveLeadingOutOfTheWeekend() {
        var result = expand(friday.plusDays(1), friday.plusDays(3));

        assertThat(result.deductibleDays()).isEqualByComparingTo("1.00");
        assertThat(result.explanation()).doesNotContain("Charged for");
    }

    @Test
    @DisplayName("a weekend with no leave either side is never charged")
    void aWeekendAlone() {
        var result = expand(friday.plusDays(1), friday.plusDays(2));

        assertThat(result.deductibleDays()).isEqualByComparingTo("0.00");
        assertThat(result.days()).noneMatch(day -> day.isDeductible());
    }

    @Test
    @DisplayName("an ordinary working week is unaffected")
    void anOrdinaryWeek() {
        LocalDate monday = friday.minusDays(4);
        var result = expand(monday, monday.plusDays(4));

        assertThat(result.deductibleDays()).isEqualByComparingTo("5.00");
        assertThat(result.explanation()).isNull();
    }

    @Test
    @DisplayName("two weekends inside one long leave are both charged")
    void twoWeekends() {
        // Friday to the Monday nine days later: two weekends, both bridged.
        var result = expand(friday, friday.plusDays(10));

        // 11 calendar days, of which 4 are weekend days, all now charged.
        assertThat(result.deductibleDays()).isEqualByComparingTo("11.00");
        assertThat(result.explanation()).contains("4 non-working days");
    }

    @Test
    @DisplayName("a type that already counts non-working days is unchanged")
    void typesThatCountEverything() {
        // Maternity counts holidays and weekly offs regardless, so the
        // sandwich pass has nothing to flip and must not double-count.
        LeaveType maternity = new LeaveType();
        maternity.setCode("MAT");
        maternity.setName("Maternity Leave");
        maternity.setCountsHolidaysWeeklyOffs(true);
        maternity.setHrApprovalThresholdDays(new BigDecimal("1"));
        maternity = leaveTypeRepository.save(maternity);

        var result = expansion.expand(employee, maternity, friday, friday.plusDays(3),
                DayPortion.FULL_DAY, DayPortion.FULL_DAY);

        assertThat(result.deductibleDays()).isEqualByComparingTo("4.00");
        assertThat(result.explanation()).isNull();
    }
}
