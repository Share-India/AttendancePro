package com.company.attendance.controller;

import com.company.attendance.dto.request.leave.LeaveApplyRequest;
import com.company.attendance.dto.request.leave.LeaveBalanceAdjustRequest;
import com.company.attendance.dto.request.leave.LeaveCancelRequest;
import com.company.attendance.dto.request.leave.LeaveDecisionRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.leave.LeaveApplyResultResponse;
import com.company.attendance.dto.response.leave.LeaveBalanceResponse;
import com.company.attendance.dto.response.leave.LeaveRequestResponse;
import com.company.attendance.dto.response.leave.LeaveTypeResponse;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.LeaveType;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.LeaveTypeRepository;
import com.company.attendance.security.CurrentUser;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.LeaveBalanceService;
import com.company.attendance.service.LeaveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/leaves")
@Tag(name = "Leave", description = "Leave types, balances, applications and approvals")
public class LeaveController {

    private final LeaveService leaveService;
    private final LeaveBalanceService leaveBalanceService;
    private final EmployeeRepository employeeRepository;
    private final LeaveTypeRepository leaveTypeRepository;

    public LeaveController(LeaveService leaveService,
                           LeaveBalanceService leaveBalanceService,
                           EmployeeRepository employeeRepository,
                           LeaveTypeRepository leaveTypeRepository) {
        this.leaveService = leaveService;
        this.leaveBalanceService = leaveBalanceService;
        this.employeeRepository = employeeRepository;
        this.leaveTypeRepository = leaveTypeRepository;
    }

    @GetMapping("/types")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Leave types available to apply for")
    public ResponseEntity<ApiResponse<List<LeaveTypeResponse>>> types() {
        return ResponseEntity.ok(ApiResponse.success(leaveService.findLeaveTypes()));
    }

    @GetMapping("/balance")
    @PreAuthorize("hasAuthority('LEAVE_APPLY')")
    @Operation(summary = "Your balance for every active leave type")
    public ResponseEntity<ApiResponse<List<LeaveBalanceResponse>>> balance(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.getMyBalances(principal)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('LEAVE_APPLY')")
    @Operation(summary = "Apply for leave")
    public ResponseEntity<ApiResponse<LeaveApplyResultResponse>> apply(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody LeaveApplyRequest request) {

        LeaveApplyResultResponse result = leaveService.apply(principal, request);

        // The count can differ from the span the employee picked; saying so
        // here stops "I asked for four days and it took two" being a surprise.
        String message = result.countExplanation() != null
                ? "Leave applied for " + result.request().totalDays() + " day(s). "
                        + result.countExplanation()
                : "Leave applied for " + result.request().totalDays() + " day(s).";

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result, message));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAuthority('LEAVE_APPLY')")
    @Operation(summary = "Your leave requests")
    public ResponseEntity<ApiResponse<PageResponse<LeaveRequestResponse>>> myRequests(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "appliedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                leaveService.getMyRequests(principal, status, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One leave request, subject to your data scope")
    public ResponseEntity<ApiResponse<LeaveRequestResponse>> findById(
            @CurrentUser UserPrincipal principal, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.getById(principal, id)));
    }

    @GetMapping("/pending-approvals")
    @PreAuthorize("hasAnyAuthority('LEAVE_APPROVE_L1', 'LEAVE_APPROVE_L2')")
    @Operation(summary = "Requests waiting on you")
    public ResponseEntity<ApiResponse<PageResponse<LeaveRequestResponse>>> pendingApprovals(
            @CurrentUser UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                leaveService.getPendingApprovals(principal, pageable)));
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize("hasAnyAuthority('LEAVE_APPROVE_L1', 'LEAVE_APPROVE_L2')")
    @Operation(summary = "Approve a request at its current level")
    public ResponseEntity<ApiResponse<LeaveRequestResponse>> approve(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody(required = false) LeaveDecisionRequest decision) {

        LeaveRequestResponse result = leaveService.approve(principal, id, decision);

        String message = "APPROVED".equals(result.status())
                ? "Leave approved."
                : "Approved at your level. Forwarded to level " + result.currentLevel() + ".";

        return ResponseEntity.ok(ApiResponse.success(result, message));
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasAnyAuthority('LEAVE_APPROVE_L1', 'LEAVE_APPROVE_L2')")
    @Operation(summary = "Reject a request, stating why")
    public ResponseEntity<ApiResponse<LeaveRequestResponse>> reject(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody LeaveDecisionRequest decision) {
        return ResponseEntity.ok(ApiResponse.success(
                leaveService.reject(principal, id, decision), "Leave rejected."));
    }

    @PutMapping("/{id}/withdraw")
    @PreAuthorize("hasAuthority('LEAVE_APPLY')")
    @Operation(summary = "Withdraw your own request before it is decided")
    public ResponseEntity<ApiResponse<LeaveRequestResponse>> withdraw(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody(required = false) LeaveCancelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                leaveService.withdraw(principal, id, request), "Leave request withdrawn."));
    }

    @PutMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('LEAVE_APPLY')")
    @Operation(summary = "Cancel approved leave; only future days are refunded")
    public ResponseEntity<ApiResponse<LeaveRequestResponse>> cancel(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody(required = false) LeaveCancelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                leaveService.cancel(principal, id, request),
                "Leave cancelled. Days already taken remain deducted."));
    }

    @PutMapping("/balance/{employeeId}/adjust")
    @PreAuthorize("hasAuthority('LEAVE_BALANCE_ADJUST')")
    @Operation(summary = "Adjust an employee's balance, with a reason")
    public ResponseEntity<ApiResponse<List<LeaveBalanceResponse>>> adjustBalance(
            @PathVariable Long employeeId,
            @Valid @RequestBody LeaveBalanceAdjustRequest request) {

        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND, "Employee not found"));

        LeaveType leaveType = leaveTypeRepository.findById(request.leaveTypeId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR, "Unknown leave type"));

        short year = leaveBalanceService.resolveLeaveYear(LocalDate.now());
        leaveBalanceService.adjust(employee, leaveType, year, request.days(), request.reason());

        return ResponseEntity.ok(ApiResponse.success(
                leaveBalanceService.getBalances(employee, year), "Balance adjusted."));
    }
}
