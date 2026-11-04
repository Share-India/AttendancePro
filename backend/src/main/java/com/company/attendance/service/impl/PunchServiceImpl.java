package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.attendance.PunchRequest;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.AttendanceBreak;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.AttendancePunchEvent;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.LocationPolicy;
import com.company.attendance.entity.OvertimePolicy;
import com.company.attendance.entity.OvertimeRecord;
import com.company.attendance.entity.Shift;
import com.company.attendance.enums.AttendanceSource;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.DayPortion;
import com.company.attendance.enums.DayType;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.PunchEventType;
import com.company.attendance.enums.SuspicionFlag;
import com.company.attendance.enums.WorkMode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendanceBreakRepository;
import com.company.attendance.repository.AttendancePunchEventRepository;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.LeaveRequestRepository;
import com.company.attendance.repository.OvertimeRecordRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.AttendanceCalculationService;
import com.company.attendance.service.CalendarService;
import com.company.attendance.service.GeofenceService;
import com.company.attendance.service.PolicyResolutionService;
import com.company.attendance.service.PunchService;
import com.company.attendance.service.SuspiciousPunchDetector;
import com.company.attendance.service.WorkFromHomeService;
import com.company.attendance.util.DateTimeUtils;
import com.company.attendance.util.IpAddressUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.Set;

/**
 * Punch in and punch out.
 *
 * <p>The validation chain runs in one transaction, ordered so the cheapest and
 * most decisive checks come first. Identity is taken from the JWT principal —
 * an employeeId in the request body, if a client sends one, is ignored.
 */
@Service
public class PunchServiceImpl implements PunchService {

    private static final Logger log = LoggerFactory.getLogger(PunchServiceImpl.class);

