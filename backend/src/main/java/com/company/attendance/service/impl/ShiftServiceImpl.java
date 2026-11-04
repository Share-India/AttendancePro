package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.master.ShiftAssignmentRequest;
import com.company.attendance.dto.request.master.ShiftRequest;
import com.company.attendance.dto.response.master.ShiftAssignmentResponse;
import com.company.attendance.dto.response.master.ShiftResponse;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeShift;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.Shift;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.exception.DuplicateResourceException;
import com.company.attendance.exception.ResourceNotFoundException;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.service.ShiftService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class ShiftServiceImpl implements ShiftService {

    private final ShiftRepository shiftRepository;
    private final EmployeeShiftRepository employeeShiftRepository;
    private final EmployeeRepository employeeRepository;
    private final OfficeRepository officeRepository;
    private final AuditLogService auditLogService;

    public ShiftServiceImpl(ShiftRepository shiftRepository,
                            EmployeeShiftRepository employeeShiftRepository,
                            EmployeeRepository employeeRepository,
                            OfficeRepository officeRepository,
                            AuditLogService auditLogService) {
        this.shiftRepository = shiftRepository;
        this.employeeShiftRepository = employeeShiftRepository;
        this.employeeRepository = employeeRepository;
        this.officeRepository = officeRepository;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShiftResponse> findAll(boolean activeOnly) {
        List<Shift> shifts = activeOnly
                ? shiftRepository.findByActiveTrueOrderByNameAsc()
                : shiftRepository.findAll();
        return shifts.stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ShiftResponse findById(Long id) {
        return toResponse(requireShift(id));
    }

    @Override
    @Transactional
    public ShiftResponse create(ShiftRequest request) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (shiftRepository.existsByCode(code)) {
            throw new DuplicateResourceException("Shift", "code", code);
        }

        Shift shift = new Shift();
        shift.setCode(code);
        apply(shift, request);

        Shift saved = shiftRepository.save(shift);
        clearOtherDefaults(saved);

        auditLogService.record(AuditAction.CREATE, "Shift", saved.getId(),
                "Created shift " + saved.getName(), null, snapshot(saved));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public ShiftResponse update(Long id, ShiftRequest request) {
        Shift shift = requireShift(id);
        Object before = snapshot(shift);

        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (!code.equals(shift.getCode())) {
            if (shiftRepository.existsByCode(code)) {
                throw new DuplicateResourceException("Shift", "code", code);
            }
            shift.setCode(code);
        }

        apply(shift, request);

        Shift saved = shiftRepository.save(shift);
        clearOtherDefaults(saved);

        // Changing hour thresholds changes how past days would be recomputed,
        // so the before and after values go on the record.
        auditLogService.record(AuditAction.UPDATE, "Shift", saved.getId(),
                "Updated shift " + saved.getName(), before, snapshot(saved));
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deactivate(Long id) {
        Shift shift = requireShift(id);

        long assigned = employeeShiftRepository.countByShiftId(id);
        if (assigned > 0) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "This shift has " + assigned + " assignment(s). Reassign those employees first.");
        }
        if (shift.isDefaultShift()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Make another shift the default before deactivating this one");
        }

        shift.setActive(false);
        shiftRepository.save(shift);
        auditLogService.record(AuditAction.DEACTIVATE, "Shift", id, "Deactivated shift " + shift.getName());
    }

    // -----------------------------------------------------------------
    // Assignment
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public ShiftAssignmentResponse assign(Long employeeId, ShiftAssignmentRequest request) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "Employee not found: " + employeeId));
        Shift shift = requireShift(request.shiftId());

        if (!shift.isActive()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Shift " + shift.getName() + " is inactive and cannot be assigned");
        }
        if (request.effectiveTo() != null && request.effectiveTo().isBefore(request.effectiveFrom())) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "Effective-to cannot be before effective-from");
        }
        if (request.effectiveFrom().isBefore(employee.getJoiningDate())) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "A shift cannot start before the employee's joining date");
        }

        // Close any open-ended assignment the day before this one starts, so the
        // two never both claim a date. Without this, resolving the shift for a
        // day would be ambiguous and attendance would be computed twice over.
        employeeShiftRepository.findOpenEndedBefore(employeeId, request.effectiveFrom())
                .forEach(previous -> {
                    previous.setEffectiveTo(request.effectiveFrom().minusDays(1));
                    employeeShiftRepository.save(previous);
                });

        EmployeeShift assignment = new EmployeeShift();
        assignment.setEmployee(employee);
        assignment.setShift(shift);
        assignment.setEffectiveFrom(request.effectiveFrom());
        assignment.setEffectiveTo(request.effectiveTo());
        assignment.setNotes(request.notes());

        EmployeeShift saved = employeeShiftRepository.save(assignment);
        auditLogService.record(AuditAction.UPDATE, "EmployeeShift", saved.getId(),
                "Assigned " + shift.getName() + " to " + employee.getEmployeeCode()
                        + " from " + request.effectiveFrom(),
                null, Map.of("shift", shift.getCode(), "from", request.effectiveFrom().toString()));

        return toAssignmentResponse(saved, LocalDate.now());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShiftAssignmentResponse> findAssignmentHistory(Long employeeId) {
        LocalDate today = LocalDate.now();
        return employeeShiftRepository.findHistoryFor(employeeId).stream()
                .map(assignment -> toAssignmentResponse(assignment, today))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ShiftAssignmentResponse> findEffectiveShift(Long employeeId, LocalDate onDate) {
        return employeeShiftRepository.findCurrentFor(employeeId, onDate)
                .map(assignment -> toAssignmentResponse(assignment, onDate));
    }

    // -----------------------------------------------------------------

    private void apply(Shift shift, ShiftRequest request) {
        shift.setName(request.name().trim());
        shift.setStartTime(request.startTime());
        shift.setEndTime(request.endTime());
        shift.setNightShift(shift.spansMidnight());

        shift.setStandardWorkMinutes(request.standardWorkMinutes().shortValue());
        shift.setFullDayMinutes(request.fullDayMinutes().shortValue());
        shift.setHalfDayMinutes(request.halfDayMinutes().shortValue());

        if (request.gracePeriodMinutes() != null) {
            shift.setGracePeriodMinutes(request.gracePeriodMinutes().shortValue());
        }
        if (request.earlyExitGraceMinutes() != null) {
            shift.setEarlyExitGraceMinutes(request.earlyExitGraceMinutes().shortValue());
        }
        if (request.minAttendanceMinutes() != null) {
            shift.setMinAttendanceMinutes(request.minAttendanceMinutes().shortValue());
        }
        if (request.maxBreakMinutes() != null) {
            shift.setMaxBreakMinutes(request.maxBreakMinutes().shortValue());
        }
        if (request.punchInWindowBeforeMinutes() != null) {
            shift.setPunchInWindowBeforeMinutes(request.punchInWindowBeforeMinutes().shortValue());
        }
        if (request.punchOutWindowAfterMinutes() != null) {
            shift.setPunchOutWindowAfterMinutes(request.punchOutWindowAfterMinutes().shortValue());
        }

        if (request.officeId() != null) {
            Office office = officeRepository.findById(request.officeId())
                    .orElseThrow(() -> new ResourceNotFoundException("Office", request.officeId()));
            shift.setOffice(office);
        } else {
            shift.setOffice(null);
        }

        shift.setDescription(request.description());
        if (request.defaultShift() != null) {
            shift.setDefaultShift(request.defaultShift());
        }
        if (request.active() != null) {
            shift.setActive(request.active());
        }

        validateThresholds(shift);
    }

    /**
     * Cross-field rules the annotations cannot express. A half-day threshold
     * above the full-day one, or a full day longer than the shift itself, would
     * make every attendance status wrong in a way nobody notices until payroll.
     */
    private void validateThresholds(Shift shift) {
        if (shift.getHalfDayMinutes() > shift.getFullDayMinutes()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Half-day minutes cannot exceed full-day minutes");
        }
        if (shift.getMinAttendanceMinutes() > shift.getHalfDayMinutes()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Minimum attendance minutes cannot exceed half-day minutes");
        }

        long spanMinutes = shiftSpanMinutes(shift.getStartTime(), shift.getEndTime());
        if (shift.getFullDayMinutes() > spanMinutes) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Full-day minutes (" + shift.getFullDayMinutes() + ") exceed the shift span of "
                            + spanMinutes + " minutes — nobody could ever reach a full day");
        }
        if (shift.getStandardWorkMinutes() > spanMinutes) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Standard working minutes exceed the shift span of " + spanMinutes + " minutes");
        }
    }

    /** Handles the midnight-crossing case, where end is at or before start. */
    private long shiftSpanMinutes(LocalTime start, LocalTime end) {
        long minutes = Duration.between(start, end).toMinutes();
        return minutes <= 0 ? minutes + Duration.ofDays(1).toMinutes() : minutes;
    }

    /** Exactly one shift may be the default. */
    private void clearOtherDefaults(Shift current) {
        if (!current.isDefaultShift()) {
            return;
        }
        shiftRepository.findAll().stream()
                .filter(other -> !other.getId().equals(current.getId()) && other.isDefaultShift())
                .forEach(other -> {
                    other.setDefaultShift(false);
                    shiftRepository.save(other);
                });
    }

    private Shift requireShift(Long id) {
        return shiftRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Shift", id));
    }

    private ShiftResponse toResponse(Shift shift) {
        return new ShiftResponse(
                shift.getId(),
                shift.getCode(),
                shift.getName(),
                shift.getStartTime(),
                shift.getEndTime(),
                shift.isNightShift(),
                shift.getGracePeriodMinutes(),
                shift.getEarlyExitGraceMinutes(),
                shift.getStandardWorkMinutes(),
                shift.getFullDayMinutes(),
                shift.getHalfDayMinutes(),
                shift.getMinAttendanceMinutes(),
                shift.getMaxBreakMinutes(),
                shift.getPunchInWindowBeforeMinutes(),
                shift.getPunchOutWindowAfterMinutes(),
                shift.getOffice() != null ? shift.getOffice().getId() : null,
                shift.getOffice() != null ? shift.getOffice().getName() : null,
                shift.getDescription(),
                shift.isDefaultShift(),
                shift.isActive(),
                shift.getId() != null ? employeeShiftRepository.countByShiftId(shift.getId()) : 0);
    }

    private ShiftAssignmentResponse toAssignmentResponse(EmployeeShift assignment, LocalDate onDate) {
        Shift shift = assignment.getShift();
        return new ShiftAssignmentResponse(
                assignment.getId(),
                assignment.getEmployee().getId(),
                shift.getId(),
                shift.getCode(),
                shift.getName(),
                shift.getStartTime(),
                shift.getEndTime(),
                assignment.getEffectiveFrom(),
                assignment.getEffectiveTo(),
                assignment.getNotes(),
                assignment.isEffectiveOn(onDate));
    }

    private Object snapshot(Shift shift) {
        return Map.of(
                "code", shift.getCode(),
                "name", shift.getName(),
                "startTime", shift.getStartTime().toString(),
                "endTime", shift.getEndTime().toString(),
                "gracePeriodMinutes", shift.getGracePeriodMinutes(),
                "fullDayMinutes", shift.getFullDayMinutes(),
                "halfDayMinutes", shift.getHalfDayMinutes(),
                "active", shift.isActive());
    }
}
