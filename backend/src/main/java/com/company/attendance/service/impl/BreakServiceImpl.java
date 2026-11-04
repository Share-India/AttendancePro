package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.attendance.BreakRequest;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.AttendanceBreak;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.AttendancePunchEvent;
import com.company.attendance.entity.BreakType;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Shift;
import com.company.attendance.enums.AttendanceSource;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.PunchEventType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendanceBreakRepository;
import com.company.attendance.repository.AttendancePunchEventRepository;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.BreakTypeRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.BreakService;
import com.company.attendance.service.PolicyResolutionService;
import com.company.attendance.util.DateTimeUtils;
import com.company.attendance.util.IpAddressUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Break start and end.
 *
 * <p>Only one break can be open at a time, and that is enforced by the absence
 * of an end time on the row rather than by a separate flag — there is no second
 * piece of state to fall out of step with the first.
 *
 * <p>An over-long break is flagged, never refused. Blocking someone from
 * returning to work because they took too long is the wrong response; the
 * record simply says what happened and HR decides.
 */
@Service
public class BreakServiceImpl implements BreakService {

    private static final Logger log = LoggerFactory.getLogger(BreakServiceImpl.class);

    private static final String DEFAULT_BREAK_TYPE = "PERSONAL";

    private final AttendanceRepository attendanceRepository;
    private final AttendanceBreakRepository breakRepository;
    private final BreakTypeRepository breakTypeRepository;
    private final AttendancePunchEventRepository punchEventRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeShiftRepository employeeShiftRepository;
    private final PolicyResolutionService policyResolutionService;
    private final AuditLogService auditLogService;

    public BreakServiceImpl(AttendanceRepository attendanceRepository,
                            AttendanceBreakRepository breakRepository,
                            BreakTypeRepository breakTypeRepository,
                            AttendancePunchEventRepository punchEventRepository,
                            EmployeeRepository employeeRepository,
                            EmployeeShiftRepository employeeShiftRepository,
                            PolicyResolutionService policyResolutionService,
                            AuditLogService auditLogService) {
        this.attendanceRepository = attendanceRepository;
        this.breakRepository = breakRepository;
        this.breakTypeRepository = breakTypeRepository;
        this.punchEventRepository = punchEventRepository;
        this.employeeRepository = employeeRepository;
        this.employeeShiftRepository = employeeShiftRepository;
        this.policyResolutionService = policyResolutionService;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional
    public AttendanceBreak startBreak(UserPrincipal principal, BreakRequest request) {
        Instant now = Instant.now();
        Employee employee = requireEmployee(principal);
        Attendance attendance = requireOpenDay(employee, now);

        if (breakRepository.findOpenBreak(attendance.getId()).isPresent()) {
            throw new BusinessException(ErrorCode.BREAK_ALREADY_OPEN,
                    "You are already on a break. End it before starting another.");
        }

        BreakType breakType = resolveBreakType(request);

        AttendanceBreak attendanceBreak = new AttendanceBreak();
        attendanceBreak.setAttendance(attendance);
        attendanceBreak.setBreakType(breakType);
        attendanceBreak.setBreakStartTime(now);
        AttendanceBreak saved = breakRepository.save(attendanceBreak);

        AttendancePunchEvent event = recordEvent(attendance, employee, PunchEventType.BREAK_START, now);
        saved.setStartEventId(event.getId());
        breakRepository.save(saved);

        attendance.setAttendanceStatus(AttendanceStatus.ON_BREAK);
        attendanceRepository.save(attendance);

        auditLogService.record(AuditAction.BREAK_START, "AttendanceBreak", saved.getId(),
                "Started " + breakType.getName());

        return saved;
    }

    @Override
    @Transactional
    public AttendanceBreak endBreak(UserPrincipal principal) {
        Instant now = Instant.now();
        Employee employee = requireEmployee(principal);
        Attendance attendance = requireOpenDay(employee, now);

        AttendanceBreak openBreak = breakRepository.findOpenBreak(attendance.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "You are not currently on a break."));

        AttendancePolicy policy = policyResolutionService.resolveAttendancePolicy(
                employee, attendance.getAttendanceDate());

        int duration = DateTimeUtils.minutesBetween(openBreak.getBreakStartTime(), now);
        openBreak.setBreakEndTime(now);
        openBreak.setDurationMinutes(duration);

        // Flagged, not blocked. The number is recorded either way.
        boolean exceeded = duration > openBreak.getBreakType().getMaxMinutes()
                || duration > policy.getMaxBreakMinutes();
        openBreak.setExceededLimit(exceeded);

        AttendancePunchEvent event = recordEvent(attendance, employee, PunchEventType.BREAK_END, now);
        openBreak.setEndEventId(event.getId());
        AttendanceBreak saved = breakRepository.save(openBreak);

        // Keep the day's break total current so the live timer stays honest
        // without recomputing every poll.
        attendance.setBreakMinutes(breakRepository.sumAllMinutes(attendance.getId()));
        attendance.setAttendanceStatus(AttendanceStatus.PUNCHED_IN);
        attendanceRepository.save(attendance);

        if (exceeded) {
            log.info("Break by {} ran {} minutes, over the {} minute limit",
                    employee.getEmployeeCode(), duration, openBreak.getBreakType().getMaxMinutes());
        }

        auditLogService.record(AuditAction.BREAK_END, "AttendanceBreak", saved.getId(),
                "Ended " + openBreak.getBreakType().getName()
                        + " after " + DateTimeUtils.formatMinutes(duration),
                null, Map.of("durationMinutes", duration, "exceededLimit", exceeded));

        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BreakType> findBreakTypes() {
        return breakTypeRepository.findByActiveTrueOrderByDisplayOrderAsc();
    }

    // -----------------------------------------------------------------

    private Employee requireEmployee(UserPrincipal principal) {
        if (principal.getEmployeeId() == null) {
            throw new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND,
                    "This account is not linked to an employee record.");
        }
        return employeeRepository.findById(principal.getEmployeeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found"));
    }

    /**
     * The day must be open: punched in and not yet out. Locked with the same
     * pessimistic write lock punch-out uses, since both mutate the same row.
     */
    private Attendance requireOpenDay(Employee employee, Instant now) {
        ZoneId zone = DateTimeUtils.zoneOf(employee);
        Shift shift = employeeShiftRepository.findCurrentFor(employee.getId(), DateTimeUtils.localDate(now, zone))
                .map(assignment -> assignment.getShift())
                .orElse(null);
        LocalDate businessDate = DateTimeUtils.resolveBusinessDate(now, zone, shift);

        Attendance attendance = attendanceRepository.findForUpdate(employee.getId(), businessDate)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_PUNCHED_IN,
                        "You have not punched in today."));