    private final AttendanceRepository attendanceRepository;
    private final AttendancePunchEventRepository punchEventRepository;
    private final AttendanceBreakRepository breakRepository;
    private final OvertimeRecordRepository overtimeRecordRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeShiftRepository employeeShiftRepository;
    private final PolicyResolutionService policyResolutionService;
    private final CalendarService calendarService;
    private final GeofenceService geofenceService;
    private final SuspiciousPunchDetector suspiciousPunchDetector;
    private final AttendanceCalculationService calculationService;
    private final WorkFromHomeService workFromHomeService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public PunchServiceImpl(AttendanceRepository attendanceRepository,
                            AttendancePunchEventRepository punchEventRepository,
                            AttendanceBreakRepository breakRepository,
                            OvertimeRecordRepository overtimeRecordRepository,
                            LeaveRequestRepository leaveRequestRepository,
                            EmployeeRepository employeeRepository,
                            EmployeeShiftRepository employeeShiftRepository,
                            PolicyResolutionService policyResolutionService,
                            CalendarService calendarService,
                            GeofenceService geofenceService,
                            SuspiciousPunchDetector suspiciousPunchDetector,
                            AttendanceCalculationService calculationService,
                            WorkFromHomeService workFromHomeService,
                            AuditLogService auditLogService,
                            ObjectMapper objectMapper) {
        this.attendanceRepository = attendanceRepository;
        this.punchEventRepository = punchEventRepository;
        this.breakRepository = breakRepository;
        this.overtimeRecordRepository = overtimeRecordRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.employeeRepository = employeeRepository;
        this.employeeShiftRepository = employeeShiftRepository;
        this.policyResolutionService = policyResolutionService;
        this.calendarService = calendarService;
        this.geofenceService = geofenceService;
        this.suspiciousPunchDetector = suspiciousPunchDetector;
        this.calculationService = calculationService;
        this.workFromHomeService = workFromHomeService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------
    // Punch in
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public Attendance punchIn(UserPrincipal principal, PunchRequest request) {
        Instant now = Instant.now();

        // 1. Identity from the token, never the request.
        Employee employee = requireEmployee(principal);

        // 2. Business date in the office's timezone, not the server's.
        ZoneId zone = DateTimeUtils.zoneOf(employee);

        // 3. The shift in force. Everything downstream needs it.
        Shift shift = resolveShift(employee, DateTimeUtils.localDate(now, zone));
        LocalDate businessDate = DateTimeUtils.resolveBusinessDate(now, zone, shift);

        // 4. Policies by scope.
        AttendancePolicy attendancePolicy = policyResolutionService.resolveAttendancePolicy(employee, businessDate);
        LocationPolicy locationPolicy = policyResolutionService.resolveLocationPolicy(employee, businessDate);

        // 5. What kind of day this is.
        DayType dayType = calendarService.resolveDayType(employee, businessDate);
        assertDayAllowsPunch(dayType, attendancePolicy);

        // 6. Approved leave. A full day off means there is nothing to punch;
        //    a half day still expects a punch for the half being worked.
        assertNotOnFullDayLeave(employee, businessDate);

        // 7. Punch window.
        WorkMode workMode = parseWorkMode(request.workMode());
        assertWithinPunchWindow(now, businessDate, zone, shift, attendancePolicy);

        // 8 & 9. Accuracy gate and geofence.
        GeofenceService.GeofenceResult geofence = geofenceService.validate(
                employee, request.latitude(), request.longitude(), request.accuracyMeters(),
                workMode, locationPolicy, isWorkFromHomeApproved(employee, businessDate));

        // 10. Suspicion flags — advisory unless policy says otherwise.
        Set<SuspicionFlag> flags = suspiciousPunchDetector.detect(
                employee, request.latitude(), request.longitude(), request.accuracyMeters(),
                now, request.capturedAt(), businessDate, locationPolicy);

        if (!flags.isEmpty() && locationPolicy.isBlockOnSuspicious()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "This punch was flagged for review and your policy blocks flagged punches. "
                            + "Contact HR.", Map.of("flags", flags));
        }

        // 11. Insert. The unique constraint on (employee_id, attendance_date) is
        //     the duplicate guard: two concurrent punch-ins cannot both succeed.
        Attendance attendance = new Attendance();
        attendance.setEmployee(employee);
        attendance.setAttendanceDate(businessDate);
        attendance.setShift(shift);
        attendance.setPunchInTime(now);
        attendance.setWorkMode(workMode);
        attendance.setSource(AttendanceSource.WEB);
        attendance.setAttendanceStatus(AttendanceStatus.PUNCHED_IN);

        HttpServletRequest httpRequest = currentRequest();
        attendance.setPunchInIp(IpAddressUtils.resolve(httpRequest));
        attendance.setPunchInDevice(truncate(request.deviceInfo(), 255));
        attendance.setPunchInLatitude(request.latitude());
        attendance.setPunchInLongitude(request.longitude());
        attendance.setPunchInAccuracy(request.accuracyMeters());
        attendance.setPunchInOfficeLocation(geofence.matchedLocation());
        attendance.setPunchInDistanceMeters(geofence.distanceMeters());
        attendance.setPunchInLocation(geofence.locationLabel());

        attendance.setLateMinutes(
                calculationService.calculateLateMinutes(now, businessDate, zone, shift, attendancePolicy));

        applyFlags(attendance, flags);

        Attendance saved;
        try {
            saved = attendanceRepository.saveAndFlush(attendance);
        } catch (DataIntegrityViolationException e) {
            // The other request got there first. This is the expected outcome of
            // a double-click or two open tabs, not an error worth a stack trace.
            log.debug("Duplicate punch-in for employee {} on {}", employee.getEmployeeCode(), businessDate);
            throw new BusinessException(ErrorCode.ALREADY_PUNCHED_IN,
                    "You have already punched in today.");
        }

        recordEvent(saved, employee, PunchEventType.PUNCH_IN, now, request, geofence, flags);

        auditLogService.record(AuditAction.PUNCH_IN, "Attendance", saved.getId(),
                "Punched in at " + geofence.locationLabel(), null,
                Map.of("date", businessDate.toString(),
                        "lateMinutes", saved.getLateMinutes(),
                        "workMode", workMode.name()));

        return saved;
    }

