package com.company.attendance.integration;

import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Office;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.EmploymentType;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.CompanySettingRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.service.EarlyExitAllowanceService;
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
 * The month's allowance of early departures.
 *
 * <p>Two a month, not one every evening. The difference is the whole point:
 * under the old per-day rule somebody could leave a quarter of an hour early
 * on every working day of the month — five and a half hours — and the system
 * recorded not one minute of it.
 *
 * <p>All dates sit inside one month so the allowance is unambiguous, and a
 * second month is used only to prove the reset.
 */
@SpringBootTest
@ActiveProfiles("test")
class EarlyExitAllowanceIT {

    /** Share India's rule: 15 minutes an occasion, two occasions a month. */
    private static final int GRACE = 15;
    private static final int ALLOWED = 2;

    private static final LocalDate THIRD = LocalDate.of(2026, 11, 3);
    private static final LocalDate FOURTH = LocalDate.of(2026, 11, 4);
    private static final LocalDate FIFTH = LocalDate.of(2026, 11, 5);
    private static final LocalDate SIXTH = LocalDate.of(2026, 11, 6);
    private static final LocalDate NEXT_MONTH = LocalDate.of(2026, 12, 1);

    @Autowired EarlyExitAllowanceService allowance;
    @Autowired AttendanceRepository attendanceRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired CompanySettingRepository companySettingRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Employee employee;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        Office office = new Office();
        office.setCode("HO");
        office.setName("Head Office");
        office.setTimezone("Asia/Kolkata");
        office = officeRepository.save(office);

        employee = new Employee();
        employee.setEmployeeCode("EMP9101");
        employee.setFirstName("Asha");
        employee.setLastName("Rao");
        employee.setOfficialEmail("asha.earlyexit@company.com");
        employee.setJoiningDate(LocalDate.of(2020, 1, 1));
        employee.setOffice(office);
        employee.setEmploymentType(EmploymentType.PERMANENT);
        employee = employeeRepository.save(employee);

