package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.request.RequestDecisionRequest;
import com.company.attendance.dto.request.request.WorkFromHomeApplyRequest;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.request.RegularizationResponse;
import com.company.attendance.dto.response.request.WorkFromHomeResponse;
import com.company.attendance.entity.AttendancePolicy;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.WfhApproval;
import com.company.attendance.entity.WfhRequestDay;
import com.company.attendance.entity.WorkFromHomeRequest;
import com.company.attendance.event.RequestEventPublisher;
import com.company.attendance.enums.AccessScope;
import com.company.attendance.enums.ApprovalAction;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.DayType;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.RequestStatus;
import com.company.attendance.enums.WorkLocation;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.WfhApprovalRepository;
import com.company.attendance.repository.WfhRequestDayRepository;
import com.company.attendance.repository.WorkFromHomeRequestRepository;
import com.company.attendance.security.AccessScopeResolver;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.CalendarService;
import com.company.attendance.service.PolicyResolutionService;
import com.company.attendance.service.RequestApproverResolver;
import com.company.attendance.service.WorkFromHomeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Work from home.
 *
 * <p>An approved request is what lets punch-in skip the geofence for those
 * dates, so this is the request type the attendance path consults on every
 * single punch. That read is a single indexed existence check, not a range
 * comparison — hence the exploded day rows.
 */
@Service
public class WorkFromHomeServiceImpl implements WorkFromHomeService {

    private static final Logger log = LoggerFactory.getLogger(WorkFromHomeServiceImpl.class);

    private static final String PERM_APPROVE = "WFH_APPROVE";
    private static final String PERM_VIEW_ALL = "ATTENDANCE_VIEW_ALL";
    private static final String PERM_VIEW_TEAM = "ATTENDANCE_VIEW_TEAM";

    private final WorkFromHomeRequestRepository wfhRepository;
    private final WfhRequestDayRepository wfhDayRepository;
    private final WfhApprovalRepository approvalRepository;
    private final EmployeeRepository employeeRepository;
    private final PolicyResolutionService policyResolutionService;
    private final CalendarService calendarService;
    private final RequestApproverResolver approverResolver;
    private final AccessScopeResolver accessScopeResolver;
    private final AuditLogService auditLogService;
    private final RequestEventPublisher eventPublisher;

    public WorkFromHomeServiceImpl(WorkFromHomeRequestRepository wfhRepository,
                                   WfhRequestDayRepository wfhDayRepository,
                                   WfhApprovalRepository approvalRepository,
                                   EmployeeRepository employeeRepository,
                                   PolicyResolutionService policyResolutionService,
                                   CalendarService calendarService,
                                   RequestApproverResolver approverResolver,
                                   AccessScopeResolver accessScopeResolver,
                                   AuditLogService auditLogService,
                                   RequestEventPublisher eventPublisher) {
        this.wfhRepository = wfhRepository;
        this.wfhDayRepository = wfhDayRepository;
        this.approvalRepository = approvalRepository;
        this.employeeRepository = employeeRepository;
        this.policyResolutionService = policyResolutionService;
        this.calendarService = calendarService;
        this.approverResolver = approverResolver;
        this.accessScopeResolver = accessScopeResolver;
        this.auditLogService = auditLogService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * The check punch-in calls on every punch.
     *
     * <p>Kept deliberately narrow — a boolean from one indexed query — because
     * it sits on the hot path of the most frequent operation in the system.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isApprovedOn(Long employeeId, LocalDate date) {
        return wfhDayRepository.isApprovedOn(employeeId, date);
    }

    // -----------------------------------------------------------------
    // Apply
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public WorkFromHomeResponse apply(UserPrincipal principal, WorkFromHomeApplyRequest request) {
        Employee employee = requireEmployee(principal);

        if (request.toDate().isBefore(request.fromDate())) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "The end date is before the start date.");
        }

        AttendancePolicy policy = policyResolutionService
                .resolveAttendancePolicy(employee, request.fromDate());

        validateNotice(request.fromDate(), policy);

        // Only working days are requested. Asking to work from home on a
        // Sunday is meaningless, and counting it against the cap would be worse.
        List<LocalDate> workingDays = new ArrayList<>();
        for (LocalDate date = request.fromDate(); !date.isAfter(request.toDate()); date = date.plusDays(1)) {
            if (calendarService.resolveDayType(employee, date) == DayType.WORKING) {
                workingDays.add(date);
            }
        }

        if (workingDays.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Every day in that range is a holiday or weekly off, so there is nothing to request.");
        }

        validateNoOverlap(employee, workingDays);
        validateMonthlyCap(employee, workingDays, policy);

