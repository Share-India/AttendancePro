package com.company.attendance.integration;

import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeShift;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.ShiftDayOverride;
import com.company.attendance.enums.EmploymentType;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.ShiftDayOverrideRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.service.ShiftResolutionService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The short Saturday: 10:00 to 14:30 for everyone, as an expected window
 * rather than a guideline.
 *
 * <p>The point of routing every caller through {@link ShiftResolutionService}
 * is that lateness is measured against 10:00 on a Saturday and 09:30 on a
 * weekday. These tests pin the resolution; the arithmetic on top of it is
 * already covered by the attendance calculation tests, which take whatever
 * shift they are handed.
 */
@SpringBootTest
@ActiveProfiles("test")
class SaturdayShiftIT {

    private static final int SATURDAY = 6;

    @Autowired ShiftResolutionService shiftResolutionService;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ShiftDayOverrideRepository overrideRepository;
    @Autowired EmployeeShiftRepository employeeShiftRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Employee employee;
    private Shift general;
    private Shift saturday;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        Office office = new Office();
        office.setCode("HO");
        office.setName("Head Office");
        office.setTimezone("Asia/Kolkata");
        office = officeRepository.save(office);

        general = shift("GEN", "General", LocalTime.of(9, 30), LocalTime.of(18, 30), (short) 480, (short) 480, (short) 240);
        saturday = shift("SAT-HALF", "Saturday Half Day",
                LocalTime.of(10, 0), LocalTime.of(14, 30), (short) 270, (short) 270, (short) 135);

        employee = new Employee();
        employee.setEmployeeCode("EMP9100");
        employee.setFirstName("Asha");
        employee.setLastName("Rao");
        employee.setOfficialEmail("asha.sat@example.com");
        employee.setJoiningDate(LocalDate.of(2020, 1, 1));
        employee.setOffice(office);
        employee.setEmploymentType(EmploymentType.PERMANENT);
        employee = employeeRepository.save(employee);

        EmployeeShift assignment = new EmployeeShift();
        assignment.setEmployee(employee);
        assignment.setShift(general);
        assignment.setEffectiveFrom(LocalDate.of(2020, 1, 1));
        employeeShiftRepository.save(assignment);
    }

    private Shift shift(String code, String name, LocalTime start, LocalTime end,
                        short standard, short fullDay, short halfDay) {
        Shift s = new Shift();
        s.setCode(code);
        s.setName(name);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setStandardWorkMinutes(standard);
        s.setFullDayMinutes(fullDay);
        s.setHalfDayMinutes(halfDay);
        return shiftRepository.save(s);
    }

    private ShiftDayOverride override(String scope, Long scopeId, int day, Shift target) {
        ShiftDayOverride o = new ShiftDayOverride();
        o.setScopeType(scope);
        o.setScopeId(scopeId);
        o.setDayOfWeek((byte) day);
        o.setShift(target);
        return overrideRepository.save(o);
    }

    @Test
    @DisplayName("Saturday resolves to the short shift, weekdays keep the assignment")
    void saturdayUsesTheOverride() {
        override("GLOBAL", null, SATURDAY, saturday);

        LocalDate sat = LocalDate.of(2026, 11, 7);
        LocalDate mon = LocalDate.of(2026, 11, 9);
        assertThat(sat.getDayOfWeek().getValue()).isEqualTo(SATURDAY);

        Shift onSaturday = shiftResolutionService.require(employee, sat);
        assertThat(onSaturday.getCode()).isEqualTo("SAT-HALF");
        assertThat(onSaturday.getStartTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(onSaturday.getEndTime()).isEqualTo(LocalTime.of(14, 30));

        assertThat(shiftResolutionService.require(employee, mon).getCode()).isEqualTo("GEN");
    }

    @Test
    @DisplayName("the short Saturday's thresholds are scaled, so a full Saturday is a full day")
    void saturdayThresholdsAreScaled() {
        override("GLOBAL", null, SATURDAY, saturday);
        Shift sat = shiftResolutionService.require(employee, LocalDate.of(2026, 11, 7));

        // 10:00 to 14:30 is 270 minutes. Against the 480 default, somebody who
        // worked the whole of their Saturday would come out as a half day.
        assertThat((int) sat.getFullDayMinutes()).isEqualTo(270);
        assertThat((int) sat.getHalfDayMinutes()).isEqualTo(135);
        assertThat(java.time.Duration.between(sat.getStartTime(), sat.getEndTime()).toMinutes())
                .isEqualTo((long) sat.getFullDayMinutes());
    }

    @Test
    @DisplayName("an override applies to someone with no assignment at all")
    void overrideDoesNotNeedAnAssignment() {
        override("GLOBAL", null, SATURDAY, saturday);
        employeeShiftRepository.deleteAll();

        assertThat(shiftResolutionService.resolve(employee, LocalDate.of(2026, 11, 7)))
                .map(Shift::getCode).contains("SAT-HALF");
        // ...but a weekday still has nothing to fall back on.
        assertThat(shiftResolutionService.resolve(employee, LocalDate.of(2026, 11, 9))).isEmpty();
    }

    @Test
    @DisplayName("a more specific override wins over the company-wide one")
    void specificityWins() {
        Shift lateSaturday = shift("SAT-LATE", "Late Saturday",
                LocalTime.of(12, 0), LocalTime.of(16, 30), (short) 270, (short) 270, (short) 135);

        override("GLOBAL", null, SATURDAY, saturday);
        override("EMPLOYEE", employee.getId(), SATURDAY, lateSaturday);

        assertThat(shiftResolutionService.require(employee, LocalDate.of(2026, 11, 7)).getCode())
                .isEqualTo("SAT-LATE");
    }

    @Test
    @DisplayName("an inactive or out-of-window override is ignored")
    void inactiveAndExpiredOverridesAreIgnored() {
        ShiftDayOverride rule = override("GLOBAL", null, SATURDAY, saturday);
        rule.setActive(false);
        overrideRepository.save(rule);

        assertThat(shiftResolutionService.require(employee, LocalDate.of(2026, 11, 7)).getCode())
                .isEqualTo("GEN");

        rule.setActive(true);
        rule.setEffectiveFrom(LocalDate.of(2026, 12, 1));
        overrideRepository.save(rule);

        assertThat(shiftResolutionService.require(employee, LocalDate.of(2026, 11, 7)).getCode())
                .as("before the override starts").isEqualTo("GEN");
        assertThat(shiftResolutionService.require(employee, LocalDate.of(2026, 12, 5)).getCode())
                .as("after it starts").isEqualTo("SAT-HALF");
    }

    @Test
    @DisplayName("no override leaves the date-ranged assignment exactly as it was")
    void withoutOverridesNothingChanges() {
        for (LocalDate date : new LocalDate[]{
                LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 13)}) {
            assertThat(shiftResolutionService.require(employee, date).getCode()).isEqualTo("GEN");
        }
    }
}
