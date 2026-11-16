package com.company.attendance.service;

import com.company.attendance.entity.CompanySetting;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Holiday;
import com.company.attendance.entity.WeeklyOff;
import com.company.attendance.enums.DayType;
import com.company.attendance.repository.CompanySettingRepository;
import com.company.attendance.repository.HolidayRepository;
import com.company.attendance.repository.WeeklyOffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CalendarService.class);

    /** Most specific scope wins, same ordering as policy resolution. */
    private static final Map<String, Integer> SCOPE_SPECIFICITY = Map.of(
            "EMPLOYEE", 50,
            "DEPARTMENT", 40,
            "SHIFT", 30,
            "OFFICE", 20,
            "GLOBAL", 10);

    private final HolidayRepository holidayRepository;
    private final WeeklyOffRepository weeklyOffRepository;
    private final CompanySettingRepository companySettingRepository;

    public CalendarService(HolidayRepository holidayRepository,
                           WeeklyOffRepository weeklyOffRepository,
                           CompanySettingRepository companySettingRepository) {
        this.holidayRepository = holidayRepository;
        this.weeklyOffRepository = weeklyOffRepository;
        this.companySettingRepository = companySettingRepository;
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

    /**
     * The same decision for many employees across a whole range of dates.
     *
     * <p>Two queries for the entire range, not two per day. The per-day call
     * above is already the fix for an N+1 across employees; this is the fix
     * for the same shape across dates, which a report covering a year would
     * otherwise hit six hundred times.
     *
     * <p>Rules are read once and applied in memory, by the same specificity
     * ordering as everywhere else — a salary sheet and a punch screen
     * disagreeing about whether a Saturday was a working day is exactly the
     * bug this shares code to avoid.
     *
     * @return day type per date per employee id; every date in the range is
     *         present, and every employee appears under each date
     */
    @Transactional(readOnly = true)
    public Map<LocalDate, Map<Long, DayType>> resolveDayTypes(Collection<Employee> employees,
                                                              LocalDate from, LocalDate to) {
        Set<LocalDate> holidays = holidayRepository
                .findByHolidayDateBetweenAndActiveTrueOrderByHolidayDateAsc(from, to).stream()
                .map(Holiday::getHolidayDate)
                .collect(Collectors.toSet());

        List<WeeklyOff> rules = weeklyOffRepository.findAllActive();

        Map<LocalDate, Map<Long, DayType>> result = new LinkedHashMap<>();
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = cursor.plusDays(1)) {
            final LocalDate date = cursor;
            Map<Long, DayType> forDate = new HashMap<>();
            boolean isHoliday = holidays.contains(date);

            List<WeeklyOff> applicable = isHoliday ? List.of()
                    : rules.stream().filter(rule -> rule.appliesTo(date)).toList();

            for (Employee employee : employees) {
                if (isHoliday) {
                    forDate.put(employee.getId(), DayType.HOLIDAY);
                    continue;
                }
                boolean weeklyOff = applicable.stream()
                        .anyMatch(rule -> matchesScope(rule, employee));
                forDate.put(employee.getId(), weeklyOff ? DayType.WEEKLY_OFF : DayType.WORKING);
            }
            result.put(date, forDate);
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


    /**
     * Whether attendance was being recorded on this date.
     *
     * <p>A working day with no punch is inferred ABSENT, which is correct once
     * people are using the application and wrong before they are. Days before
     * {@code attendance.tracking_start_date} are outside the system's reach:
     * not absent, not counted in the attendance percentage, and consuming no
     * grace. See V26.
     *
     * <p>No setting means the system has always been recording — the right
     * answer for a fresh install and for every environment that never
     * migrated from something else.
     */
    @Transactional(readOnly = true)
    public boolean isTracked(LocalDate date) {
        LocalDate start = trackingStartDate();
        return start == null || !date.isBefore(start);
    }

    /** The configured start, or null when attendance has always been recorded. */
    public LocalDate trackingStartDate() {
        return companySettingRepository.findBySettingKey("attendance.tracking_start_date")
                .map(CompanySetting::getSettingValue)
                .filter(value -> value != null && !value.isBlank())
                .map(value -> {
                    try {
                        return LocalDate.parse(value.trim());
                    } catch (DateTimeParseException e) {
                        // Treated as absent rather than thrown: a typo here
                        // must not stop the calendar rendering, and falling
                        // back to "always tracked" is the pre-existing
                        // behaviour rather than a new one.
                        log.warn("attendance.tracking_start_date is not a date: '{}'. "
                                + "Treating attendance as always tracked.", value);
                        return null;
                    }
                })
                .orElse(null);
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
