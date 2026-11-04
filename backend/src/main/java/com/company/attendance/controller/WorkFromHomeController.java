package com.company.attendance.controller;

import com.company.attendance.dto.request.request.RequestDecisionRequest;
import com.company.attendance.dto.request.request.WorkFromHomeApplyRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.request.WorkFromHomeResponse;
import com.company.attendance.security.CurrentUser;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.WorkFromHomeService;
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

@RestController
@RequestMapping("/api/v1/wfh-requests")
@Tag(name = "Work from home", description = "Work-from-home requests and approvals")
public class WorkFromHomeController {

    private final WorkFromHomeService workFromHomeService;

    public WorkFromHomeController(WorkFromHomeService workFromHomeService) {
        this.workFromHomeService = workFromHomeService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('WFH_APPLY')")
    @Operation(summary = "Request to work from home")
    public ResponseEntity<ApiResponse<WorkFromHomeResponse>> apply(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody WorkFromHomeApplyRequest request) {

        WorkFromHomeResponse result = workFromHomeService.apply(principal, request);

        // The working-day count often differs from the span picked, so say it
        // rather than leaving the employee to work out why.
        String message = result.totalDays() == result.calendarDays()
                ? "Requested " + result.totalDays() + " day(s) working from home."
                : "Requested " + result.totalDays() + " working day(s) out of "
                        + result.calendarDays() + " calendar days.";

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result, message));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAuthority('WFH_APPLY')")
    @Operation(summary = "Your work-from-home requests")
    public ResponseEntity<ApiResponse<PageResponse<WorkFromHomeResponse>>> myRequests(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "appliedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                workFromHomeService.getMyRequests(principal, status, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One request, subject to your data scope")
    public ResponseEntity<ApiResponse<WorkFromHomeResponse>> findById(
            @CurrentUser UserPrincipal principal, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(workFromHomeService.getById(principal, id)));
    }

    @GetMapping("/pending-approvals")
    @PreAuthorize("hasAuthority('WFH_APPROVE')")
    @Operation(summary = "Requests waiting on you")
    public ResponseEntity<ApiResponse<PageResponse<WorkFromHomeResponse>>> pendingApprovals(
            @CurrentUser UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                workFromHomeService.getPendingApprovals(principal, pageable)));
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('WFH_APPROVE')")
    @Operation(summary = "Approve; the employee can then punch without the geofence on those days")
    public ResponseEntity<ApiResponse<WorkFromHomeResponse>> approve(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody(required = false) RequestDecisionRequest decision) {
        return ResponseEntity.ok(ApiResponse.success(
                workFromHomeService.approve(principal, id, decision),
                "Approved. The geofence will not apply on those days."));
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('WFH_APPROVE')")
    @Operation(summary = "Reject, stating why")
    public ResponseEntity<ApiResponse<WorkFromHomeResponse>> reject(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody RequestDecisionRequest decision) {
        return ResponseEntity.ok(ApiResponse.success(
                workFromHomeService.reject(principal, id, decision), "Request rejected."));
    }

    @PutMapping("/{id}/withdraw")
    @PreAuthorize("hasAuthority('WFH_APPLY')")
    @Operation(summary = "Withdraw your own request before it is decided")
    public ResponseEntity<ApiResponse<WorkFromHomeResponse>> withdraw(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(ApiResponse.success(
                workFromHomeService.withdraw(principal, id, reason), "Request withdrawn."));
    }

    @PutMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('WFH_APPLY')")
    @Operation(summary = "Cancel an approved request that has not started")
    public ResponseEntity<ApiResponse<WorkFromHomeResponse>> cancel(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(ApiResponse.success(
                workFromHomeService.cancel(principal, id, reason), "Request cancelled."));
    }
}
