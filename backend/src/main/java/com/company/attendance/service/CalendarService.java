package com.company.attendance.service;

import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Holiday;
import com.company.attendance.entity.WeeklyOff;
import com.company.attendance.enums.DayType;
import com.company.attendance.repository.HolidayRepository;
import com.company.attendance.repository.WeeklyOffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides what kind of day a date is for a given employee.
 *
 * <p>Punch-in needs this before it can say whether a punch is allowed, and the
 * end-of-day job needs it before it can mark anyone absent — marking someone
 * absent on a public holiday is the kind of error that erodes trust in the
 * whole system.
 */
@Service
public class CalendarService {

    /** Most specific scope wins, same ordering as policy resolution. */
    private static final Map<String, Integer> SCOPE_SPECIFICITY = Map.of(
            "EMPLOYEE", 50,
            "DEPARTMENT", 40,
            "SHIFT", 30,
            "OFFICE", 20,
            "GLOBAL", 10);

    private final HolidayRepository holidayRepository;
    private final WeeklyOffRepository weeklyOffRepository;

    public CalendarService(HolidayRepository holidayRepository, WeeklyOffRepository weeklyOffRepository) {
        this.holidayRepository = holidayRepository;
        this.weeklyOffRepository = weeklyOffRepository;
    }

    /**
     * A holiday outranks a weekly off, so a holiday falling on a Sunday still
     * reports as a holiday rather than disappearing into the weekend.
     */
    @Transactional(readOnly = true)
    public DayType resolveDayType(Employee employee, LocalDate date) {
        if (!findHoliday(employee, date).isEmpty()) {
            return DayType.HOLIDAY;
        }
        if (isWeeklyOff(employee, date)) {
            return DayType.WEEKLY_OFF;
        }
        return DayType.WORKING;
    }

    /**
     * The same decision for many employees at once.
     *
     * <p>The single-employee call costs two queries. A dashboard asking it per
     * person over a few hundred employees is the classic N+1, and it is the
     * one query pattern on the busiest screen in the product — so the rules are
     * read once here and applied in memory. The logic itself is shared with
     * {@link #resolveDayType}: duplicating the specificity ordering is exactly
     * how a dashboard and a punch screen start disagreeing about whether
     * Saturday is a working day.
     */
    @Transactional(readOnly = true)
    public Map<Long, DayType> resolveDayTypes(Collection<Employee> employees, LocalDate date) {
        boolean isHoliday = !holidayRepository.findByHolidayDateAndActiveTrue(date).isEmpty();
        List<WeeklyOff> applicableRules = weeklyOffRepository.findAllActive().stream()
                .filter(rule -> rule.appliesTo(date))
                .toList();

        Map<Long, DayType> result = new HashMap<>();
        for (Employee employee : employees) {
            if (isHoliday) {
                result.put(employee.getId(), DayType.HOLIDAY);
                continue;
            }
            boolean weeklyOff = applicableRules.stream()
                    .filter(rule -> matchesScope(rule, employee))
                    .max(Comparator.comparingInt(rule -> specificity(rule.getScopeType())))
                    .isPresent();
            result.put(employee.getId(), weeklyOff ? DayType.WEEKLY_OFF : DayType.WORKING);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<Holiday> findHoliday(Employee employee, LocalDate date) {
        // Office-specific holiday calendars land with the HR module; until then
        // every active holiday applies company-wide.
        return holidayRepository.findByHolidayDateAndActiveTrue(date);
    }

    @Transactional(readOnly = true)
    public List<Holiday> findHolidaysBetween(LocalDate from, LocalDate to) {
        return holidayRepository.findByHolidayDateBetweenAndActiveTrueOrderByHolidayDateAsc(from, to);
    }

    @Transactional(readOnly = true)
    public boolean isWeeklyOff(Employee employee, LocalDate date) {
        return resolveWeeklyOffRule(employee, date).isPresent();
    }

    /** True when the applicable rule marks the day a half working day. */
    @Transactional(readOnly = true)
    public boolean isHalfDayWeeklyOff(Employee employee, LocalDate date) {
        return resolveWeeklyOffRule(employee, date).map(WeeklyOff::isHalfDay).orElse(false);
    }

    /**
     * The single weekly-off rule that governs a date.
     *
     * <p>Rules are filtered to those that actually apply to this employee and
     * date, then the most specific one wins. Without picking a single winner, a
     * department rule that <em>removes</em> a company-wide Saturday off could
     * not be expressed.
     */
    private Optional<WeeklyOff> resolveWeeklyOffRule(Employee employee, LocalDate date) {
        return weeklyOffRepository.findAllActive().stream()
                .filter(rule -> rule.appliesTo(date))
                .filter(rule -> matchesScope(rule, employee))
                .max(Comparator.comparingInt(rule -> specificity(rule.getScopeType())));
    }

    private boolean matchesScope(WeeklyOff rule, Employee employee) {
        String scope = rule.getScopeType();

        if ("GLOBAL".equals(scope)) {
            return true;
        }
        if (employee == null) {
            return false;
        }

        return switch (scope) {
            case "EMPLOYEE" -> employee.getId().equals(rule.getScopeId());
            case "DEPARTMENT" -> employee.getDepartment() != null
                    && employee.getDepartment().getId().equals(rule.getScopeId());
            case "OFFICE" -> employee.getOffice() != null
                    && employee.getOffice().getId().equals(rule.getScopeId());
            default -> false;
        };
    }

    private int specificity(String scopeType) {
        return SCOPE_SPECIFICITY.getOrDefault(scopeType, 0);
    }
}