        WorkFromHomeRequest wfh = new WorkFromHomeRequest();
        wfh.setRequestNumber(nextRequestNumber(request.fromDate()));
        wfh.setEmployee(employee);
        wfh.setFromDate(request.fromDate());
        wfh.setToDate(request.toDate());
        wfh.setTotalDays((short) workingDays.size());
        wfh.setReason(request.reason().trim());
        wfh.setWorkLocation(parseLocation(request.workLocation()));
        wfh.setContactNumber(blankToNull(request.contactNumber()));

        WorkFromHomeRequest saved = wfhRepository.save(wfh);

        List<WfhRequestDay> days = workingDays.stream().map(date -> {
            WfhRequestDay day = new WfhRequestDay();
            day.setWfhRequest(saved);
            day.setEmployeeId(employee.getId());
            day.setWfhDate(date);
            day.setDayType(DayType.WORKING);
            return day;
        }).toList();
        wfhDayRepository.saveAll(days);

        WfhApproval level = new WfhApproval();
        level.setWfhRequest(saved);
        level.setLevel((short) 1);
        level.setApproverRole("MANAGER");

        Optional<Employee> approver = approverResolver.resolveApprover(employee, request.fromDate());
        if (approver.isPresent()) {
            level.setApproverEmployeeId(approver.get().getId());
            saved.setCurrentApproverEmployeeId(approver.get().getId());
        } else {
            level.setComments("No approver could be resolved");
            log.warn("Work-from-home request {} has no approver", saved.getRequestNumber());
        }
        approvalRepository.save(level);
        wfhRepository.save(saved);

        auditLogService.record(AuditAction.CREATE, "WorkFromHomeRequest", saved.getId(),
                "Requested work from home for " + workingDays.size() + " day(s) from "
                        + request.fromDate(),
                null, Map.of("requestNumber", saved.getRequestNumber(), "days", workingDays.size()));