    // -----------------------------------------------------------------
    // Punch out
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public Attendance punchOut(UserPrincipal principal, PunchRequest request) {
        Instant now = Instant.now();
        Employee employee = requireEmployee(principal);
        ZoneId zone = DateTimeUtils.zoneOf(employee);

        Shift todayShift = resolveShift(employee, DateTimeUtils.localDate(now, zone));
        LocalDate businessDate = DateTimeUtils.resolveBusinessDate(now, zone, todayShift);

        // Row lock: the day already exists, so two concurrent writes would
        // otherwise interleave a read-modify-write and lose one of them.
        Attendance attendance = attendanceRepository.findForUpdate(employee.getId(), businessDate)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_PUNCHED_IN,
                        "You have not punched in today."));

        if (!attendance.isPunchedIn()) {
            throw new BusinessException(ErrorCode.NOT_PUNCHED_IN, "You have not punched in today.");
        }
        if (attendance.isPunchedOut()) {
            throw new BusinessException(ErrorCode.ALREADY_PUNCHED_OUT, "You have already punched out today.");
        }
        if (attendance.isLocked()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "This day has been locked for payroll and can no longer be changed.");
        }

        Shift shift = attendance.getShift() != null ? attendance.getShift() : todayShift;
        AttendancePolicy attendancePolicy = policyResolutionService.resolveAttendancePolicy(employee, businessDate);
        LocationPolicy locationPolicy = policyResolutionService.resolveLocationPolicy(employee, businessDate);
        WorkMode workMode = attendance.getWorkMode();

        // An open break at punch-out is closed rather than left dangling, and
        // marked auto-closed so it is distinguishable from one the employee ended.
        breakRepository.findOpenBreak(attendance.getId()).ifPresent(openBreak -> {
            closeBreak(openBreak, now, attendancePolicy, true);
            breakRepository.save(openBreak);
        });

        GeofenceService.GeofenceResult geofence = geofenceService.validate(
                employee, request.latitude(), request.longitude(), request.accuracyMeters(),
                workMode, locationPolicy, isWorkFromHomeApproved(employee, businessDate));

        Set<SuspicionFlag> flags = suspiciousPunchDetector.detect(
                employee, request.latitude(), request.longitude(), request.accuracyMeters(),
                now, request.capturedAt(), businessDate, locationPolicy);

        int deductibleBreakMinutes = breakRepository.sumDeductibleMinutes(attendance.getId());
        int totalBreakMinutes = breakRepository.sumAllMinutes(attendance.getId());
        DayType dayType = calendarService.resolveDayType(employee, businessDate);

        var calculation = calculationService.calculateDay(
                attendance.getPunchInTime(), now,
                deductibleBreakMinutes, totalBreakMinutes,
                businessDate, zone, shift, attendancePolicy, dayType, workMode);

        attendance.setPunchOutTime(now);
        attendance.setPunchOutLatitude(request.latitude());
        attendance.setPunchOutLongitude(request.longitude());
        attendance.setPunchOutAccuracy(request.accuracyMeters());
        attendance.setPunchOutOfficeLocation(geofence.matchedLocation());
        attendance.setPunchOutDistanceMeters(geofence.distanceMeters());
        attendance.setPunchOutLocation(geofence.locationLabel());
        attendance.setPunchOutIp(IpAddressUtils.resolve(currentRequest()));
        attendance.setPunchOutDevice(truncate(request.deviceInfo(), 255));

        attendance.setWorkingMinutes(calculation.workingMinutes());
        attendance.setBreakMinutes(calculation.breakMinutes());
        attendance.setEarlyExitMinutes(calculation.earlyExitMinutes());
        attendance.setOvertimeMinutes(calculation.overtimeMinutes());
        attendance.setAttendanceStatus(calculation.status());

        applyFlags(attendance, flags);

        Attendance saved = attendanceRepository.save(attendance);
        recordEvent(saved, employee, PunchEventType.PUNCH_OUT, now, request, geofence, flags);

        recordOvertimeIfEarned(saved, employee, businessDate, dayType, calculation.overtimeMinutes());

        auditLogService.record(AuditAction.PUNCH_OUT, "Attendance", saved.getId(),
                "Punched out after " + DateTimeUtils.formatMinutes(calculation.workingMinutes()),
                null,
                Map.of("workingMinutes", calculation.workingMinutes(),
                        "breakMinutes", calculation.breakMinutes(),
                        "status", calculation.status().name()));

        return saved;
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private Employee requireEmployee(UserPrincipal principal) {
        if (principal.getEmployeeId() == null) {
            throw new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND,
                    "This account is not linked to an employee record, so it cannot record attendance.");
        }
        Employee employee = employeeRepository.findById(principal.getEmployeeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found"));

        if (!employee.isEmployable()) {
            throw new BusinessException(ErrorCode.EMPLOYEE_INACTIVE,
                    "Your employee record is inactive. Contact HR.");
        }
        return employee;
    }

    private Shift resolveShift(Employee employee, LocalDate onDate) {
        return employeeShiftRepository.findCurrentFor(employee.getId(), onDate)
                .map(assignment -> assignment.getShift())
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_SHIFT_ASSIGNED,
                        "No shift is assigned to you for today, so attendance cannot be recorded. "
                                + "Ask HR to assign one."));
    }

    private void assertDayAllowsPunch(DayType dayType, AttendancePolicy policy) {
        if (dayType == DayType.HOLIDAY && !policy.isAllowPunchOnHoliday()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Today is a holiday and your policy does not allow punching on holidays.");
        }
        if (dayType == DayType.WEEKLY_OFF && !policy.isAllowPunchOnWeeklyOff()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Today is a weekly off and your policy does not allow punching on weekly offs.");
        }
    }

    /**
     * Stops a punch hours before a shift begins from silently claiming the day.
     * Skipped on non-working days, where there is no meaningful window.
     */
    private void assertWithinPunchWindow(Instant now, LocalDate businessDate, ZoneId zone,
                                         Shift shift, AttendancePolicy policy) {
        if (!policy.isEnforcePunchWindow()) {
            return;
        }
        Instant windowOpens = DateTimeUtils.shiftStartInstant(businessDate, zone, shift)
                .minusSeconds(shift.getPunchInWindowBeforeMinutes() * 60L);

        if (now.isBefore(windowOpens)) {
            throw new BusinessException(ErrorCode.OUTSIDE_PUNCH_WINDOW,
                    "Your shift starts at " + shift.getStartTime() + ". You can punch in from "
                            + shift.getPunchInWindowBeforeMinutes() + " minutes before that.");
        }
    }

    /**
     * Refuses a punch on a day already covered by approved full-day leave.
     *
     * <p>A half-day leave is deliberately allowed through: the employee is
     * expected to work the other half, and blocking them would leave that half
     * unrecorded.
     */
    private void assertNotOnFullDayLeave(Employee employee, LocalDate businessDate) {
        List<DayPortion> portions =
                leaveRequestRepository.findApprovedPortionsOn(employee.getId(), businessDate);

        boolean fullDay = portions.stream().anyMatch(portion -> portion == DayPortion.FULL_DAY);

        if (fullDay) {
            throw new BusinessException(ErrorCode.ON_APPROVED_LEAVE,
                    "You are on approved leave today. If you are working after all, "
                            + "cancel the leave first.");
        }
    }

    /**
     * Whether an approved work-from-home request covers this date.
     *
     * <p>One indexed existence check. This is the hot path — it runs on every
     * punch — so it deliberately does not load the request itself.
     */
    private boolean isWorkFromHomeApproved(Employee employee, LocalDate date) {
        return workFromHomeService.isApprovedOn(employee.getId(), date);
    }

    private WorkMode parseWorkMode(String value) {
        if (value == null || value.isBlank()) {
            return WorkMode.OFFICE;
        }
        try {
            return WorkMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Work mode must be one of OFFICE, WFH, FIELD or CLIENT_SITE.");
        }
    }

    private void applyFlags(Attendance attendance, Set<SuspicionFlag> flags) {
        if (flags.isEmpty()) {
            return;
        }
        attendance.setSuspicious(true);
        attendance.setSuspicionReasons(toJson(flags.stream().map(Enum::name).toList()));
    }

    private void recordEvent(Attendance attendance, Employee employee, PunchEventType type,
                             Instant eventTime, PunchRequest request,
                             GeofenceService.GeofenceResult geofence, Set<SuspicionFlag> flags) {

        HttpServletRequest httpRequest = currentRequest();

        AttendancePunchEvent event = new AttendancePunchEvent();
        event.setAttendance(attendance);
        event.setEmployeeId(employee.getId());
        event.setEventType(type);
        event.setEventTime(eventTime);
        event.setClientReportedTime(request != null ? request.capturedAt() : null);
        event.setLatitude(request != null ? request.latitude() : null);
        event.setLongitude(request != null ? request.longitude() : null);
        event.setAccuracyMeters(request != null ? request.accuracyMeters() : null);
        event.setOfficeLocation(geofence != null ? geofence.matchedLocation() : null);
        event.setDistanceMeters(geofence != null ? geofence.distanceMeters() : null);
        event.setWithinGeofence(geofence != null && geofence.checkPerformed() ? geofence.withinGeofence() : null);
        event.setWorkMode(attendance.getWorkMode());
        event.setIpAddress(IpAddressUtils.resolve(httpRequest));
        event.setUserAgent(IpAddressUtils.userAgent(httpRequest));
        event.setDeviceInfo(request != null ? truncate(request.deviceInfo(), 255) : null);
        event.setSource(AttendanceSource.WEB);
        event.setSuspicious(!flags.isEmpty());
        event.setSuspicionReasons(flags.isEmpty() ? null : toJson(flags.stream().map(Enum::name).toList()));
        event.setCreatedAt(Instant.now());

        punchEventRepository.save(event);
    }

    /** Records overtime only when the policy says the extra minutes count. */
    private void recordOvertimeIfEarned(Attendance attendance, Employee employee,
                                        LocalDate businessDate, DayType dayType, int overtimeMinutes) {
        if (overtimeMinutes <= 0) {
            return;
        }

        OvertimePolicy policy = policyResolutionService.resolveOvertimePolicy(employee, businessDate).orElse(null);
        if (policy == null) {
            return;
        }
        if (overtimeMinutes < policy.getMinOvertimeMinutes()) {
            return;
        }
        if (dayType == DayType.WEEKLY_OFF && !policy.isApplicableOnWeeklyOff()) {
            return;
        }
        if (dayType == DayType.HOLIDAY && !policy.isApplicableOnHoliday()) {
            return;
        }

        int claimable = Math.min(overtimeMinutes, policy.getMaxDailyOvertimeMinutes());

        OvertimeRecord record = overtimeRecordRepository.findByAttendanceId(attendance.getId())
                .orElseGet(OvertimeRecord::new);
        record.setAttendance(attendance);
        record.setEmployeeId(employee.getId());
        record.setOvertimeDate(businessDate);
        record.setOvertimeMinutes(claimable);

        if (policy.isRequiresApproval()) {
            record.setStatus("PENDING");
        } else {
            record.setStatus("AUTO_APPROVED");
            record.setApprovedMinutes(claimable);
            record.setActedAt(Instant.now());
        }

        overtimeRecordRepository.save(record);
    }

    private void closeBreak(AttendanceBreak openBreak, Instant now, AttendancePolicy policy, boolean autoClosed) {
        openBreak.setBreakEndTime(now);
        int duration = DateTimeUtils.minutesBetween(openBreak.getBreakStartTime(), now);
        openBreak.setDurationMinutes(duration);
        openBreak.setAutoClosed(autoClosed);

        int limit = openBreak.getBreakType().getMaxMinutes();
        openBreak.setExceededLimit(duration > limit || duration > policy.getMaxBreakMinutes());
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    private HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletAttributes
                ? servletAttributes.getRequest()
                : null;
    }
}
