package com.company.attendance.controller;

import com.company.attendance.dto.request.request.RegularizationApplyRequest;
import com.company.attendance.dto.request.request.RequestDecisionRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.request.RegularizationResponse;
import com.company.attendance.security.CurrentUser;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.RegularizationService;
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

/**
 * Attendance corrections.
 *
 * <p>The only route by which an attendance record is rewritten, and it is
 * reviewed and audited on both sides — what the day was, and what it became.
 */
@RestController
@RequestMapping("/api/v1/regularizations")
@Tag(name = "Regularization", description = "Attendance correction requests")
public class RegularizationController {

    private final RegularizationService regularizationService;

    public RegularizationController(RegularizationService regularizationService) {
        this.regularizationService = regularizationService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('REGULARIZATION_APPLY')")
    @Operation(summary = "Request a correction to one day's attendance")
    public ResponseEntity<ApiResponse<RegularizationResponse>> apply(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody RegularizationApplyRequest request) {

        RegularizationResponse result = regularizationService.apply(principal, request);

        String message = result.currentApproverName() != null
                ? "Correction requested. " + result.currentApproverName() + " has been asked to review it."
                : "Correction requested.";

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result, message));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAuthority('REGULARIZATION_APPLY')")
    @Operation(summary = "Your correction requests")
    public ResponseEntity<ApiResponse<PageResponse<RegularizationResponse>>> myRequests(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "appliedAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                regularizationService.getMyRequests(principal, status, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One correction request, subject to your data scope")
    public ResponseEntity<ApiResponse<RegularizationResponse>> findById(
            @CurrentUser UserPrincipal principal, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(regularizationService.getById(principal, id)));
    }

    @GetMapping("/pending-approvals")
    @PreAuthorize("hasAnyAuthority('REGULARIZATION_APPROVE_L1', 'REGULARIZATION_APPROVE_L2')")
    @Operation(summary = "Corrections waiting on you")
    public ResponseEntity<ApiResponse<PageResponse<RegularizationResponse>>> pendingApprovals(
            @CurrentUser UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                regularizationService.getPendingApprovals(principal, pageable)));
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize("hasAnyAuthority('REGULARIZATION_APPROVE_L1', 'REGULARIZATION_APPROVE_L2')")
    @Operation(summary = "Approve a correction and rewrite the day")
    public ResponseEntity<ApiResponse<RegularizationResponse>> approve(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @RequestBody(required = false) RequestDecisionRequest decision) {
        return ResponseEntity.ok(ApiResponse.success(
                regularizationService.approve(principal, id, decision),
                "Correction approved and the day recomputed."));
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasAnyAuthority('REGULARIZATION_APPROVE_L1', 'REGULARIZATION_APPROVE_L2')")
    @Operation(summary = "Reject a correction, stating why")
    public ResponseEntity<ApiResponse<RegularizationResponse>> reject(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody RequestDecisionRequest decision) {
        return ResponseEntity.ok(ApiResponse.success(
                regularizationService.reject(principal, id, decision), "Correction rejected."));
    }

    @PutMapping("/{id}/withdraw")
    @PreAuthorize("hasAuthority('REGULARIZATION_APPLY')")
    @Operation(summary = "Withdraw your own request before it is decided")
    public ResponseEntity<ApiResponse<RegularizationResponse>> withdraw(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(ApiResponse.success(
                regularizationService.withdraw(principal, id, reason), "Request withdrawn."));
    }
}
