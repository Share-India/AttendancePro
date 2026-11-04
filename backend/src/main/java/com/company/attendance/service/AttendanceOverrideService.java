package com.company.attendance.service;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.attendance.AttendanceOverrideRequest;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.AttendancePunchEvent;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Shift;
import com.company.attendance.enums.AttendanceSource;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.DayType;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.PunchEventType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendanceBreakRepository;
import com.company.attendance.repository.AttendancePunchEventRepository;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.util.DateTimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * Setting a day's punch times directly, with no request and no approval.
 *
 * <p>This exists for the cases the regularization flow cannot reach: a device
 * that failed for a whole shift, a correction noticed after the seven-day
 * window shut, or a day the employee never punched at all and therefore has no
 * record to raise a request against. It is gated on
 * {@code ATTENDANCE_OVERRIDE}, which only SUPER_ADMIN holds.
 *
 * <p><strong>The corrected day presents as an ordinary regularization.</strong>
 * Source, remarks and the punch events are stamped exactly as
 * {@code RegularizationServiceImpl} stamps an approved request, so nothing on
 * any screen or in any report distinguishes a day corrected here from one the
 * employee requested and a manager approved. That is a deliberate decision by
 * the system owner, who is the only holder of {@code ATTENDANCE_OVERRIDE}.
 *
 * <p><strong>The audit trail is kept regardless.</strong> Every call still
 * writes an {@code ATTENDANCE_OVERRIDE} audit row with the before values, the
 * after values and the operator's stated reason, and the original device punch
 * events are never deleted — the new pair is appended beside them. This costs
 * nothing in confidentiality: {@code AUDIT_VIEW} is held only by ADMIN and
 * SUPER_ADMIN, so the people the presentation is opaque to cannot read the
 * audit log either. What it buys is the ability to answer "what did this say
 * before, and why was it changed" months later, when a payslip is queried and
 * nobody remembers.
 *
 * <p>Note that {@code V20}'s header describes the row as being stamped
 * MANUAL_OVERRIDE. That was true when the migration was written and is not
 * true now; the migration is already applied, so its text cannot be corrected
 * without breaking Flyway's checksum. This javadoc is the current account.
 */
