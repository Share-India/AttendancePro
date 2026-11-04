package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.request.RegularizationApplyRequest;
import com.company.attendance.dto.request.request.RequestDecisionRequest;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.request.RegularizationResponse;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.AttendancePunchEvent;
import com.company.attendance.entity.AttendanceRegularization;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.RegularizationApproval;
import com.company.attendance.entity.Shift;
import com.company.attendance.event.RequestEventPublisher;
import com.company.attendance.enums.AccessScope;
import com.company.attendance.enums.ApprovalAction;
import com.company.attendance.enums.AttendanceSource;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.DayType;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.PunchEventType;
import com.company.attendance.enums.RegularizationReason;
import com.company.attendance.enums.RequestStatus;
import com.company.attendance.enums.WorkMode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendanceBreakRepository;
import com.company.attendance.repository.AttendancePunchEventRepository;
import com.company.attendance.repository.AttendanceRegularizationRepository;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.RegularizationApprovalRepository;
import com.company.attendance.security.AccessScopeResolver;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.AttendanceCalculationService;
import com.company.attendance.service.CalendarService;
import com.company.attendance.service.PolicyResolutionService;
import com.company.attendance.service.RegularizationService;
import com.company.attendance.service.RequestApproverResolver;
import com.company.attendance.util.DateTimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Attendance correction.
 *
 * <p>This is the only path that rewrites an attendance record, so it is
 * deliberately the most conservative one in the system: a frozen before-image
 * on the request, the original punch events left untouched in their append-only
 * log, a synthetic event recording that a correction happened, and an audit row
 * carrying both states.
 */
@Service
public class RegularizationServiceImpl implements RegularizationService {

    private static final Logger log = LoggerFactory.getLogger(RegularizationServiceImpl.class);

    private static final String PERM_APPROVE_L2 = "REGULARIZATION_APPROVE_L2";
    private static final String PERM_VIEW_ALL = "ATTENDANCE_VIEW_ALL";
    private static final String PERM_VIEW_TEAM = "ATTENDANCE_VIEW_TEAM";

    private final AttendanceRegularizationRepository regularizationRepository;
    private final RegularizationApprovalRepository approvalRepository;
    private final AttendanceRepository attendanceRepository;
    private final AttendancePunchEventRepository punchEventRepository;
    private final AttendanceBreakRepository breakRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeShiftRepository employeeShiftRepository;
    private final PolicyResolutionService policyResolutionService;
    private final CalendarService calendarService;
    private final AttendanceCalculationService calculationService;
    private final RequestApproverResolver approverResolver;
    private final AccessScopeResolver accessScopeResolver;
    private final AuditLogService auditLogService;
    private final RequestEventPublisher eventPublisher;

    public RegularizationServiceImpl(AttendanceRegularizationRepository regularizationRepository,
                                     RegularizationApprovalRepository approvalRepository,
                                     AttendanceRepository attendanceRepository,
                                     AttendancePunchEventRepository punchEventRepository,
                                     AttendanceBreakRepository breakRepository,
                                     EmployeeRepository employeeRepository,
                                     EmployeeShiftRepository employeeShiftRepository,
                                     PolicyResolutionService policyResolutionService,
                                     CalendarService calendarService,
                                     AttendanceCalculationService calculationService,
                                     RequestApproverResolver approverResolver,
                                     AccessScopeResolver accessScopeResolver,
                                     AuditLogService auditLogService,
                                        RequestEventPublisher eventPublisher) {
        this.regularizationRepository = regularizationRepository;
        this.approvalRepository = approvalRepository;
        this.attendanceRepository = attendanceRepository;
        this.punchEventRepository = punchEventRepository;
        this.breakRepository = breakRepository;
        this.employeeRepository = employeeRepository;
        this.employeeShiftRepository = employeeShiftRepository;
        this.policyResolutionService = policyResolutionService;
        this.calendarService = calendarService;
        this.calculationService = calculationService;
        this.approverResolver = approverResolver;
        this.accessScopeResolver = accessScopeResolver;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
    }