        // company_settings survives DatabaseCleaner, so a go-live date left
        // by another class would make these dates untracked and quietly move
        // the start of the month.
        companySettingRepository.findBySettingKey("attendance.tracking_start_date")
                .ifPresent(companySettingRepository::delete);
    }

    /** A settled day that spent an occasion of the month's allowance. */
    private void recordDeparture(LocalDate date, int graceUsed, int earlyExitMinutes) {
        Attendance attendance = new Attendance();
        attendance.setEmployee(employee);
        attendance.setAttendanceDate(date);
        attendance.setEarlyExitGraceUsedMinutes(graceUsed);
        attendance.setEarlyExitMinutes(earlyExitMinutes);
        attendance.setAttendanceStatus(AttendanceStatus.PRESENT);
        attendanceRepository.save(attendance);
    }

    private EarlyExitAllowanceService.Allowance apply(LocalDate date, int rawMinutes) {
        return allowance.apply(employee.getId(), date, rawMinutes, GRACE, ALLOWED);
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("the first early departure of the month is forgiven")
    void theFirstIsForgiven() {
        var result = apply(THIRD, 10);

        assertThat(result.graceUsedMinutes()).isEqualTo(10);
        assertThat(result.earlyExitMinutes()).isZero();
    }

    @Test
    @DisplayName("the second is forgiven too")
    void theSecondIsForgiven() {
        recordDeparture(THIRD, 10, 0);

        var result = apply(FOURTH, 12);

        assertThat(result.graceUsedMinutes()).isEqualTo(12);
        assertThat(result.earlyExitMinutes()).isZero();
    }

    @Test
    @DisplayName("the third is recorded in full: the allowance is a month's, not a day's")
    void theThirdIsRecorded() {
        // This is the whole change. Under the old per-day rule all three of
        // these were forgiven, and so was a fourth, and a twentieth.
        recordDeparture(THIRD, 15, 0);
        recordDeparture(FOURTH, 15, 0);

        var result = apply(FIFTH, 12);

        assertThat(result.graceUsedMinutes()).isZero();
        assertThat(result.earlyExitMinutes()).isEqualTo(12);
    }

    @Test
    @DisplayName("a departure longer than the grace spends an occasion and records the rest")
    void aLongDepartureSpendsAnOccasion() {
        var result = apply(THIRD, 40);

        // Fifteen forgiven, twenty-five on the record, and one of the two
        // occasions gone — leaving early by a lot should not be cheaper in
        // allowance terms than leaving early by a little.
        assertThat(result.graceUsedMinutes()).isEqualTo(GRACE);
        assertThat(result.earlyExitMinutes()).isEqualTo(25);
    }

    @Test
    @DisplayName("settling the same day twice does not spend two occasions")
    void resettlingIsIdempotent() {
        recordDeparture(THIRD, 15, 0);
        recordDeparture(FOURTH, 15, 0);

        // The 4th is re-settled — a corrected punch-out, a regularisation.
        // Its own earlier spend must not count against it, or a correction
        // would push the day past an allowance it is already inside.
        var result = apply(FOURTH, 12);

        assertThat(result.graceUsedMinutes()).isEqualTo(12);
        assertThat(result.earlyExitMinutes()).isZero();
    }

    @Test
    @DisplayName("the allowance resets with the month")
    void itResetsNextMonth() {
        recordDeparture(THIRD, 15, 0);
        recordDeparture(FOURTH, 15, 0);
        recordDeparture(FIFTH, 0, 20);

        var result = apply(NEXT_MONTH, 10);

        assertThat(result.graceUsedMinutes()).isEqualTo(10);
        assertThat(result.earlyExitMinutes()).isZero();
    }

    @Test
    @DisplayName("a day already past the allowance does not spend one by being re-settled")
    void aRecordedDayStaysRecorded() {
        recordDeparture(THIRD, 15, 0);
        recordDeparture(FOURTH, 15, 0);
        recordDeparture(FIFTH, 0, 20);

        var result = apply(SIXTH, 10);

        assertThat(result.graceUsedMinutes()).isZero();
        assertThat(result.earlyExitMinutes()).isEqualTo(10);
    }

    @Test
    @DisplayName("staying past the shift end is not an early departure and spends nothing")
    void stayingLateSpendsNothing() {
        var result = apply(THIRD, 0);

        assertThat(result.graceUsedMinutes()).isZero();
        assertThat(result.earlyExitMinutes()).isZero();
    }

    @Test
    @DisplayName("an allowance of zero forgives nothing")
    void zeroForgivesNothing() {
        var result = allowance.apply(employee.getId(), THIRD, 10, GRACE, 0);

        assertThat(result.graceUsedMinutes()).isZero();
        assertThat(result.earlyExitMinutes()).isEqualTo(10);
    }

    @Test
    @DisplayName("the remaining count is what is left of the month's occasions")
    void remainingIsReported() {
        assertThat(allowance.remainingFor(employee.getId(), THIRD, ALLOWED)).isEqualTo(2);

        recordDeparture(THIRD, 15, 0);
        assertThat(allowance.remainingFor(employee.getId(), FOURTH, ALLOWED)).isEqualTo(1);

        recordDeparture(FOURTH, 15, 0);
        assertThat(allowance.remainingFor(employee.getId(), FIFTH, ALLOWED)).isZero();
    }

    @Test
    @DisplayName("one employee's departures do not spend another's allowance")
    void allowancesArePerEmployee() {
        recordDeparture(THIRD, 15, 0);
        recordDeparture(FOURTH, 15, 0);

        Employee other = new Employee();
        other.setEmployeeCode("EMP9102");
        other.setFirstName("Bhavin");
        other.setLastName("Shah");
        other.setOfficialEmail("bhavin.earlyexit@company.com");
        other.setJoiningDate(LocalDate.of(2020, 1, 1));
        other.setOffice(employee.getOffice());
        other.setEmploymentType(EmploymentType.PERMANENT);
        other = employeeRepository.save(other);

        var result = allowance.apply(other.getId(), FIFTH, 10, GRACE, ALLOWED);

        assertThat(result.graceUsedMinutes()).isEqualTo(10);
        assertThat(result.earlyExitMinutes()).isZero();
    }
}
