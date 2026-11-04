package com.company.attendance.controller.admin;

import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.security.CurrentUser;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.dto.response.audit.AuditLogResponse;
import com.company.attendance.service.AuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * The audit trail.
 *
 * <p>Read-only. There is no endpoint here to edit or delete an entry, and there
 * should never be one — a log the application can rewrite proves nothing.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@Tag(name = "Audit", description = "Who changed what, and when")
public class AuditLogController {

    private final AuditQueryService auditQueryService;

    public AuditLogController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    @Operation(summary = "Search the audit trail")
    public ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> search(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return ResponseEntity.ok(ApiResponse.success(auditQueryService.search(
                principal, entityType, entityId, actorUserId, action, fromDate, toDate, search, pageable)));
    }

    @GetMapping("/entity-types")
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    @Operation(summary = "Entity types present in the log, for the filter")
    public ResponseEntity<ApiResponse<List<String>>> entityTypes() {
        return ResponseEntity.ok(ApiResponse.success(auditQueryService.knownEntityTypes()));
    }
}
