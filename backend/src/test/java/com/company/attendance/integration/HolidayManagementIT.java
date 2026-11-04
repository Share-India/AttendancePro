package com.company.attendance.integration;

import com.company.attendance.dto.request.master.HolidayRequest;
import com.company.attendance.dto.response.master.HolidayResponse;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Office;
import com.company.attendance.enums.DayType;
import com.company.attendance.enums.EmploymentType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.HolidayRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.service.CalendarService;
import com.company.attendance.service.HolidayService;
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
 * The holiday calendar.
 *
 * <p>The case that matters most is {@link #declaredHolidayExcusesTheDay()}:
 * the table and the read path existed for a long time with nothing able to
 * write to them, so the only proof this feature works is that a holiday
 * declared through the service actually changes what the calendar reports.
 * Everything else here guards a specific way the data can go wrong.
 */
@SpringBootTest
@ActiveProfiles("test")
class HolidayManagementIT {

    @Autowired HolidayService holidayService;
    @Autowired HolidayRepository holidayRepository;
    @Autowired CalendarService calendarService;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private Employee employee;

    @BeforeEach
    void setUp() {
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
        employee.setOfficialEmail("asha.rao@example.com");
        employee.setJoiningDate(LocalDate.of(2020, 1, 1));
        employee.setOffice(office);
        employee.setEmploymentType(EmploymentType.PERMANENT);
        employee = employeeRepository.save(employee);
    }

    private HolidayRequest request(String name, LocalDate date) {
        return new HolidayRequest(name, date, "COMPANY", null, null);
    }

    @Test
    @DisplayName("a declared holiday is what makes the day a holiday on the calendar")
    void declaredHolidayExcusesTheDay() {
        LocalDate diwali = LocalDate.of(2026, 11, 8);
        assertThat(calendarService.resolveDayType(employee, diwali)).isNotEqualTo(DayType.HOLIDAY);

        holidayService.create(request("Diwali", diwali));

        assertThat(calendarService.resolveDayType(employee, diwali)).isEqualTo(DayType.HOLIDAY);
    }

    @Test
    @DisplayName("holiday_year is derived from the date, never supplied")
    void holidayYearIsDerived() {
        HolidayResponse created = holidayService.create(request("Republic Day", LocalDate.of(2027, 1, 26)));

        assertThat(created.holidayYear()).isEqualTo(2027);
        // The year-filtered query is the one that silently misses rows whose
        // denormalised year disagrees with the date, so assert through it.
        assertThat(holidayService.findAll(2027, true)).extracting(HolidayResponse::name)
                .containsExactly("Republic Day");
        assertThat(holidayService.findAll(2026, true)).isEmpty();
    }

    @Test
    @DisplayName("moving a holiday to another year moves it between years, not into both")
    void movingAcrossYearsUpdatesTheDerivedYear() {
        HolidayResponse created = holidayService.create(request("Floating", LocalDate.of(2026, 12, 31)));

        holidayService.update(created.id(), request("Floating", LocalDate.of(2027, 1, 1)));

        assertThat(holidayService.findAll(2026, true)).isEmpty();
        assertThat(holidayService.findAll(2027, true)).hasSize(1);
    }

    @Test
    @DisplayName("the same day cannot be declared twice")
    void duplicateDateIsRejected() {
        LocalDate date = LocalDate.of(2026, 11, 8);
        holidayService.create(request("Diwali", date));

        assertThatThrownBy(() -> holidayService.create(request("Deepavali", date)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Diwali")
                .hasMessageContaining("already declared");
    }

    @Test
    @DisplayName("a withdrawn date can be declared again")
    void withdrawnDateIsFreeAgain() {
        LocalDate date = LocalDate.of(2026, 11, 8);
        HolidayResponse first = holidayService.create(request("Typo", date));
        holidayService.deactivate(first.id());

        HolidayResponse second = holidayService.create(request("Diwali", date));

        assertThat(second.name()).isEqualTo("Diwali");
        assertThat(calendarService.resolveDayType(employee, date)).isEqualTo(DayType.HOLIDAY);
    }

    @Test
    @DisplayName("withdrawing a holiday returns the day to a working day")
    void withdrawingRestoresTheWorkingDay() {
        LocalDate date = LocalDate.of(2026, 11, 8);
        HolidayResponse created = holidayService.create(request("Diwali", date));
        assertThat(calendarService.resolveDayType(employee, date)).isEqualTo(DayType.HOLIDAY);

        holidayService.deactivate(created.id());

        assertThat(calendarService.resolveDayType(employee, date)).isNotEqualTo(DayType.HOLIDAY);
        // Withdrawn, not deleted: the audit trail has to keep pointing at a row.
        assertThat(holidayRepository.findById(created.id())).isPresent();
    }

    @Test
    @DisplayName("withdrawing twice is refused rather than silently repeated")
    void doubleWithdrawIsRefused() {
        HolidayResponse created = holidayService.create(request("Diwali", LocalDate.of(2026, 11, 8)));
        holidayService.deactivate(created.id());

        assertThatThrownBy(() -> holidayService.deactivate(created.id()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already withdrawn");
    }

    @Test
    @DisplayName("OPTIONAL and RESTRICTED are refused, because nothing models an optional day")
    void onlyCompanyWideTypesAreAccepted() {
        // CalendarService excuses everyone from any active holiday whatever
        // its type, so accepting an optional one would hand the whole company
        // a day off that was meant to be each employee's own choice.
        // Asserted against the service, not the controller: the @Pattern on
        // the request record only runs where something applies @Valid, so the
        // service has to refuse these on its own account.
        for (String type : List.of("OPTIONAL", "RESTRICTED", "BANK_HOLIDAY", "")) {
            assertThatThrownBy(() -> holidayService.create(
                    new HolidayRequest("X", LocalDate.of(2026, 5, 1), type, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("NATIONAL, STATE or COMPANY");
        }

        for (String type : List.of("NATIONAL", "STATE", "COMPANY")) {
            assertThat(holidayService.create(new HolidayRequest(
                    type + " day", LocalDate.of(2026, 5, 1).plusDays(type.length()), type, null, null))
                    .holidayType()).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("activeOnly=false shows withdrawn holidays, the default hides them")
    void withdrawnHolidaysAreHiddenByDefault() {
        HolidayResponse kept = holidayService.create(request("Diwali", LocalDate.of(2026, 11, 8)));
        HolidayResponse gone = holidayService.create(request("Cancelled", LocalDate.of(2026, 11, 9)));
        holidayService.deactivate(gone.id());

        assertThat(holidayService.findAll(2026, true)).extracting(HolidayResponse::id)
                .containsExactly(kept.id());
        assertThat(holidayService.findAll(2026, false)).extracting(HolidayResponse::id)
                .containsExactlyInAnyOrder(kept.id(), gone.id());
    }

    @Test
    @DisplayName("a holiday falling on a weekly off still reports as a holiday")
    void holidayOutranksWeeklyOff() {
        // 2026-11-08 is a Sunday. Without the precedence rule in
        // CalendarService the holiday would vanish into the weekend.
        LocalDate sunday = LocalDate.of(2026, 11, 8);
        assertThat(sunday.getDayOfWeek().getValue()).isEqualTo(7);

        holidayService.create(request("Diwali", sunday));

        assertThat(calendarService.resolveDayType(employee, sunday)).isEqualTo(DayType.HOLIDAY);
    }
}