        WorkFromHomeResponse response = toResponse(saved, principal);
        eventPublisher.wfhRequested(response);
        return response;
    }

    // -----------------------------------------------------------------
    // Decisions
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public WorkFromHomeResponse approve(UserPrincipal principal, Long id, RequestDecisionRequest decision) {
        WorkFromHomeRequest wfh = requirePending(id);
        assertCanAct(principal, wfh);

        WfhApproval level = currentLevel(wfh);
        level.setAction(ApprovalAction.APPROVED);
        level.setApproverEmployeeId(principal.getEmployeeId());
        level.setComments(blankToNull(decision != null ? decision.comments() : null));
        level.setActedAt(Instant.now());
        approvalRepository.save(level);

        wfh.setStatus(RequestStatus.APPROVED);
        wfh.setDecidedAt(Instant.now());
        wfh.setCurrentApproverEmployeeId(null);
        wfhRepository.save(wfh);

        // Nothing is written to attendance here. The approval only grants
        // permission; the day is still recorded by an actual punch, which is
        // what keeps working-hour rules applying to remote days too.
        auditLogService.record(AuditAction.APPROVE, "WorkFromHomeRequest", wfh.getId(),
                "Approved " + wfh.getRequestNumber() + " — " + wfh.getTotalDays() + " day(s)",
                Map.of("status", RequestStatus.PENDING.name()),
                Map.of("status", RequestStatus.APPROVED.name()));

        WorkFromHomeResponse response = toResponse(wfh, principal);
        eventPublisher.wfhDecided(response, principal, "APPROVED", blankToNull(decision != null ? decision.comments() : null));
        return response;
    }

    @Override
    @Transactional
    public WorkFromHomeResponse reject(UserPrincipal principal, Long id, RequestDecisionRequest decision) {
        WorkFromHomeRequest wfh = requirePending(id);
        assertCanAct(principal, wfh);

        String comments = decision != null ? blankToNull(decision.comments()) : null;
        if (comments == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Give a reason for the rejection so the employee knows why.");
        }

        WfhApproval level = currentLevel(wfh);
        level.setAction(ApprovalAction.REJECTED);
        level.setApproverEmployeeId(principal.getEmployeeId());
        level.setComments(comments);
        level.setActedAt(Instant.now());
        approvalRepository.save(level);

        wfh.setStatus(RequestStatus.REJECTED);
        wfh.setDecidedAt(Instant.now());
        wfh.setCurrentApproverEmployeeId(null);
        wfhRepository.save(wfh);

        auditLogService.record(AuditAction.REJECT, "WorkFromHomeRequest", wfh.getId(),
                "Rejected " + wfh.getRequestNumber() + ": " + comments);

        WorkFromHomeResponse response = toResponse(wfh, principal);
        eventPublisher.wfhDecided(response, principal, "REJECTED", comments);
        return response;
    }

    @Override
    @Transactional
    public WorkFromHomeResponse withdraw(UserPrincipal principal, Long id, String reason) {
        WorkFromHomeRequest wfh = requireOwn(principal, id);

        if (wfh.getStatus() != RequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Only a request still awaiting a decision can be withdrawn.");
        }

        wfh.setStatus(RequestStatus.WITHDRAWN);
        wfh.setDecidedAt(Instant.now());
        wfh.setCurrentApproverEmployeeId(null);
        wfh.setCancellationReason(blankToNull(reason));
        wfhRepository.save(wfh);

        auditLogService.record(AuditAction.CANCEL, "WorkFromHomeRequest", wfh.getId(),
                "Withdrew " + wfh.getRequestNumber());

        WorkFromHomeResponse response = toResponse(wfh, principal);
        eventPublisher.wfhDecided(response, principal, "WITHDRAWN", reason);
        return response;
    }

    /**
     * Cancels an approved request.
     *
     * <p>Refused once the period has passed: days already worked from home
     * were worked under that approval, and removing it would leave punches
     * recorded against a permission that no longer exists.
     */
    @Override
    @Transactional
    public WorkFromHomeResponse cancel(UserPrincipal principal, Long id, String reason) {
        WorkFromHomeRequest wfh = requireOwn(principal, id);

        if (wfh.getStatus() != RequestStatus.APPROVED) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Only an approved request can be cancelled. Use withdraw for one still pending.");
        }
        if (wfh.getToDate().isBefore(LocalDate.now())) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "That period has already passed and can no longer be cancelled.");
        }

        wfh.setStatus(RequestStatus.CANCELLED);
        wfh.setCancellationReason(blankToNull(reason));
        wfhRepository.save(wfh);

        auditLogService.record(AuditAction.CANCEL, "WorkFromHomeRequest", wfh.getId(),
                "Cancelled " + wfh.getRequestNumber(),
                Map.of("status", RequestStatus.APPROVED.name()),
                Map.of("status", RequestStatus.CANCELLED.name()));

        WorkFromHomeResponse response = toResponse(wfh, principal);
        eventPublisher.wfhDecided(response, principal, "CANCELLED", reason);
        return response;
    }

    // -----------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PageResponse<WorkFromHomeResponse> getMyRequests(UserPrincipal principal, String status,
                                                             Pageable pageable) {
        Employee employee = requireEmployee(principal);

        Page<WorkFromHomeRequest> page = (status == null || status.isBlank())
                ? wfhRepository.findByEmployeeIdOrderByAppliedAtDesc(employee.getId(), pageable)
                : wfhRepository.findByEmployeeIdAndStatusOrderByAppliedAtDesc(
                        employee.getId(), parseStatus(status), pageable);

        return PageResponse.of(page, request -> toResponse(request, principal));
    }

    @Override
    @Transactional(readOnly = true)
    public WorkFromHomeResponse getById(UserPrincipal principal, Long id) {
        WorkFromHomeRequest wfh = wfhRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Work-from-home request not found"));

        if (!wfh.getEmployee().getId().equals(principal.getEmployeeId())) {
            AccessScope scope = accessScopeResolver.resolve(principal, PERM_VIEW_ALL, PERM_VIEW_TEAM);
            accessScopeResolver.assertCanAccess(principal, scope, wfh.getEmployee().getId());
        }
        return toResponse(wfh, principal);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<WorkFromHomeResponse> getPendingApprovals(UserPrincipal principal, Pageable pageable) {
        if (principal.getEmployeeId() == null) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "This account is not linked to an employee record, so it has no approval inbox.");
        }
        // Someone who may act on any request also sees the ones the resolver
        // could route to nobody, so an unroutable request still reaches a human.
        boolean canActOnAny = principal.hasPermission(PERM_APPROVE) && principal.hasPermission(PERM_VIEW_ALL);

        Page<WorkFromHomeRequest> page = wfhRepository.findPendingForApprover(
                principal.getEmployeeId(),
                approverResolver.subordinateIdsOf(principal.getEmployeeId(), LocalDate.now()),
                canActOnAny,
                pageable);
        return PageResponse.of(page, request -> toResponse(request, principal));
    }

    // -----------------------------------------------------------------
    // Validation
    // -----------------------------------------------------------------

    private void validateNotice(LocalDate fromDate, AttendancePolicy policy) {
        LocalDate today = LocalDate.now();

        if (fromDate.isBefore(today)) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Work from home cannot be requested for a day that has passed. "
                            + "Raise an attendance correction instead.");
        }

        if (policy.getWfhMinNoticeDays() > 0) {
            long notice = ChronoUnit.DAYS.between(today, fromDate);
            if (notice < policy.getWfhMinNoticeDays()) {
                throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                        "Work from home needs " + policy.getWfhMinNoticeDays()
                                + " day(s) notice; this gives " + Math.max(0, notice) + ".");
            }
        }
    }

    private void validateNoOverlap(Employee employee, List<LocalDate> dates) {
        List<LocalDate> conflicts = wfhDayRepository.findConflictingDates(employee.getId(), dates);

        if (!conflicts.isEmpty()) {
            List<LocalDate> shown = conflicts.stream().sorted().limit(5).toList();
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE,
                    "You already have a work-from-home request covering " + shown
                            + (conflicts.size() > shown.size()
                                    ? " and " + (conflicts.size() - shown.size()) + " more." : "."),
                    Map.of("conflictingDates", conflicts));
        }
    }

    /**
     * A monthly cap keeps work from home an arrangement rather than a default.
     * Counted per calendar month the request touches, so a range spanning a
     * month boundary is checked against both.
     */
    private void validateMonthlyCap(Employee employee, List<LocalDate> dates, AttendancePolicy policy) {
        dates.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        date -> date.withDayOfMonth(1), java.util.stream.Collectors.counting()))
                .forEach((monthStart, requested) -> {
                    LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
                    long already = wfhRepository.countCommittedDaysInPeriod(
                            employee.getId(), monthStart, monthEnd);

                    if (already + requested > policy.getMaxWfhDaysPerMonth()) {
                        throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                                "That would be " + (already + requested) + " work-from-home day(s) in "
                                        + monthStart.getMonth() + ", and the limit is "
                                        + policy.getMaxWfhDaysPerMonth() + ".");
                    }
                });
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

    private WorkFromHomeRequest requireOwn(UserPrincipal principal, Long id) {
        WorkFromHomeRequest wfh = wfhRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Work-from-home request not found"));

        if (!wfh.getEmployee().getId().equals(principal.getEmployeeId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "That is not your request.");
        }
        return wfh;
    }

    private WorkFromHomeRequest requirePending(Long id) {
        WorkFromHomeRequest wfh = wfhRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Work-from-home request not found"));

        if (wfh.getStatus() != RequestStatus.PENDING) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "This request has already been "
                            + wfh.getStatus().name().toLowerCase(Locale.ROOT) + ".");
        }
        return wfh;
    }

    private void assertCanAct(UserPrincipal principal, WorkFromHomeRequest wfh) {
        boolean allowed = approverResolver.canActOn(
                wfh.getEmployee().getId(),
                wfh.getCurrentApproverEmployeeId(),
                principal.getEmployeeId(),
                wfh.getFromDate(),
                principal.hasPermission(PERM_APPROVE) && principal.hasPermission(PERM_VIEW_ALL));

        if (!allowed) {
            if (wfh.getEmployee().getId().equals(principal.getEmployeeId())) {
                throw new BusinessException(ErrorCode.NOT_AUTHORIZED_APPROVER,
                        "You cannot approve your own request.");
            }
            throw new BusinessException(ErrorCode.NOT_AUTHORIZED_APPROVER,
                    "This request is not assigned to you for approval.");
        }
    }

    private WfhApproval currentLevel(WorkFromHomeRequest wfh) {
        return approvalRepository.findByWfhRequestIdAndLevel(wfh.getId(), wfh.getCurrentLevel())
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "The approval chain for this request is incomplete."));
    }

    private WorkLocation parseLocation(String value) {
        if (value == null || value.isBlank()) {
            return WorkLocation.HOME;
        }
        try {
            return WorkLocation.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Work location must be HOME or OTHER.");
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
        return String.format("WFH-%d-%06d", date.getYear(), wfhRepository.count() + 1);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private WorkFromHomeResponse toResponse(WorkFromHomeRequest wfh, UserPrincipal principal) {
        Employee employee = wfh.getEmployee();

        List<LocalDate> days = wfhDayRepository.findByWfhRequestIdOrderByWfhDateAsc(wfh.getId()).stream()
                .map(WfhRequestDay::getWfhDate)
                .toList();

        List<RegularizationResponse.ApprovalStep> approvals = approvalRepository
                .findByWfhRequestIdOrderByLevelAsc(wfh.getId()).stream()
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
        boolean future = !wfh.getToDate().isBefore(LocalDate.now());

        return new WorkFromHomeResponse(
                wfh.getId(),
                wfh.getRequestNumber(),
                employee.getId(),
                employee.getEmployeeCode(),
                employee.getFullName(),
                wfh.getFromDate(),
                wfh.getToDate(),
                wfh.getTotalDays(),
                (int) ChronoUnit.DAYS.between(wfh.getFromDate(), wfh.getToDate()) + 1,
                wfh.getReason(),
                wfh.getWorkLocation().name(),
                wfh.getContactNumber(),
                wfh.getStatus().name(),
                wfh.getCurrentLevel(),
                wfh.getCurrentApproverEmployeeId(),
                nameOf(wfh.getCurrentApproverEmployeeId()),
                wfh.getAppliedAt(),
                wfh.getDecidedAt(),
                wfh.getCancellationReason(),
                own && wfh.getStatus() == RequestStatus.PENDING,
                own && wfh.getStatus() == RequestStatus.APPROVED && future,
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
