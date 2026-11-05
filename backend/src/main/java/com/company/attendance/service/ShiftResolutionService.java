package com.company.attendance.service;

import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.ShiftDayOverride;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.ShiftDayOverrideRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * The one place that answers "which shift governs this employee on this date".
 *
 * <p>Before this existed, eight call sites each asked
 * {@code employeeShiftRepository.findCurrentFor(...)} directly. That was fine
 * while a shift was purely a date-ranged assignment, but a weekday override
 * has to be applied by every one of them or the punch screen and the
 * dashboard start disagreeing about when somebody was late — and a
 * disagreement between those two is close to impossible to notice, because
 * each looks right on its own.
 *
 * <p>Resolution order:
 *
 * <ol>
 *   <li>A {@link ShiftDayOverride} matching the date's weekday. Most specific
 *       scope wins, the same precedence the calendar uses.</li>
 *   <li>Otherwise the date-ranged assignment from {@code employee_shifts}.</li>
 * </ol>
 *
 * <p>An override does not care whether the employee has an assignment: a
 * Saturday shift applies to whoever works that Saturday. It is deliberately
 * not consulted for a day the calendar has already decided is a weekly off —
 * that decision happens before anything here is asked.
 */
@Service
public class ShiftResolutionService {

    /** Most specific scope wins, the same ordering CalendarService uses. */
    private static final Map<String, Integer> SCOPE_SPECIFICITY = Map.of(
            "EMPLOYEE", 50,
            "DEPARTMENT", 40,
            "OFFICE", 20,
            "GLOBAL", 10);

    private final EmployeeShiftRepository employeeShiftRepository;
    private final ShiftDayOverrideRepository shiftDayOverrideRepository;

    public ShiftResolutionService(EmployeeShiftRepository employeeShiftRepository,
                                  ShiftDayOverrideRepository shiftDayOverrideRepository) {
        this.employeeShiftRepository = employeeShiftRepository;
        this.shiftDayOverrideRepository = shiftDayOverrideRepository;
    }

    @Transactional(readOnly = true)
    public Optional<Shift> resolve(Employee employee, LocalDate date) {
        Optional<Shift> override = shiftDayOverrideRepository.findAllActive().stream()
                .filter(rule -> rule.appliesTo(date))
                .filter(rule -> matchesScope(rule, employee))
                .max(Comparator.comparingInt(rule -> specificity(rule.getScopeType())))
                .map(ShiftDayOverride::getShift);

        if (override.isPresent()) {
            return override;
        }

        return employeeShiftRepository.findCurrentFor(employee.getId(), date)
                .map(assignment -> assignment.getShift());
    }

    /**
     * The same answer, or the error the punch flow has always raised. Kept
     * identical in wording because it is shown to the employee.
     */
    @Transactional(readOnly = true)
    public Shift require(Employee employee, LocalDate date) {
        return resolve(employee, date)
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_SHIFT_ASSIGNED,
                        "No shift is assigned to you for today, so attendance cannot be recorded. "
                                + "Ask HR to assign one."));
    }

    private boolean matchesScope(ShiftDayOverride rule, Employee employee) {
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