    // -----------------------------------------------------------------
    // Apply
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public RegularizationResponse apply(UserPrincipal principal, RegularizationApplyRequest request) {
        Employee employee = requireEmployee(principal);
        LocalDate date = request.attendanceDate();

        AttendancePolicy policy = policyResolutionService.resolveAttendancePolicy(employee, date);
        RegularizationReason reasonType = parseReason(request.reasonType());

        validateWindow(date, policy);
        validateMonthlyCap(employee, date, policy);

        regularizationRepository.findPendingForDate(employee.getId(), date).ifPresent(existing -> {
            throw new BusinessException(ErrorCode.DUPLICATE_REGULARIZATION,
                    "You already have a request awaiting a decision for " + date
                            + " (" + existing.getRequestNumber() + ").");
        });

        Optional<Attendance> existing = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employee.getId(), date);

        existing.filter(Attendance::isLocked).ifPresent(locked -> {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "That day has been locked for payroll and can no longer be corrected. Contact HR.");
        });

        validateRequestedTimes(request, reasonType, existing);

        AttendanceRegularization regularization = new AttendanceRegularization();
        regularization.setRequestNumber(nextRequestNumber(date));
        regularization.setEmployee(employee);
        regularization.setAttendanceDate(date);
        regularization.setAttendance(existing.orElse(null));
        regularization.setReasonType(reasonType);
        regularization.setRequestedPunchInTime(request.requestedPunchInTime());
        regularization.setRequestedPunchOutTime(request.requestedPunchOutTime());
        regularization.setRequestedWorkMode(parseWorkMode(request.requestedWorkMode()));
        regularization.setReason(request.reason().trim());
        regularization.setAttachmentUrl(blankToNull(request.attachmentUrl()));

        // Freeze the before-image now. Deriving it at approval time would read
        // a row the approval itself is about to overwrite.
        existing.ifPresent(attendance -> {
            regularization.setOriginalPunchInTime(attendance.getPunchInTime());
            regularization.setOriginalPunchOutTime(attendance.getPunchOutTime());
            regularization.setOriginalWorkingMinutes(attendance.getWorkingMinutes());
            regularization.setOriginalStatus(attendance.getAttendanceStatus());
        });

        AttendanceRegularization saved = regularizationRepository.save(regularization);

        RegularizationApproval level = new RegularizationApproval();
        level.setRegularization(saved);
        level.setLevel((short) 1);
        level.setApproverRole("MANAGER");

        Optional<Employee> approver = approverResolver.resolveApprover(employee, date);
        if (approver.isPresent()) {
            level.setApproverEmployeeId(approver.get().getId());
            saved.setCurrentApproverEmployeeId(approver.get().getId());
        } else {
            level.setComments("No approver could be resolved");
            log.warn("Regularization {} has no approver", saved.getRequestNumber());
        }
        approvalRepository.save(level);
        regularizationRepository.save(saved);

        auditLogService.record(AuditAction.CREATE, "AttendanceRegularization", saved.getId(),
                "Requested correction for " + date + " (" + reasonType + ")",
                null, Map.of("requestNumber", saved.getRequestNumber(), "date", date.toString()));

        RegularizationResponse response = toResponse(saved, principal);
        eventPublisher.regularizationRequested(response);
        return response;
    }

    // -----------------------------------------------------------------
    // Approve — the only path that rewrites attendance
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public RegularizationResponse approve(UserPrincipal principal, Long id, RequestDecisionRequest decision) {
        AttendanceRegularization regularization = requirePending(id);
        assertCanAct(principal, regularization);

        Employee employee = regularization.getEmployee();
        LocalDate date = regularization.getAttendanceDate();
        ZoneId zone = DateTimeUtils.zoneOf(employee);

        Shift shift = employeeShiftRepository.findCurrentFor(employee.getId(), date)
                .map(assignment -> assignment.getShift())
                .orElseThrow(() -> new BusinessException(ErrorCode.NO_SHIFT_ASSIGNED,
                        "No shift was assigned on " + date + ", so the day cannot be recomputed."));

        AttendancePolicy policy = policyResolutionService.resolveAttendancePolicy(employee, date);
        DayType dayType = calendarService.resolveDayType(employee, date);

        Attendance attendance = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employee.getId(), date)
                .orElseGet(() -> {
                    Attendance created = new Attendance();
                    created.setEmployee(employee);
                    created.setAttendanceDate(date);
                    return created;
                });

        if (attendance.isLocked()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "That day has been locked for payroll and can no longer be corrected.");
        }

        Map<String, Object> before = snapshot(attendance);

        // Requested times win where given; anything omitted keeps what was there.
        Instant punchIn = regularization.getRequestedPunchInTime() != null
                ? regularization.getRequestedPunchInTime()
                : attendance.getPunchInTime();
        Instant punchOut = regularization.getRequestedPunchOutTime() != null
                ? regularization.getRequestedPunchOutTime()
                : attendance.getPunchOutTime();

        WorkMode workMode = regularization.getRequestedWorkMode() != null
                ? regularization.getRequestedWorkMode()
                : attendance.getWorkMode();

        attendance.setShift(shift);
        attendance.setPunchInTime(punchIn);
        attendance.setPunchOutTime(punchOut);
        attendance.setWorkMode(workMode);
        attendance.setSource(AttendanceSource.REGULARIZATION);
        attendance.setRegularized(true);
        attendance.setRemarks("Regularized " + regularization.getRequestNumber());

        if (punchIn != null && punchOut != null) {
            int deductibleBreaks = attendance.getId() != null
                    ? breakRepository.sumDeductibleMinutes(attendance.getId()) : 0;
            int totalBreaks = attendance.getId() != null
                    ? breakRepository.sumAllMinutes(attendance.getId()) : 0;

            var calculation = calculationService.calculateDay(
                    punchIn, punchOut, deductibleBreaks, totalBreaks,
                    date, zone, shift, policy, dayType, workMode);

            attendance.setWorkingMinutes(calculation.workingMinutes());
            attendance.setBreakMinutes(calculation.breakMinutes());
            attendance.setLateMinutes(calculation.lateMinutes());
            attendance.setEarlyExitMinutes(calculation.earlyExitMinutes());
            attendance.setOvertimeMinutes(calculation.overtimeMinutes());
            attendance.setAttendanceStatus(calculation.status());
        } else if (punchIn != null) {
            // Half a correction: the day is open again rather than settled.
            attendance.setLateMinutes(
                    calculationService.calculateLateMinutes(punchIn, date, zone, shift, policy));
            attendance.setAttendanceStatus(com.company.attendance.enums.AttendanceStatus.MISSING_PUNCH_OUT);
        }

        Attendance savedAttendance = attendanceRepository.save(attendance);

        // The original punch events are untouched. This one records that a
        // correction happened, so the log still explains the summary.
        recordCorrectionEvent(savedAttendance, employee, regularization);

        regularization.setAttendance(savedAttendance);
        regularization.setStatus(RequestStatus.APPROVED);
        regularization.setDecidedAt(Instant.now());
        regularization.setCurrentApproverEmployeeId(null);
        regularizationRepository.save(regularization);

        RegularizationApproval level = currentLevel(regularization);
        level.setAction(ApprovalAction.APPROVED);
        level.setApproverEmployeeId(principal.getEmployeeId());
        level.setComments(blankToNull(decision != null ? decision.comments() : null));
        level.setActedAt(Instant.now());
        approvalRepository.save(level);

        auditLogService.record(AuditAction.APPROVE, "AttendanceRegularization", regularization.getId(),
                "Approved correction of " + date + " for " + employee.getEmployeeCode(),
                before, snapshot(savedAttendance));

        RegularizationResponse response = toResponse(regularization, principal);
        eventPublisher.regularizationDecided(response, principal, "APPROVED", blankToNull(decision != null ? decision.comments() : null));
        return response;
    }

    @Override
    @Transactional
    public RegularizationResponse reject(UserPrincipal principal, Long id, RequestDecisionRequest decision) {
        AttendanceRegularization regularization = requirePending(id);
        assertCanAct(principal, regularization);

        String comments = decision != null ? blankToNull(decision.comments()) : null;
        if (comments == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Give a reason for the rejection so the employee knows why.");
        }

        RegularizationApproval level = currentLevel(regularization);
        level.setAction(ApprovalAction.REJECTED);
        level.setApproverEmployeeId(principal.getEmployeeId());
        level.setComments(comments);
        level.setActedAt(Instant.now());
        approvalRepository.save(level);

        regularization.setStatus(RequestStatus.REJECTED);
        regularization.setDecidedAt(Instant.now());
        regularization.setCurrentApproverEmployeeId(null);
        regularizationRepository.save(regularization);

        auditLogService.record(AuditAction.REJECT, "AttendanceRegularization", regularization.getId(),
                "Rejected correction of " + regularization.getAttendanceDate() + ": " + comments);

        RegularizationResponse response = toResponse(regularization, principal);
        eventPublisher.regularizationDecided(response, principal, "REJECTED", comments);
        return response;
    }

    @Override
    @Transactional
    public RegularizationResponse withdraw(UserPrincipal principal, Long id, String reason) {
        AttendanceRegularization regularization = regularizationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Regularization request not found"));

        if (!regularization.getEmployee().getId().equals(principal.getEmployeeId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "That is not your request.");
        }
        if (regularization.getStatus() != RequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Only a request still awaiting a decision can be withdrawn.");
        }

        regularization.setStatus(RequestStatus.WITHDRAWN);
        regularization.setDecidedAt(Instant.now());
        regularization.setCurrentApproverEmployeeId(null);
        regularization.setCancellationReason(blankToNull(reason));
        regularizationRepository.save(regularization);

        auditLogService.record(AuditAction.CANCEL, "AttendanceRegularization", regularization.getId(),
                "Withdrew " + regularization.getRequestNumber());

        RegularizationResponse response = toResponse(regularization, principal);
        eventPublisher.regularizationDecided(response, principal, "WITHDRAWN", reason);
        return response;
    }

    // -----------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RegularizationResponse> getMyRequests(UserPrincipal principal, String status,
                                                               Pageable pageable) {
        Employee employee = requireEmployee(principal);

        Page<AttendanceRegularization> page = (status == null || status.isBlank())
                ? regularizationRepository.findByEmployeeIdOrderByAppliedAtDesc(employee.getId(), pageable)
                : regularizationRepository.findByEmployeeIdAndStatusOrderByAppliedAtDesc(
                        employee.getId(), parseStatus(status), pageable);

        return PageResponse.of(page, request -> toResponse(request, principal));
    }

    @Override
    @Transactional(readOnly = true)
    public RegularizationResponse getById(UserPrincipal principal, Long id) {
        AttendanceRegularization regularization = regularizationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Regularization request not found"));

        if (!regularization.getEmployee().getId().equals(principal.getEmployeeId())) {
            AccessScope scope = accessScopeResolver.resolve(principal, PERM_VIEW_ALL, PERM_VIEW_TEAM);
            accessScopeResolver.assertCanAccess(principal, scope, regularization.getEmployee().getId());
        }
        return toResponse(regularization, principal);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RegularizationResponse> getPendingApprovals(UserPrincipal principal, Pageable pageable) {
        if (principal.getEmployeeId() == null) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "This account is not linked to an employee record, so it has no approval inbox.");
        }
        // Same as the decision path: an L2 approver may act on any correction,
        // so unrouted ones surface here rather than sitting in no inbox.
        boolean canActOnAny = principal.hasPermission(PERM_APPROVE_L2);

        Page<AttendanceRegularization> page = regularizationRepository.findPendingForApprover(
                principal.getEmployeeId(),
                approverResolver.subordinateIdsOf(principal.getEmployeeId(), LocalDate.now()),
                canActOnAny,
                pageable);
        return PageResponse.of(page, request -> toResponse(request, principal));
    }

    // -----------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------

    /**
     * Corrections have a window. Without one, a year-old day could be rewritten
     * long after payroll closed on it.
     */
    private void validateWindow(LocalDate date, AttendancePolicy policy) {
        LocalDate today = LocalDate.now();

        if (date.isAfter(today)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "You cannot correct a day that has not happened yet.");
        }

        long age = ChronoUnit.DAYS.between(date, today);
        if (age > policy.getRegularizationWindowDays()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Corrections can be raised within " + policy.getRegularizationWindowDays()
                            + " day(s). " + date + " is " + age + " days ago — ask HR to adjust it.");
        }
    }

    private void validateMonthlyCap(Employee employee, LocalDate date, AttendancePolicy policy) {
        LocalDate monthStart = date.withDayOfMonth(1);
        LocalDate monthEnd = date.withDayOfMonth(date.lengthOfMonth());

        long used = regularizationRepository.countInPeriod(employee.getId(), monthStart, monthEnd);
        if (used >= policy.getMaxRegularizationsPerMonth()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "You have used all " + policy.getMaxRegularizationsPerMonth()
                            + " corrections allowed for " + date.getMonth() + ".");
        }
    }

    /**
     * Checks the request actually changes something, and that the times make
     * sense together.
     */
    private void validateRequestedTimes(RegularizationApplyRequest request,
                                        RegularizationReason reasonType,
                                        Optional<Attendance> existing) {
        Instant in = request.requestedPunchInTime();
        Instant out = request.requestedPunchOutTime();

        if (in == null && out == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Give at least a punch-in or a punch-out time to correct.");
        }
        if (in != null && out != null && !out.isAfter(in)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "The punch-out time must be after the punch-in time.");
        }
        if (in != null && in.isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A punch-in time cannot be in the future.");
        }
        if (out != null && out.isAfter(Instant.now())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A punch-out time cannot be in the future.");
        }

        // Asking to change nothing wastes an approver's attention.
        existing.ifPresent(attendance -> {
            boolean inSame = in == null || in.equals(attendance.getPunchInTime());
            boolean outSame = out == null || out.equals(attendance.getPunchOutTime());
            if (inSame && outSame && request.requestedWorkMode() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Those times already match what is recorded, so there is nothing to correct.");
            }
        });

        if (reasonType == RegularizationReason.FORGOT_PUNCH_IN && in == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A forgotten punch-in needs a punch-in time.");
        }
        if (reasonType == RegularizationReason.FORGOT_PUNCH_OUT && out == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "A forgotten punch-out needs a punch-out time.");
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private void recordCorrectionEvent(Attendance attendance, Employee employee,
                                       AttendanceRegularization regularization) {
        AttendancePunchEvent event = new AttendancePunchEvent();
        event.setAttendance(attendance);
        event.setEmployeeId(employee.getId());
        // The correction is logged against whichever end was actually changed.
        event.setEventType(regularization.getRequestedPunchOutTime() != null
                ? PunchEventType.PUNCH_OUT : PunchEventType.PUNCH_IN);
        event.setEventTime(regularization.getRequestedPunchOutTime() != null
                ? regularization.getRequestedPunchOutTime() : regularization.getRequestedPunchInTime());
        event.setWorkMode(attendance.getWorkMode());
        event.setSource(AttendanceSource.REGULARIZATION);
        event.setCreatedAt(Instant.now());
        punchEventRepository.save(event);
    }

    private Employee requireEmployee(UserPrincipal principal) {
        if (principal.getEmployeeId() == null) {
            throw new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND,
                    "This account is not linked to an employee record.");
        }
        return employeeRepository.findById(principal.getEmployeeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found"));
    }

    private AttendanceRegularization requirePending(Long id) {
        AttendanceRegularization regularization = regularizationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Regularization request not found"));

        if (regularization.getStatus() != RequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "This request has already been "
                            + regularization.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
        return regularization;
    }

    private void assertCanAct(UserPrincipal principal, AttendanceRegularization regularization) {
        boolean override = principal.hasPermission(PERM_APPROVE_L2);

        boolean allowed = approverResolver.canActOn(
                regularization.getEmployee().getId(),
                regularization.getCurrentApproverEmployeeId(),
                principal.getEmployeeId(),
                regularization.getAttendanceDate(),
                override);

        if (!allowed) {
            if (regularization.getEmployee().getId().equals(principal.getEmployeeId())) {
                throw new BusinessException(ErrorCode.NOT_AUTHORIZED_APPROVER,
                        "You cannot approve your own correction.");
            }
            throw new BusinessException(ErrorCode.NOT_AUTHORIZED_APPROVER,
                    "This request is not assigned to you for approval.");
        }
    }

    private RegularizationApproval currentLevel(AttendanceRegularization regularization) {
        return approvalRepository
                .findByRegularizationIdAndLevel(regularization.getId(), regularization.getCurrentLevel())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "The approval chain for this request is incomplete."));
    }

    private Map<String, Object> snapshot(Attendance attendance) {
        Map<String, Object> values = new HashMap<>();
        values.put("punchInTime", attendance.getPunchInTime() != null
                ? attendance.getPunchInTime().toString() : null);
        values.put("punchOutTime", attendance.getPunchOutTime() != null
                ? attendance.getPunchOutTime().toString() : null);
        values.put("workingMinutes", attendance.getWorkingMinutes());
        values.put("status", attendance.getAttendanceStatus() != null
                ? attendance.getAttendanceStatus().name() : null);
        return values;
    }

    private RegularizationReason parseReason(String value) {
        try {
            return RegularizationReason.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Reason type must be one of FORGOT_PUNCH_IN, FORGOT_PUNCH_OUT, WRONG_PUNCH, "
                            + "TECHNICAL_ISSUE, CLIENT_LOCATION, WORK_FROM_HOME or OTHER.");
        }
    }

    private WorkMode parseWorkMode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return WorkMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Work mode must be OFFICE, WFH, FIELD or CLIENT_SITE.");
        }
    }

    private RequestStatus parseStatus(String value) {
        try {
            return RequestStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Status must be PENDING, APPROVED, REJECTED, WITHDRAWN or CANCELLED.");
        }
    }

    private String nextRequestNumber(LocalDate date) {
        return String.format("RG-%d-%06d", date.getYear(), regularizationRepository.count() + 1);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private RegularizationResponse toResponse(AttendanceRegularization regularization,
                                              UserPrincipal principal) {
        Employee employee = regularization.getEmployee();

        List<RegularizationResponse.ApprovalStep> approvals = approvalRepository
                .findByRegularizationIdOrderByLevelAsc(regularization.getId()).stream()
                .map(step -> new RegularizationResponse.ApprovalStep(
                        step.getLevel(),
                        step.getApproverEmployeeId(),
                        nameOf(step.getApproverEmployeeId()),
                        step.getApproverRole(),
                        step.getAction().name(),
                        step.getComments(),
                        step.getActedAt()))
                .toList();

        boolean own = employee.getId().equals(principal.getEmployeeId());

        return new RegularizationResponse(
                regularization.getId(),
                regularization.getRequestNumber(),
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                regularization.getAttendanceDate(),
                regularization.getAttendanceDate().getDayOfWeek().name(),
                regularization.getReasonType().name(),
                regularization.getReason(),
                regularization.getAttachmentUrl(),
                regularization.getRequestedPunchInTime(),
                regularization.getRequestedPunchOutTime(),
                regularization.getRequestedWorkMode() != null
                        ? regularization.getRequestedWorkMode().name() : null,
                regularization.getOriginalPunchInTime(),
                regularization.getOriginalPunchOutTime(),
                regularization.getOriginalWorkingMinutes(),
                regularization.getOriginalStatus() != null
                        ? regularization.getOriginalStatus().name() : null,
                regularization.isCreatingMissingDay(),
                regularization.getStatus().name(),
                regularization.getCurrentLevel(),
                regularization.getCurrentApproverEmployeeId(),
                nameOf(regularization.getCurrentApproverEmployeeId()),
                regularization.getAppliedAt(),
                regularization.getDecidedAt(),
                regularization.getCancellationReason(),
                own && regularization.getStatus() == RequestStatus.PENDING,
                approvals);
    }

    private String nameOf(Long employeeId) {
        if (employeeId == null) {
            return null;
        }
        return employeeRepository.findById(employeeId).map(Employee::getFullName).orElse(null);
    }
}