        if (!attendance.isPunchedIn()) {
            throw new BusinessException(ErrorCode.NOT_PUNCHED_IN, "You have not punched in today.");
        }
        if (attendance.isPunchedOut()) {
            throw new BusinessException(ErrorCode.ALREADY_PUNCHED_OUT,
                    "You have already punched out, so breaks can no longer be recorded.");
        }
        return attendance;
    }

    private BreakType resolveBreakType(BreakRequest request) {
        String code = request != null && request.breakTypeCode() != null && !request.breakTypeCode().isBlank()
                ? request.breakTypeCode().trim().toUpperCase(Locale.ROOT)
                : DEFAULT_BREAK_TYPE;

        return breakTypeRepository.findByCode(code)
                .filter(BreakType::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Unknown break type '" + code + "'."));
    }

    /** Breaks are punch events too, so the day's evidence log stays complete. */
    private AttendancePunchEvent recordEvent(Attendance attendance, Employee employee,
                                             PunchEventType type, Instant now) {
        HttpServletRequest httpRequest = currentRequest();

        AttendancePunchEvent event = new AttendancePunchEvent();
        event.setAttendance(attendance);
        event.setEmployeeId(employee.getId());
        event.setEventType(type);
        event.setEventTime(now);
        event.setWorkMode(attendance.getWorkMode());
        event.setIpAddress(IpAddressUtils.resolve(httpRequest));
        event.setUserAgent(IpAddressUtils.userAgent(httpRequest));
        event.setSource(AttendanceSource.WEB);
        event.setCreatedAt(now);

        return punchEventRepository.save(event);
    }

    private HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletAttributes
                ? servletAttributes.getRequest()
                : null;
    }
}
