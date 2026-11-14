package com.company.attendance.integration;

import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.CompanySetting;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Office;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.EmploymentType;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.CompanySettingRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.service.MonthlyGraceService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The month's shared grace allowance.
 *
 * <p>Thirty minutes for a whole month, not fifteen a day. The difference is
 * the point: under the old rule somebody could arrive a quarter of an hour
 * late on every working day and never be recorded late once.
 *
 * <p>All dates sit inside one month so the pool is unambiguous, and a second
 * month is used only to prove the reset.
 */
@SpringBootTest
@ActiveProfiles("test")
class MonthlyGraceIT {

    @Autowired MonthlyGraceService grace;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired CompanySettingRepository companySettingRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Employee employee;

    private static final LocalDate THIRD = LocalDate.of(2026, 11, 3);
    private static final LocalDate FOURTH = LocalDate.of(2026, 11, 4);
    private static final LocalDate FIFTH = LocalDate.of(2026, 11, 5);
    private static final LocalDate NEXT_MONTH = LocalDate.of(2026, 12, 1);

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
        employee.setOfficialEmail("asha.grace@company.com");
        employee.setJoiningDate(LocalDate.of(2020, 1, 1));
        employee.setOffice(office);
        employee.setEmploymentType(EmploymentType.PERMANENT);
        employee = employeeRepository.save(employee);

        setMonthlyGrace("30");
    }

    private void setMonthlyGrace(String minutes) {
        CompanySetting setting = companySettingRepository
                .findBySettingKey("attendance.monthly_grace_minutes")
                .orElseGet(CompanySetting::new);
        setting.setSettingKey("attendance.monthly_grace_minutes");
        setting.setSettingValue(minutes);
        setting.setValueType("INT");
        setting.setCategory("ATTENDANCE");
        companySettingRepository.save(setting);
    }

    /** Records a settled day that spent {@code graceUsed} of the month's pool. */
    private void recordDay(LocalDate date, int graceUsed, int lateMinutes) {
        Attendance attendance = new Attendance();
        attendance.setEmployee(employee);
        attendance.setAttendanceDate(date);
        attendance.setGraceUsedMinutes(graceUsed);
        attendance.setLateMinutes(lateMinutes);
        attendance.setAttendanceStatus(AttendanceStatus.PRESENT);
        attendanceRepository.save(attendance);
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("lateness inside the allowance records nothing late")
    void withinTheAllowance() {
        var allowance = grace.apply(employee.getId(), THIRD, 10);

        assertThat(allowance.graceUsedMinutes()).isEqualTo(10);
        assertThat(allowance.lateMinutes()).isZero();
    }

    @Test
    @DisplayName("the allowance is shared across the month, not renewed daily")
    void sharedAcrossTheMonth() {
        // This is the whole change. Under the old per-day rule each of these
        // would have been free; now the third one starts costing.
        recordDay(THIRD, 10, 0);
        recordDay(FOURTH, 10, 0);

        var third = grace.apply(employee.getId(), FIFTH, 10);
        assertThat(third.graceUsedMinutes()).isEqualTo(10);
        assertThat(third.lateMinutes()).isZero();

        recordDay(FIFTH, 10, 0);

        var fourth = grace.apply(employee.getId(), FIFTH.plusDays(1), 10);
        assertThat(fourth.graceUsedMinutes()).isZero();
        assertThat(fourth.lateMinutes()).isEqualTo(10);
    }

    @Test
    @DisplayName("a partly spent allowance absorbs what it can and records the rest")
    void onlyTheExcessIsRecorded() {
        recordDay(THIRD, 25, 0);

        // 5 left, 12 minutes late: 5 forgiven, 7 recorded.
        var allowance = grace.apply(employee.getId(), FOURTH, 12);

        assertThat(allowance.graceUsedMinutes()).isEqualTo(5);
        assertThat(allowance.lateMinutes()).isEqualTo(7);
    }

    @Test
    @DisplayName("once the allowance is gone, lateness is recorded in full")
    void exhausted() {
        recordDay(THIRD, 30, 0);

        var allowance = grace.apply(employee.getId(), FOURTH, 20);

        assertThat(allowance.graceUsedMinutes()).isZero();
        assertThat(allowance.lateMinutes()).isEqualTo(20);
    }

    @Test
    @DisplayName("a single very late arrival can spend the whole month's allowance")
    void oneLateMorningCanSpendItAll() {
        var allowance = grace.apply(employee.getId(), THIRD, 90);

        assertThat(allowance.graceUsedMinutes()).isEqualTo(30);
        assertThat(allowance.lateMinutes()).isEqualTo(60);
        assertThat(grace.remainingFor(employee.getId(), THIRD)).isEqualTo(30);  // not yet saved
    }

    @Test
    @DisplayName("the allowance resets with the calendar month")
    void resetsMonthly() {
        recordDay(THIRD, 30, 0);

        assertThat(grace.remainingFor(employee.getId(), FOURTH)).isZero();
        assertThat(grace.remainingFor(employee.getId(), NEXT_MONTH)).isEqualTo(30);

        var december = grace.apply(employee.getId(), NEXT_MONTH, 10);
        assertThat(december.graceUsedMinutes()).isEqualTo(10);
        assertThat(december.lateMinutes()).isZero();
    }

    @Test
    @DisplayName("re-settling the same day does not charge the month twice")
    void reSettlementIsIdempotent() {
        // The day already spent 10. Settling it again — a corrected punch, a
        // regularisation — must see 30 available, not 20, or every correction
        // would quietly eat the allowance.
        recordDay(THIRD, 10, 0);

        var again = grace.apply(employee.getId(), THIRD, 10);

        assertThat(again.graceUsedMinutes()).isEqualTo(10);
        assertThat(again.lateMinutes()).isZero();
    }

    @Test
    @DisplayName("one employee's lateness does not spend another's allowance")
    void allowanceIsPerEmployee() {
        Employee other = new Employee();
        other.setEmployeeCode("EMP9002");
        other.setFirstName("Bikram");
        other.setLastName("Sen");
        other.setOfficialEmail("bikram.grace@company.com");
        other.setJoiningDate(LocalDate.of(2020, 1, 1));
        other.setOffice(employee.getOffice());
        other.setEmploymentType(EmploymentType.PERMANENT);
        other = employeeRepository.save(other);

        recordDay(THIRD, 30, 0);

        assertThat(grace.remainingFor(other.getId(), THIRD)).isEqualTo(30);
    }

    @Test
    @DisplayName("arriving on time spends nothing")
    void onTime() {
        var allowance = grace.apply(employee.getId(), THIRD, 0);

        assertThat(allowance.graceUsedMinutes()).isZero();
        assertThat(allowance.lateMinutes()).isZero();
        assertThat(grace.remainingFor(employee.getId(), THIRD)).isEqualTo(30);
    }

    @Test
    @DisplayName("setting the allowance to zero records every late minute")
    void noAllowanceConfigured() {
        setMonthlyGrace("0");

        var allowance = grace.apply(employee.getId(), THIRD, 8);

        assertThat(allowance.graceUsedMinutes()).isZero();
        assertThat(allowance.lateMinutes()).isEqualTo(8);
    }

    @Test
    @DisplayName("an unreadable setting falls back to thirty rather than failing a punch")
    void unreadableSetting() {
        setMonthlyGrace("half an hour");

        assertThat(grace.monthlyGraceMinutes()).isEqualTo(30);

        var allowance = grace.apply(employee.getId(), THIRD, 10);
        assertThat(allowance.lateMinutes()).isZero();
    }
}
