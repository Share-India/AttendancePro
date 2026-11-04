package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.leave.LeaveApplyRequest;
import com.company.attendance.dto.request.leave.LeaveCancelRequest;
import com.company.attendance.dto.request.leave.LeaveDecisionRequest;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.leave.LeaveApplyResultResponse;
import com.company.attendance.dto.response.leave.LeaveBalanceResponse;
import com.company.attendance.dto.response.leave.LeaveRequestResponse;
import com.company.attendance.dto.response.leave.LeaveTypeResponse;
import com.company.attendance.entity.Attendance;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.LeaveApproval;
import com.company.attendance.entity.LeaveRequest;
import com.company.attendance.entity.LeaveRequestDay;
import com.company.attendance.entity.LeaveType;
import com.company.attendance.event.RequestEventPublisher;
import com.company.attendance.enums.AccessScope;
import com.company.attendance.enums.ApprovalAction;
import com.company.attendance.enums.AttendanceSource;
import com.company.attendance.enums.AttendanceStatus;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.DayPortion;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.RequestStatus;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.AttendanceRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.LeaveApprovalRepository;
import com.company.attendance.repository.LeaveRequestDayRepository;
import com.company.attendance.repository.LeaveRequestRepository;
import com.company.attendance.repository.LeaveTypeRepository;
import com.company.attendance.security.AccessScopeResolver;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.LeaveApprovalChainService;
import com.company.attendance.service.LeaveBalanceService;
import com.company.attendance.service.LeaveDayExpansionService;
import com.company.attendance.service.LeaveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class LeaveServiceImpl implements LeaveService {

    private static final Logger log = LoggerFactory.getLogger(LeaveServiceImpl.class);

    private static final String PERM_VIEW_ALL = "LEAVE_CANCEL_ANY";
    private static final String PERM_APPROVE_L2 = "LEAVE_APPROVE_L2";

    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveRequestDayRepository leaveRequestDayRepository;
    private final LeaveApprovalRepository leaveApprovalRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceRepository attendanceRepository;
    private final LeaveBalanceService leaveBalanceService;
    private final LeaveDayExpansionService dayExpansionService;
    private final LeaveApprovalChainService approvalChainService;
    private final AccessScopeResolver accessScopeResolver;
    private final AuditLogService auditLogService;
    private final RequestEventPublisher eventPublisher;

    public LeaveServiceImpl(LeaveRequestRepository leaveRequestRepository,
                            LeaveRequestDayRepository leaveRequestDayRepository,
                            LeaveApprovalRepository leaveApprovalRepository,
                            LeaveTypeRepository leaveTypeRepository,
                            EmployeeRepository employeeRepository,
                            AttendanceRepository attendanceRepository,
                            LeaveBalanceService leaveBalanceService,
                            LeaveDayExpansionService dayExpansionService,
                            LeaveApprovalChainService approvalChainService,
                            AccessScopeResolver accessScopeResolver,
                            AuditLogService auditLogService,
                            RequestEventPublisher eventPublisher) {
        this.leaveRequestRepository = leaveRequestRepository;
        this.leaveRequestDayRepository = leaveRequestDayRepository;
        this.leaveApprovalRepository = leaveApprovalRepository;
        this.leaveTypeRepository = leaveTypeRepository;
        this.employeeRepository = employeeRepository;
        this.attendanceRepository = attendanceRepository;
        this.leaveBalanceService = leaveBalanceService;
        this.dayExpansionService = dayExpansionService;
        this.approvalChainService = approvalChainService;
        this.accessScopeResolver = accessScopeResolver;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
    }

    // -----------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<LeaveTypeResponse> findLeaveTypes() {
        return leaveTypeRepository.findByActiveTrueOrderByDisplayOrderAsc().stream()
                .map(this::toTypeResponse)
                .toList();
    }

    @Override
    @Transactional
    public List<LeaveBalanceResponse> getMyBalances(UserPrincipal principal) {
        Employee employee = requireEmployee(principal);
        short year = leaveBalanceService.resolveLeaveYear(LocalDate.now());
        return leaveBalanceService.getBalances(employee, year);
    }

    // -----------------------------------------------------------------
    // Apply
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public LeaveApplyResultResponse apply(UserPrincipal principal, LeaveApplyRequest request) {
        Employee employee = requireEmployee(principal);

        LeaveType leaveType = leaveTypeRepository.findById(request.leaveTypeId())
                .filter(LeaveType::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "That leave type is not available."));

        DayPortion fromPortion = parsePortion(request.fromDayPortion(), leaveType);
        DayPortion toPortion = parsePortion(request.toDayPortion(), leaveType);

        validateDates(request, leaveType);
        validateEligibility(employee, leaveType);

        var expansion = dayExpansionService.expand(
                employee, leaveType, request.fromDate(), request.toDate(), fromPortion, toPortion);

        if (expansion.deductibleDays().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Every day in that range is a holiday or weekly off, so there is nothing to apply for.");
        }

        validateConsecutiveLimit(leaveType, expansion.deductibleDays());
        validateAttachment(request, leaveType, expansion.deductibleDays());
        validateNoOverlap(employee, expansion.days(), null);

        short leaveYear = leaveBalanceService.resolveLeaveYear(request.fromDate());

        LeaveRequest leaveRequest = new LeaveRequest();
        leaveRequest.setRequestNumber(nextRequestNumber(request.fromDate()));
        leaveRequest.setEmployee(employee);
        leaveRequest.setLeaveType(leaveType);
        leaveRequest.setFromDate(request.fromDate());
        leaveRequest.setToDate(request.toDate());
        leaveRequest.setFromDayPortion(fromPortion);
        leaveRequest.setToDayPortion(toPortion);
        leaveRequest.setTotalDays(expansion.deductibleDays());
        leaveRequest.setReason(request.reason().trim());
        leaveRequest.setAttachmentUrl(blankToNull(request.attachmentUrl()));
        leaveRequest.setEmergencyContact(blankToNull(request.emergencyContact()));
        leaveRequest.setContactDuringLeave(blankToNull(request.contactDuringLeave()));
        leaveRequest.setStatus(RequestStatus.PENDING);
        leaveRequest.setAppliedAt(Instant.now());

        LeaveRequest saved = leaveRequestRepository.save(leaveRequest);

        expansion.days().forEach(day -> day.setLeaveRequest(saved));
        leaveRequestDayRepository.saveAll(expansion.days());

        // Reserve before routing: if the balance is short, nothing should have
        // been created at all, and the transaction rolls the request back too.
        leaveBalanceService.reserve(employee, leaveType, leaveYear, expansion.deductibleDays());

        approvalChainService.buildChain(saved)
                .ifPresent(saved::setCurrentApproverEmployeeId);
        leaveRequestRepository.save(saved);

        auditLogService.record(AuditAction.CREATE, "LeaveRequest", saved.getId(),
                "Applied for " + expansion.deductibleDays() + " day(s) of " + leaveType.getName()
                        + " from " + request.fromDate() + " to " + request.toDate(),
                null,
                Map.of("requestNumber", saved.getRequestNumber(),
                        "totalDays", expansion.deductibleDays(),
                        "status", saved.getStatus().name()));

        BigDecimal remaining = leaveBalanceService.getBalances(employee, leaveYear).stream()
                .filter(balance -> balance.leaveTypeId().equals(leaveType.getId()))
                .map(LeaveBalanceResponse::availableDays)
                .findFirst()
                .orElse(BigDecimal.ZERO);

        LeaveRequestResponse response = toResponse(saved, principal);
        eventPublisher.leaveRequested(response);
        return new LeaveApplyResultResponse(response, remaining, expansion.explanation());
    }

    // -----------------------------------------------------------------
    // Decisions
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public LeaveRequestResponse approve(UserPrincipal principal, Long requestId, LeaveDecisionRequest decision) {
        LeaveRequest request = requirePending(requestId);
        assertCanAct(principal, request);

        LeaveApproval level = currentLevel(request);
        level.setAction(ApprovalAction.APPROVED);
        level.setApproverEmployeeId(principal.getEmployeeId());
        level.setComments(blankToNull(decision != null ? decision.comments() : null));
        level.setActedAt(Instant.now());
        leaveApprovalRepository.save(level);

        Optional<LeaveApproval> next = leaveApprovalRepository
                .findByLeaveRequestIdOrderByLevelAsc(request.getId()).stream()
                .filter(step -> step.getLevel() > level.getLevel())
                .filter(step -> step.getAction() == ApprovalAction.PENDING)
                .findFirst();

        if (next.isPresent()) {
            // Another level still has to see it; the balance stays reserved.
            request.setCurrentLevel(next.get().getLevel());
            request.setCurrentApproverEmployeeId(next.get().getApproverEmployeeId());
            leaveRequestRepository.save(request);

            auditLogService.record(AuditAction.APPROVE, "LeaveRequest", request.getId(),
                    "Approved at level " + level.getLevel() + "; forwarded to level " + next.get().getLevel());

            // Forwarded rather than finished, so the next approver is the
            // one who needs telling, not the applicant.
            LeaveRequestResponse forwarded = toResponse(request, principal);
            eventPublisher.leaveRequested(forwarded);
            return forwarded;
        }

        finalise(request);
        LeaveRequestResponse response = toResponse(request, principal);
        eventPublisher.leaveDecided(response, principal, "APPROVED", blankToNull(decision != null ? decision.comments() : null));
        return response;
    }

    /** Everything that happens once the last level has approved. */
    private void finalise(LeaveRequest request) {
        short leaveYear = leaveBalanceService.resolveLeaveYear(request.getFromDate());

        request.setStatus(RequestStatus.APPROVED);
        request.setDecidedAt(Instant.now());
        request.setCurrentApproverEmployeeId(null);
        leaveRequestRepository.save(request);

        leaveBalanceService.consume(
                request.getEmployee(), request.getLeaveType(), leaveYear, request.getTotalDays());

        markAttendance(request);

        auditLogService.record(AuditAction.APPROVE, "LeaveRequest", request.getId(),
                "Approved " + request.getRequestNumber() + " — " + request.getTotalDays()
                        + " day(s) of " + request.getLeaveType().getName(),
                Map.of("status", RequestStatus.PENDING.name()),
                Map.of("status", RequestStatus.APPROVED.name()));
    }

    /**
     * Writes the leave onto the attendance calendar.
     *
     * <p>An existing day with a punch is left alone: someone who actually
     * worked has a record that must not be overwritten by a later approval.
     * A half-day leave still expects a punch for the working half, so it is
     * marked HALF_DAY_LEAVE rather than ON_LEAVE.
     */
    private void markAttendance(LeaveRequest request) {
        List<LeaveRequestDay> days = leaveRequestDayRepository
                .findByLeaveRequestIdOrderByLeaveDateAsc(request.getId());

        for (LeaveRequestDay day : days) {
            if (!day.isDeductible()) {
                continue;
            }

            Optional<Attendance> existing = attendanceRepository
                    .findByEmployeeIdAndAttendanceDate(request.getEmployee().getId(), day.getLeaveDate());

            if (existing.isPresent()) {
                Attendance attendance = existing.get();
                if (attendance.isPunchedIn()) {
                    log.info("Leave approved for {} on {} but a punch already exists; leaving it untouched",
                            request.getEmployee().getEmployeeCode(), day.getLeaveDate());
                    continue;
                }
                attendance.setAttendanceStatus(statusFor(day));
                attendance.setRemarks("Leave " + request.getRequestNumber());
                attendanceRepository.save(attendance);
                continue;
            }

            Attendance attendance = new Attendance();
            attendance.setEmployee(request.getEmployee());
            attendance.setAttendanceDate(day.getLeaveDate());
            attendance.setAttendanceStatus(statusFor(day));
            attendance.setSource(AttendanceSource.SYSTEM);
            attendance.setRemarks("Leave " + request.getRequestNumber());
            attendanceRepository.save(attendance);
        }
    }

    private AttendanceStatus statusFor(LeaveRequestDay day) {
        return day.getDayPortion().isHalf() ? AttendanceStatus.HALF_DAY_LEAVE : AttendanceStatus.ON_LEAVE;
    }

    @Override
    @Transactional
    public LeaveRequestResponse reject(UserPrincipal principal, Long requestId, LeaveDecisionRequest decision) {
        LeaveRequest request = requirePending(requestId);
        assertCanAct(principal, request);

        // A rejection without a reason leaves the employee with nothing to act
        // on, so it is required here even though approval's comment is optional.
        String comments = decision != null ? blankToNull(decision.comments()) : null;
        if (comments == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Give a reason for the rejection so the employee knows why.");
        }

        LeaveApproval level = currentLevel(request);
        level.setAction(ApprovalAction.REJECTED);
        level.setApproverEmployeeId(principal.getEmployeeId());
        level.setComments(comments);
        level.setActedAt(Instant.now());
        leaveApprovalRepository.save(level);

        request.setStatus(RequestStatus.REJECTED);
        request.setDecidedAt(Instant.now());
        request.setCurrentApproverEmployeeId(null);
        leaveRequestRepository.save(request);

        short leaveYear = leaveBalanceService.resolveLeaveYear(request.getFromDate());
        leaveBalanceService.release(request.getEmployee(), request.getLeaveType(),
                leaveYear, request.getTotalDays(), "request rejected");

        auditLogService.record(AuditAction.REJECT, "LeaveRequest", request.getId(),
                "Rejected " + request.getRequestNumber() + ": " + comments,
                Map.of("status", RequestStatus.PENDING.name()),
                Map.of("status", RequestStatus.REJECTED.name()));

        LeaveRequestResponse response = toResponse(request, principal);
        eventPublisher.leaveDecided(response, principal, "REJECTED", comments);
        return response;
    }

    // -----------------------------------------------------------------
    // Withdraw and cancel
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public LeaveRequestResponse withdraw(UserPrincipal principal, Long requestId, LeaveCancelRequest request) {
        LeaveRequest leaveRequest = requireOwn(principal, requestId);

        if (leaveRequest.getStatus() != RequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Only a request still awaiting a decision can be withdrawn. "
                            + "This one is " + leaveRequest.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }

        leaveRequest.setStatus(RequestStatus.WITHDRAWN);
        leaveRequest.setCancelledAt(Instant.now());
        leaveRequest.setCancellationReason(blankToNull(request != null ? request.reason() : null));
        leaveRequest.setCurrentApproverEmployeeId(null);
        leaveRequestRepository.save(leaveRequest);

        short leaveYear = leaveBalanceService.resolveLeaveYear(leaveRequest.getFromDate());
        leaveBalanceService.release(leaveRequest.getEmployee(), leaveRequest.getLeaveType(),
                leaveYear, leaveRequest.getTotalDays(), "withdrawn by employee");

        auditLogService.record(AuditAction.CANCEL, "LeaveRequest", leaveRequest.getId(),
                "Withdrew " + leaveRequest.getRequestNumber());

        LeaveRequestResponse response = toResponse(leaveRequest, principal);
        eventPublisher.leaveDecided(response, principal, "WITHDRAWN", null);
        return response;
    }

    /**
     * Cancels an approved leave.
     *
     * <p>Only days that have not yet passed are refunded. Days already taken
     * stay consumed — payroll has read them, and giving them back would let
     * someone take leave and then reclaim it afterwards.
     */
    @Override
    @Transactional
    public LeaveRequestResponse cancel(UserPrincipal principal, Long requestId, LeaveCancelRequest request) {
        LeaveRequest leaveRequest = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LEAVE_REQUEST_NOT_FOUND,
                        "Leave request not found"));

        boolean own = leaveRequest.getEmployee().getId().equals(principal.getEmployeeId());
        if (!own && !principal.hasPermission(PERM_VIEW_ALL)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "You can only cancel your own leave.");
        }

        if (leaveRequest.getStatus() != RequestStatus.APPROVED) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Only an approved request can be cancelled. Use withdraw for one still pending.");
        }

        LocalDate today = LocalDate.now();
        if (leaveRequest.getToDate().isBefore(today)) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "That leave has already finished and can no longer be cancelled. "
                            + "Ask HR to adjust your balance if it was not taken.");
        }

        List<LeaveRequestDay> refundable = leaveRequestDayRepository
                .findDeductibleFrom(leaveRequest.getId(), today);

        BigDecimal refundDays = refundable.stream()
                .map(day -> day.getDayPortion().days())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        leaveRequest.setStatus(RequestStatus.CANCELLED);
        leaveRequest.setCancelledAt(Instant.now());
        leaveRequest.setCancellationReason(blankToNull(request != null ? request.reason() : null));
        leaveRequestRepository.save(leaveRequest);

        short leaveYear = leaveBalanceService.resolveLeaveYear(leaveRequest.getFromDate());
        leaveBalanceService.refund(leaveRequest.getEmployee(), leaveRequest.getLeaveType(),
                leaveYear, refundDays, "leave cancelled from " + today);

        // Clear the leave marking from days that will now be worked.
        refundable.forEach(day -> attendanceRepository
                .findByEmployeeIdAndAttendanceDate(leaveRequest.getEmployee().getId(), day.getLeaveDate())
                .filter(attendance -> !attendance.isPunchedIn())
                .ifPresent(attendanceRepository::delete));

        auditLogService.record(AuditAction.CANCEL, "LeaveRequest", leaveRequest.getId(),
                "Cancelled " + leaveRequest.getRequestNumber() + "; refunded " + refundDays
                        + " of " + leaveRequest.getTotalDays() + " day(s)",
                Map.of("status", RequestStatus.APPROVED.name()),
                Map.of("status", RequestStatus.CANCELLED.name(), "refundedDays", refundDays));

        LeaveRequestResponse response = toResponse(leaveRequest, principal);
        eventPublisher.leaveDecided(response, principal, "CANCELLED", null);
        return response;
    }

    // -----------------------------------------------------------------
    // Listing
    // -----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LeaveRequestResponse> getMyRequests(UserPrincipal principal, String status,
                                                            Pageable pageable) {
        Employee employee = requireEmployee(principal);

        Page<LeaveRequest> page = (status == null || status.isBlank())
                ? leaveRequestRepository.findByEmployeeIdOrderByAppliedAtDesc(employee.getId(), pageable)
                : leaveRequestRepository.findByEmployeeIdAndStatusOrderByAppliedAtDesc(
                        employee.getId(), parseStatus(status), pageable);

        return PageResponse.of(page, request -> toResponse(request, principal));
    }

    @Override
    @Transactional(readOnly = true)
    public LeaveRequestResponse getById(UserPrincipal principal, Long requestId) {
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LEAVE_REQUEST_NOT_FOUND,
                        "Leave request not found"));

        boolean own = request.getEmployee().getId().equals(principal.getEmployeeId());
        if (!own) {
            AccessScope scope = accessScopeResolver.resolve(principal, PERM_VIEW_ALL, "LEAVE_APPROVE_L1");
            accessScopeResolver.assertCanAccess(principal, scope, request.getEmployee().getId());
        }

        return toResponse(request, principal);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LeaveRequestResponse> getPendingApprovals(UserPrincipal principal, Pageable pageable) {
        if (principal.getEmployeeId() == null) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "This account is not linked to an employee record, so it has no approval inbox.");
        }
        Page<LeaveRequest> page = leaveRequestRepository
                .findPendingForApprover(principal.getEmployeeId(), pageable);
        return PageResponse.of(page, request -> toResponse(request, principal));
    }

    // -----------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------

    private void validateDates(LeaveApplyRequest request, LeaveType leaveType) {
        if (request.toDate().isBefore(request.fromDate())) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "The end date is before the start date.");
        }

        LocalDate today = LocalDate.now();

        if (request.fromDate().isBefore(today) && !leaveType.isAllowBackdated()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    leaveType.getName() + " cannot be applied for retrospectively. "
                            + "Ask HR to record it for you.");
        }

        if (!leaveType.isExemptFromNotice() && leaveType.getMinNoticeDays() > 0) {
            long notice = ChronoUnit.DAYS.between(today, request.fromDate());
            if (notice < leaveType.getMinNoticeDays()) {
                throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                        leaveType.getName() + " needs " + leaveType.getMinNoticeDays()
                                + " day(s) notice; this request gives " + Math.max(0, notice) + ".");
            }
        }
    }

    private void validateEligibility(Employee employee, LeaveType leaveType) {
        if (!leaveType.appliesToGender(employee.getGender())) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    leaveType.getName() + " is not available to you.");
        }

        if (leaveType.getMinServiceDays() > 0 && employee.getJoiningDate() != null) {
            long service = ChronoUnit.DAYS.between(employee.getJoiningDate(), LocalDate.now());
            if (service < leaveType.getMinServiceDays()) {
                throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                        leaveType.getName() + " becomes available after "
                                + leaveType.getMinServiceDays() + " days of service.");
            }
        }
    }

    private void validateConsecutiveLimit(LeaveType leaveType, BigDecimal days) {
        Short max = leaveType.getMaxConsecutiveDays();
        if (max != null && days.compareTo(BigDecimal.valueOf(max)) > 0) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    leaveType.getName() + " allows at most " + max + " consecutive day(s).");
        }
    }

    private void validateAttachment(LeaveApplyRequest request, LeaveType leaveType, BigDecimal days) {
        boolean hasAttachment = request.attachmentUrl() != null && !request.attachmentUrl().isBlank();

        if (leaveType.isRequiresAttachment() && !hasAttachment) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    leaveType.getName() + " requires a supporting document.");
        }

        Short threshold = leaveType.getAttachmentRequiredAfterDays();
        if (threshold != null && days.compareTo(BigDecimal.valueOf(threshold)) > 0 && !hasAttachment) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    leaveType.getName() + " needs a supporting document for more than "
                            + threshold + " day(s).");
        }
    }

    /**
     * Rejects dates the employee has already committed to.
     *
     * <p>Compared date by date through the exploded day rows rather than by
     * range arithmetic, which has to reason about overlap in four directions
     * and still gets half-days wrong.
     */
    private void validateNoOverlap(Employee employee, List<LeaveRequestDay> days, Long excludeRequestId) {
        List<LocalDate> dates = days.stream().map(LeaveRequestDay::getLeaveDate).toList();

        List<LocalDate> conflicts = leaveRequestRepository
                .findConflictingDates(employee.getId(), dates, excludeRequestId);

        if (!conflicts.isEmpty()) {
            List<LocalDate> shown = conflicts.stream().sorted().limit(5).toList();
            throw new BusinessException(ErrorCode.OVERLAPPING_LEAVE,
                    "You already have leave covering " + shown
                            + (conflicts.size() > shown.size() ? " and " + (conflicts.size() - shown.size())
                                    + " more date(s)." : "."),
                    Map.of("conflictingDates", conflicts));
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private Employee requireEmployee(UserPrincipal principal) {
        if (principal.getEmployeeId() == null) {
            throw new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND,
                    "This account is not linked to an employee record.");
        }
        return employeeRepository.findById(principal.getEmployeeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found"));
    }

    private LeaveRequest requireOwn(UserPrincipal principal, Long requestId) {
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LEAVE_REQUEST_NOT_FOUND,
                        "Leave request not found"));

        if (!request.getEmployee().getId().equals(principal.getEmployeeId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "That is not your leave request.");
        }
        return request;
    }

    private LeaveRequest requirePending(Long requestId) {
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.LEAVE_REQUEST_NOT_FOUND,
                        "Leave request not found"));

        if (request.getStatus() != RequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "This request has already been "
                            + request.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
        return request;
    }

    private void assertCanAct(UserPrincipal principal, LeaveRequest request) {
        boolean override = principal.hasPermission(PERM_APPROVE_L2) || principal.hasPermission(PERM_VIEW_ALL);

        if (!approvalChainService.canActOn(request, principal.getEmployeeId(), override)) {
            if (request.getEmployee().getId().equals(principal.getEmployeeId())) {
                throw new BusinessException(ErrorCode.NOT_AUTHORIZED_APPROVER,
                        "You cannot approve your own leave.");
            }
            throw new BusinessException(ErrorCode.NOT_AUTHORIZED_APPROVER,
                    "This request is not assigned to you for approval.");
        }
    }

    private LeaveApproval currentLevel(LeaveRequest request) {
        return leaveApprovalRepository
                .findByLeaveRequestIdAndLevel(request.getId(), request.getCurrentLevel())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "The approval chain for this request is incomplete."));
    }

    private DayPortion parsePortion(String value, LeaveType leaveType) {
        if (value == null || value.isBlank()) {
            return DayPortion.FULL_DAY;
        }
        DayPortion portion;
        try {
            portion = DayPortion.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Day portion must be FULL_DAY, FIRST_HALF or SECOND_HALF.");
        }
        if (portion.isHalf() && !leaveType.isAllowHalfDay()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    leaveType.getName() + " cannot be taken as a half day.");
        }
        return portion;
    }

    private RequestStatus parseStatus(String value) {
        try {
            return RequestStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Status must be PENDING, APPROVED, REJECTED, WITHDRAWN or CANCELLED.");
        }
    }

    /**
     * A readable reference. Derived from the count in the year rather than the
     * row id, so the number does not leak how many requests exist system-wide.
     */
    private String nextRequestNumber(LocalDate forDate) {
        int year = forDate.getYear();
        long sequence = leaveRequestRepository.count() + 1;
        return String.format("LV-%d-%06d", year, sequence);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    // -----------------------------------------------------------------
    // Mapping
    // -----------------------------------------------------------------

    private LeaveTypeResponse toTypeResponse(LeaveType type) {
        return new LeaveTypeResponse(
                type.getId(), type.getCode(), type.getName(), type.getDescription(),
                type.isPaid(), type.isAllowHalfDay(), type.isRequiresAttachment(),
                type.getAttachmentRequiredAfterDays(), type.getMinNoticeDays(),
                type.getMaxConsecutiveDays(), type.isExemptFromNotice(), type.isAllowBackdated(),
                type.getApplicableGender(), type.isCountsHolidaysWeeklyOffs(),
                type.isAllowNegativeBalance(), type.getHrApprovalThresholdDays(), type.isActive());
    }

    private LeaveRequestResponse toResponse(LeaveRequest request, UserPrincipal principal) {
        Employee employee = request.getEmployee();
        LeaveType type = request.getLeaveType();

        List<LeaveRequestResponse.DayBreakdown> days = leaveRequestDayRepository
                .findByLeaveRequestIdOrderByLeaveDateAsc(request.getId()).stream()
                .map(day -> new LeaveRequestResponse.DayBreakdown(
                        day.getLeaveDate(),
                        day.getLeaveDate().getDayOfWeek().name(),
                        day.getDayPortion().name(),
                        day.isDeductible(),
                        day.getDayType().name()))
                .toList();

        List<LeaveRequestResponse.ApprovalStep> approvals = leaveApprovalRepository
                .findByLeaveRequestIdOrderByLevelAsc(request.getId()).stream()
                .map(step -> new LeaveRequestResponse.ApprovalStep(
                        step.getLevel(),
                        step.getApproverEmployeeId(),
                        nameOf(step.getApproverEmployeeId()),
                        step.getApproverRole(),
                        step.getAction().name(),
                        step.getComments(),
                        step.getActedAt()))
                .toList();

        boolean own = employee.getId().equals(principal.getEmployeeId());
        boolean future = !request.getToDate().isBefore(LocalDate.now());

        return new LeaveRequestResponse(
                request.getId(),
                request.getRequestNumber(),
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                type.getId(), type.getCode(), type.getName(), type.isPaid(),
                request.getFromDate(),
                request.getToDate(),
                request.getFromDayPortion().name(),
                request.getToDayPortion().name(),
                request.getTotalDays(),
                (int) ChronoUnit.DAYS.between(request.getFromDate(), request.getToDate()) + 1,
                request.getReason(),
                request.getAttachmentUrl(),
                request.getContactDuringLeave(),
                request.getStatus().name(),
                request.getCurrentLevel(),
                request.getCurrentApproverEmployeeId(),
                nameOf(request.getCurrentApproverEmployeeId()),
                request.getAppliedAt(),
                request.getDecidedAt(),
                request.getCancellationReason(),
                own && request.getStatus() == RequestStatus.PENDING,
                own && request.getStatus() == RequestStatus.APPROVED && future,
                days,
                approvals);
    }

    private String nameOf(Long employeeId) {
        if (employeeId == null) {
            return null;
        }
        return employeeRepository.findById(employeeId).map(Employee::getFullName).orElse(null);
    }
}
