package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.master.WeeklyOffRequest;
import com.company.attendance.dto.response.master.WeeklyOffResponse;
import com.company.attendance.entity.WeeklyOff;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.exception.ResourceNotFoundException;
import com.company.attendance.repository.DepartmentRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.WeeklyOffRepository;
import com.company.attendance.service.WeeklyOffService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class WeeklyOffServiceImpl implements WeeklyOffService {

    private static final Map<Integer, String> ORDINAL = Map.of(
            1, "1st", 2, "2nd", 3, "3rd", 4, "4th", 5, "5th");

    private final WeeklyOffRepository weeklyOffRepository;
    private final OfficeRepository officeRepository;
    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;
    private final AuditLogService auditLogService;

    public WeeklyOffServiceImpl(WeeklyOffRepository weeklyOffRepository,
                                OfficeRepository officeRepository,
                                DepartmentRepository departmentRepository,
                                EmployeeRepository employeeRepository,
                                AuditLogService auditLogService) {
        this.weeklyOffRepository = weeklyOffRepository;
        this.officeRepository = officeRepository;
        this.departmentRepository = departmentRepository;
        this.employeeRepository = employeeRepository;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WeeklyOffResponse> findAll(boolean activeOnly) {
        return weeklyOffRepository.findAll().stream()
                .filter(rule -> !activeOnly || rule.isActive())
                // Broadest scope first, then by day, which is the order
                // someone reads a roster in: the company rule, then the
                // exceptions to it.
                .sorted(Comparator.comparingInt((WeeklyOff r) -> specificityOf(r.getScopeType()))
                        .thenComparingInt(WeeklyOff::getDayOfWeek)
                        .thenComparing(r -> r.getWeekOfMonth() == null ? 0 : r.getWeekOfMonth()))
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public WeeklyOffResponse findById(Long id) {
        return toResponse(requireRule(id));
    }

    @Override
    @Transactional
    public WeeklyOffResponse create(WeeklyOffRequest request) {
        WeeklyOff rule = new WeeklyOff();
        apply(rule, request);
        rule.setActive(request.active() == null || request.active());

        assertNotDuplicate(rule, null);

        WeeklyOff saved = weeklyOffRepository.save(rule);
        auditLogService.record(AuditAction.CREATE, "WeeklyOff", saved.getId(),
                "Added weekly off: " + summaryOf(saved) + " (" + scopeLabel(saved) + ")",
                null, snapshot(saved));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public WeeklyOffResponse update(Long id, WeeklyOffRequest request) {
        WeeklyOff rule = requireRule(id);
        Object before = snapshot(rule);

        apply(rule, request);
        if (request.active() != null) {
            rule.setActive(request.active());
        }
        assertNotDuplicate(rule, id);

        WeeklyOff saved = weeklyOffRepository.save(rule);
        // Both sides recorded: changing a roster rule changes which days the
        // affected people were excused on, including days already past,
        // because day types are derived when they are read.
        auditLogService.record(AuditAction.UPDATE, "WeeklyOff", saved.getId(),
                "Updated weekly off: " + summaryOf(saved), before, snapshot(saved));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deactivate(Long id) {
        WeeklyOff rule = requireRule(id);
        if (!rule.isActive()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION, "This rule is already inactive");
        }
        rule.setActive(false);
        weeklyOffRepository.save(rule);
        auditLogService.record(AuditAction.DEACTIVATE, "WeeklyOff", id,
                "Removed weekly off: " + summaryOf(rule) + " (" + scopeLabel(rule) + ")");
    }

    private void apply(WeeklyOff rule, WeeklyOffRequest request) {
        String scope = request.scopeType().trim().toUpperCase(Locale.ROOT);

        Long scopeId = request.scopeId();
        if ("GLOBAL".equals(scope)) {
            // A GLOBAL rule with a stray id would sort and display as though
            // it were targeted at something.
            scopeId = null;
        } else {
            if (scopeId == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "A " + scope + " rule needs something to apply to");
            }
            assertScopeTargetExists(scope, scopeId);
        }

        if (request.effectiveFrom() != null && request.effectiveTo() != null
                && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The end date cannot be before the start date");
        }

        rule.setScopeType(scope);
        rule.setScopeId(scopeId);
        rule.setDayOfWeek((byte) request.dayOfWeek().intValue());
        rule.setWeekOfMonth(request.weekOfMonth() == null
                ? null : (byte) request.weekOfMonth().intValue());
        rule.setEffectiveFrom(request.effectiveFrom());
        rule.setEffectiveTo(request.effectiveTo());
    }

    private void assertScopeTargetExists(String scope, Long scopeId) {
        boolean exists = switch (scope) {
            case "OFFICE" -> officeRepository.existsById(scopeId);
            case "DEPARTMENT" -> departmentRepository.existsById(scopeId);
            case "EMPLOYEE" -> employeeRepository.existsById(scopeId);
            default -> false;
        };
        if (!exists) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "No " + scope.toLowerCase(Locale.ROOT) + " with id " + scopeId);
        }
    }

    /**
     * Two identical rules are never meaningful — only one can ever win the
     * specificity comparison, so the other is invisible and will confuse
     * whoever reads the list later looking for why a day is off.
     */
    private void assertNotDuplicate(WeeklyOff candidate, Long excludingId) {
        weeklyOffRepository.findAll().stream()
                .filter(WeeklyOff::isActive)
                .filter(other -> !other.getId().equals(excludingId))
                .filter(other -> other.getScopeType().equals(candidate.getScopeType()))
                .filter(other -> java.util.Objects.equals(other.getScopeId(), candidate.getScopeId()))
                .filter(other -> other.getDayOfWeek() == candidate.getDayOfWeek())
                .filter(other -> java.util.Objects.equals(other.getWeekOfMonth(), candidate.getWeekOfMonth()))
                .findFirst()
                .ifPresent(other -> {
                    throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                            "That rule already exists: " + summaryOf(other)
                                    + " for " + scopeLabel(other));
                });
    }

    private WeeklyOff requireRule(Long id) {
        return weeklyOffRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("WeeklyOff", id));
    }

    private static int specificityOf(String scopeType) {
        return switch (scopeType) {
            case "EMPLOYEE" -> 4;
            case "DEPARTMENT" -> 3;
            case "OFFICE" -> 2;
            default -> 1;
        };
    }

    private static String dayName(int dayOfWeek) {
        return DayOfWeek.of(dayOfWeek).getDisplayName(TextStyle.FULL, Locale.UK);
    }

    private static String summaryOf(WeeklyOff rule) {
        String day = dayName(rule.getDayOfWeek());
        return rule.getWeekOfMonth() == null
                ? "Every " + day
                : ORDINAL.get((int) rule.getWeekOfMonth()) + " " + day;
    }

    private String scopeLabel(WeeklyOff rule) {
        if ("GLOBAL".equals(rule.getScopeType()) || rule.getScopeId() == null) {
            return "everyone";
        }
        return switch (rule.getScopeType()) {
            case "OFFICE" -> officeRepository.findById(rule.getScopeId())
                    .map(o -> o.getName()).orElse("office " + rule.getScopeId());
            case "DEPARTMENT" -> departmentRepository.findById(rule.getScopeId())
                    .map(d -> d.getName()).orElse("department " + rule.getScopeId());
            case "EMPLOYEE" -> employeeRepository.findById(rule.getScopeId())
                    .map(e -> e.getFirstName() + " " + e.getLastName() + " (" + e.getEmployeeCode() + ")")
                    .orElse("employee " + rule.getScopeId());
            default -> rule.getScopeType();
        };
    }

    private WeeklyOffResponse toResponse(WeeklyOff rule) {
        return new WeeklyOffResponse(
                rule.getId(),
                rule.getScopeType(),
                rule.getScopeId(),
                scopeLabel(rule),
                rule.getDayOfWeek(),
                dayName(rule.getDayOfWeek()),
                rule.getWeekOfMonth() == null ? null : (int) rule.getWeekOfMonth(),
                summaryOf(rule),
                rule.getEffectiveFrom(),
                rule.getEffectiveTo(),
                rule.isActive());
    }

    private Object snapshot(WeeklyOff rule) {
        return Map.of(
                "scopeType", rule.getScopeType(),
                "scopeId", String.valueOf(rule.getScopeId()),
                "dayOfWeek", String.valueOf(rule.getDayOfWeek()),
                "weekOfMonth", String.valueOf(rule.getWeekOfMonth()),
                "active", rule.isActive());
    }
}
