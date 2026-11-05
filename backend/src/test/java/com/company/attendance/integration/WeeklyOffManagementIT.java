package com.company.attendance.integration;

import com.company.attendance.dto.request.master.WeeklyOffRequest;
import com.company.attendance.dto.response.master.WeeklyOffResponse;
import com.company.attendance.entity.Department;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Office;
import com.company.attendance.enums.DayType;
import com.company.attendance.enums.EmploymentType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.DepartmentRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.service.CalendarService;
import com.company.attendance.service.WeeklyOffService;
import com.company.attendance.support.DatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Weekly offs, and the alternate-Saturday roster they exist for.
 *
 * <p>November 2026 is the month used throughout because its Saturdays fall on
 * the 7th, 14th, 21st and 28th — four of them, with no fifth to complicate the
 * arithmetic. {@link #fifthSaturdayIsWorkedByEverybody()} uses January 2027,
 * which has five.
 */
@SpringBootTest
@ActiveProfiles("test")
class WeeklyOffManagementIT {

    private static final int SATURDAY = 6;

    @Autowired WeeklyOffService weeklyOffService;
    @Autowired CalendarService calendarService;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired DepartmentRepository departmentRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Employee groupA;
    private Employee groupB;
    private Office office;

    @BeforeEach
    void setUp() {
        databaseCleaner.clean();

        office = new Office();
        office.setCode("HO");
        office.setName("Head Office");
        office.setTimezone("Asia/Kolkata");
        office = officeRepository.save(office);

        groupA = employee("EMP-A", "Asha", "Rao");
        groupB = employee("EMP-B", "Bikram", "Sen");
    }

    private Employee employee(String code, String first, String last) {
        Employee e = new Employee();
        e.setEmployeeCode(code);
        e.setFirstName(first);
        e.setLastName(last);
        e.setOfficialEmail(code.toLowerCase() + "@example.com");
        e.setJoiningDate(LocalDate.of(2020, 1, 1));
        e.setOffice(office);
        e.setEmploymentType(EmploymentType.PERMANENT);
        return employeeRepository.save(e);
    }

    private WeeklyOffRequest forEmployee(Employee e, int week) {
        return new WeeklyOffRequest("EMPLOYEE", e.getId(), SATURDAY, week, null, null, null);
    }

    @Test
    @DisplayName("the alternate-Saturday roster: 1st and 3rd off against 2nd and 4th")
    void alternateSaturdayRoster() {
        weeklyOffService.create(forEmployee(groupA, 1));
        weeklyOffService.create(forEmployee(groupA, 3));
        weeklyOffService.create(forEmployee(groupB, 2));
        weeklyOffService.create(forEmployee(groupB, 4));

        LocalDate sat1 = LocalDate.of(2026, 11, 7);
        LocalDate sat2 = LocalDate.of(2026, 11, 14);
        LocalDate sat3 = LocalDate.of(2026, 11, 21);
        LocalDate sat4 = LocalDate.of(2026, 11, 28);

        assertThat(calendarService.resolveDayType(groupA, sat1)).isEqualTo(DayType.WEEKLY_OFF);
        assertThat(calendarService.resolveDayType(groupA, sat2)).isEqualTo(DayType.WORKING);
        assertThat(calendarService.resolveDayType(groupA, sat3)).isEqualTo(DayType.WEEKLY_OFF);
        assertThat(calendarService.resolveDayType(groupA, sat4)).isEqualTo(DayType.WORKING);

        assertThat(calendarService.resolveDayType(groupB, sat1)).isEqualTo(DayType.WORKING);
        assertThat(calendarService.resolveDayType(groupB, sat2)).isEqualTo(DayType.WEEKLY_OFF);
        assertThat(calendarService.resolveDayType(groupB, sat3)).isEqualTo(DayType.WORKING);
        assertThat(calendarService.resolveDayType(groupB, sat4)).isEqualTo(DayType.WEEKLY_OFF);
    }

    @Test
    @DisplayName("a fifth Saturday matches no rule, so everybody works it")
    void fifthSaturdayIsWorkedByEverybody() {
        weeklyOffService.create(forEmployee(groupA, 1));
        weeklyOffService.create(forEmployee(groupA, 3));
        weeklyOffService.create(forEmployee(groupB, 2));
        weeklyOffService.create(forEmployee(groupB, 4));

        // January 2027: Saturdays on 2, 9, 16, 23 and 30.
        LocalDate fifth = LocalDate.of(2027, 1, 30);
        assertThat(fifth.getDayOfWeek().getValue()).isEqualTo(SATURDAY);

        assertThat(calendarService.resolveDayType(groupA, fifth)).isEqualTo(DayType.WORKING);
        assertThat(calendarService.resolveDayType(groupB, fifth)).isEqualTo(DayType.WORKING);
    }

    @Test
    @DisplayName("a company-wide Saturday rule cannot be carved out, which is why there must not be one")
    void globalRuleSwallowsTheRoster() {
        // This is the trap the roster has to avoid, pinned as a test so nobody
        // "fixes" the roster by adding a convenient global rule. Every rule
        // means off; precedence picks which off-rule wins, never that the day
        // is worked. So the global rule wins on the Saturdays the employee
        // rules do not cover, and the person is off every Saturday.
        weeklyOffService.create(new WeeklyOffRequest("GLOBAL", null, SATURDAY, null, null, null, null));
        weeklyOffService.create(forEmployee(groupA, 1));

        assertThat(calendarService.resolveDayType(groupA, LocalDate.of(2026, 11, 7)))
                .isEqualTo(DayType.WEEKLY_OFF);
        assertThat(calendarService.resolveDayType(groupA, LocalDate.of(2026, 11, 14)))
                .as("the global rule still applies on the 2nd Saturday")
                .isEqualTo(DayType.WEEKLY_OFF);
    }

    @Test
    @DisplayName("removing a rule returns those days to working days")
    void removingARuleRestoresTheDay() {
        WeeklyOffResponse rule = weeklyOffService.create(forEmployee(groupA, 1));
        LocalDate sat1 = LocalDate.of(2026, 11, 7);
        assertThat(calendarService.resolveDayType(groupA, sat1)).isEqualTo(DayType.WEEKLY_OFF);

        weeklyOffService.deactivate(rule.id());

        assertThat(calendarService.resolveDayType(groupA, sat1)).isEqualTo(DayType.WORKING);
        assertThatThrownBy(() -> weeklyOffService.deactivate(rule.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already inactive");
    }

    @Test
    @DisplayName("an identical rule is refused, because only one of them could ever apply")
    void duplicateRuleIsRefused() {
        weeklyOffService.create(forEmployee(groupA, 2));

        assertThatThrownBy(() -> weeklyOffService.create(forEmployee(groupA, 2)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");

        // A different week is a different rule, not a duplicate.
        assertThat(weeklyOffService.create(forEmployee(groupA, 4))).isNotNull();
    }

    @Test
    @DisplayName("a scoped rule needs a target, and the target has to exist")
    void scopeTargetIsValidated() {
        assertThatThrownBy(() -> weeklyOffService.create(
                new WeeklyOffRequest("EMPLOYEE", null, SATURDAY, 1, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("needs something to apply to");

        assertThatThrownBy(() -> weeklyOffService.create(
                new WeeklyOffRequest("EMPLOYEE", 999999L, SATURDAY, 1, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No employee with id");

        // GLOBAL takes no target, and a stray one is dropped rather than stored.
        WeeklyOffResponse global = weeklyOffService.create(
                new WeeklyOffRequest("GLOBAL", 123L, 7, null, null, null, null));
        assertThat(global.scopeId()).isNull();
        assertThat(global.scopeName()).isEqualTo("everyone");
    }

    @Test
    @DisplayName("a department rule reaches that department's people and nobody else")
    void departmentScopeApplies() {
        Department sales = new Department();
        sales.setCode("SALES");
        sales.setName("Sales");
        sales = departmentRepository.save(sales);

        groupA.setDepartment(sales);
        employeeRepository.save(groupA);

        weeklyOffService.create(new WeeklyOffRequest("DEPARTMENT", sales.getId(), SATURDAY, 2, null, null, null));

        LocalDate sat2 = LocalDate.of(2026, 11, 14);
        assertThat(calendarService.resolveDayType(groupA, sat2)).isEqualTo(DayType.WEEKLY_OFF);
        assertThat(calendarService.resolveDayType(groupB, sat2)).isEqualTo(DayType.WORKING);
    }

    @Test
    @DisplayName("effective dates bound when a rule applies")
    void effectiveDatesAreHonoured() {
        weeklyOffService.create(new WeeklyOffRequest("EMPLOYEE", groupA.getId(), SATURDAY, null,
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 24), null));

        assertThat(calendarService.resolveDayType(groupA, LocalDate.of(2026, 11, 7)))
                .as("before it starts").isEqualTo(DayType.WORKING);
        assertThat(calendarService.resolveDayType(groupA, LocalDate.of(2026, 11, 14)))
                .as("inside the window").isEqualTo(DayType.WEEKLY_OFF);
        assertThat(calendarService.resolveDayType(groupA, LocalDate.of(2026, 11, 28)))
                .as("after it ends").isEqualTo(DayType.WORKING);

        assertThatThrownBy(() -> weeklyOffService.create(new WeeklyOffRequest(
                "EMPLOYEE", groupB.getId(), SATURDAY, null,
                LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 10), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be before");
    }

    @Test
    @DisplayName("rules read back broadest-scope first, with a human summary")
    void listingIsOrderedAndLabelled() {
        weeklyOffService.create(forEmployee(groupA, 3));
        weeklyOffService.create(new WeeklyOffRequest("GLOBAL", null, 7, null, null, null, null));

        List<WeeklyOffResponse> all = weeklyOffService.findAll(true);

        assertThat(all).extracting(WeeklyOffResponse::scopeType)
                .containsExactly("GLOBAL", "EMPLOYEE");
        assertThat(all.get(0).summary()).isEqualTo("Every Sunday");
        assertThat(all.get(1).summary()).isEqualTo("3rd Saturday");
        assertThat(all.get(1).scopeName()).contains("Asha").contains("EMP-A");
    }
}