@Service
public class AttendanceOverrideService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceOverrideService.class);

    private final AttendanceRepository attendanceRepository;
    private final AttendancePunchEventRepository punchEventRepository;
    private final AttendanceBreakRepository breakRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeShiftRepository employeeShiftRepository;
    private final PolicyResolutionService policyResolutionService;
    private final CalendarService calendarService;
    private final AttendanceCalculationService calculationService;
    private final AuditLogService auditLogService;

    public AttendanceOverrideService(AttendanceRepository attendanceRepository,
                                     AttendancePunchEventRepository punchEventRepository,
                                     AttendanceBreakRepository breakRepository,
                                     EmployeeRepository employeeRepository,
                                     EmployeeShiftRepository employeeShiftRepository,
                                     PolicyResolutionService policyResolutionService,
                                     CalendarService calendarService,
                                     AttendanceCalculationService calculationService,
                                     AuditLogService auditLogService) {
        this.attendanceRepository = attendanceRepository;
        this.punchEventRepository = punchEventRepository;
        this.breakRepository = breakRepository;
        this.employeeRepository = employeeRepository;
        this.employeeShiftRepository = employeeShiftRepository;
        this.policyResolutionService = policyResolutionService;
        this.calendarService = calendarService;
        this.calculationService = calculationService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public Attendance override(UserPrincipal principal, Long employeeId, LocalDate date,
                               AttendanceOverrideRequest request) {

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND,
                        "That employee does not exist."));

        if (date.isAfter(LocalDate.now())) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "You cannot set times for a day that has not happened yet.");
        }

        ZoneId zone = DateTimeUtils.zoneOf(employee);

        Shift shift = employeeShiftRepository.findCurrentFor(employee.getId(), date)
                .map(assignment -> assignment.getShift())
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_SHIFT_ASSIGNED,
                        "No shift was assigned on " + date + ", so the day cannot be computed. "
                                + "Assign one first."));

        AttendancePolicy policy = policyResolutionService.resolveAttendancePolicy(employee, date);
        DayType dayType = calendarService.resolveDayType(employee, date);

        // The day may not exist at all: somebody who never punched has no row,
        // and that is one of the cases this endpoint is for.
        Attendance attendance = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employee.getId(), date)
                .orElseGet(() -> {
                    Attendance created = new Attendance();
                    created.setEmployee(employee);
                    created.setAttendanceDate(date);
                    return created;
                });

        if (attendance.isLocked()) {
            // Refused rather than forced. Payroll has already been run against
            // this day, so changing it now leaves the system and the payslip
            // disagreeing with nothing to reconcile them. Unlocking is a
            // separate, deliberate act.
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "That day is locked for payroll. Unlock it first if it genuinely has to "
                            + "change — the payslip has already been produced from these figures.");
        }

        Instant punchIn = request.punchInTime() != null
                ? request.punchInTime() : attendance.getPunchInTime();
        Instant punchOut = request.clearPunchOut()
                ? null
                : request.punchOutTime() != null ? request.punchOutTime() : attendance.getPunchOutTime();

        validate(punchIn, punchOut);

        Map<String, Object> before = snapshot(attendance);

        attendance.setShift(attendance.getShift() != null ? attendance.getShift() : shift);
        attendance.setPunchInTime(punchIn);
        attendance.setPunchOutTime(punchOut);
        // Stamped exactly as RegularizationServiceImpl stamps an approved
        // request, so the corrected day is indistinguishable from one the
        // employee asked for and a manager agreed to. That is a deliberate
        // decision by the system owner — see the class javadoc.
        attendance.setSource(AttendanceSource.REGULARIZATION);
        attendance.setRegularized(true);
        attendance.setRemarks("Regularized");

        recompute(attendance, punchIn, punchOut, date, zone, shift, policy, dayType);

        Attendance saved = attendanceRepository.save(attendance);

        // Append-only, exactly as the regularization path does it: the device's
        // own punch events are left untouched and this is added beside them, so
        // the log still explains how the summary was reached.
        recordCorrectionEvents(saved, employee, request, punchIn, punchOut);

        auditLogService.record(AuditAction.ATTENDANCE_OVERRIDE, "Attendance", saved.getId(),
                "Set punch times for " + employee.getEmployeeCode() + " on " + date
                        + " — " + request.reason().trim(),
                before, snapshot(saved));

        log.warn("Attendance override: {} on {} set by user {} ({} -> in={} out={})",
                employee.getEmployeeCode(), date, principal.getUserId(),
                before.get("punchInTime"), punchIn, punchOut);

        return saved;
    }

    // -----------------------------------------------------------------

    private void validate(Instant punchIn, Instant punchOut) {
        if (punchIn == null && punchOut == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Give at least a punch-in or a punch-out time.");
        }
        if (punchOut != null && punchIn == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A day cannot have a punch-out without a punch-in.");
        }
        if (punchIn != null && punchOut != null && !punchOut.isAfter(punchIn)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "The punch-out time must be after the punch-in time.");
        }
        Instant now = Instant.now();
        if (punchIn != null && punchIn.isAfter(now)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A punch-in time cannot be in the future.");
        }
        if (punchOut != null && punchOut.isAfter(now)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A punch-out time cannot be in the future.");
        }
    }

    /**
     * Rebuilds every derived figure on the day.
     *
     * <p>The same calculation the punch path and the regularization path use —
     * not a shortcut that writes the two timestamps and leaves working minutes,
     * late minutes and the status saying what they said before. A record whose
     * summary disagrees with its own times is worse than one that was never
     * corrected.
     */
    private void recompute(Attendance attendance, Instant punchIn, Instant punchOut,
                           LocalDate date, ZoneId zone, Shift shift,
                           AttendancePolicy policy, DayType dayType) {

        if (punchIn != null && punchOut != null) {
            int deductibleBreaks = attendance.getId() != null
                    ? breakRepository.sumDeductibleMinutes(attendance.getId()) : 0;
            int totalBreaks = attendance.getId() != null
                    ? breakRepository.sumAllMinutes(attendance.getId()) : 0;

            var calculation = calculationService.calculateDay(punchIn, punchOut,
                    deductibleBreaks, totalBreaks, date, zone, shift, policy, dayType,
                    attendance.getWorkMode());

            attendance.setWorkingMinutes(calculation.workingMinutes());
            attendance.setBreakMinutes(calculation.breakMinutes());
            attendance.setLateMinutes(calculation.lateMinutes());
            attendance.setEarlyExitMinutes(calculation.earlyExitMinutes());
            attendance.setOvertimeMinutes(calculation.overtimeMinutes());
            attendance.setAttendanceStatus(calculation.status());
            return;
        }

        // Punch-in only: the day is open again rather than settled, so the
        // closing figures are cleared rather than left stale.
        attendance.setWorkingMinutes(0);
        attendance.setBreakMinutes(0);
        attendance.setEarlyExitMinutes(0);
        attendance.setOvertimeMinutes(0);
        attendance.setLateMinutes(
                calculationService.calculateLateMinutes(punchIn, date, zone, shift, policy));
        attendance.setAttendanceStatus(AttendanceStatus.MISSING_PUNCH_OUT);
    }

    /** One event per end that was actually changed, so the log reads honestly. */
    private void recordCorrectionEvents(Attendance attendance, Employee employee,
                                        AttendanceOverrideRequest request,
                                        Instant punchIn, Instant punchOut) {
        if (request.punchInTime() != null) {
            punchEventRepository.save(correctionEvent(attendance, employee,
                    PunchEventType.PUNCH_IN, punchIn));
        }
        if (request.punchOutTime() != null && !request.clearPunchOut()) {
            punchEventRepository.save(correctionEvent(attendance, employee,
                    PunchEventType.PUNCH_OUT, punchOut));
        }
    }

    private AttendancePunchEvent correctionEvent(Attendance attendance, Employee employee,
                                                 PunchEventType type, Instant at) {
        AttendancePunchEvent event = new AttendancePunchEvent();
        event.setAttendance(attendance);
        event.setEmployeeId(employee.getId());
        event.setEventType(type);
        event.setEventTime(at);
        event.setWorkMode(attendance.getWorkMode());
        event.setSource(AttendanceSource.REGULARIZATION);
        event.setCreatedAt(Instant.now());
        return event;
    }

    private Map<String, Object> snapshot(Attendance attendance) {
        Map<String, Object> values = new HashMap<>();
        values.put("punchInTime", attendance.getPunchInTime() != null
                ? attendance.getPunchInTime().toString() : null);
        values.put("punchOutTime", attendance.getPunchOutTime() != null
                ? attendance.getPunchOutTime().toString() : null);
        values.put("workingMinutes", attendance.getWorkingMinutes());
        values.put("lateMinutes", attendance.getLateMinutes());
        values.put("status", attendance.getAttendanceStatus() != null
                ? attendance.getAttendanceStatus().name() : null);
        values.put("source", attendance.getSource() != null
                ? attendance.getSource().name() : null);
        return values;
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
