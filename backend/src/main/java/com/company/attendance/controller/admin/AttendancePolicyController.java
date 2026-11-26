package com.company.attendance.controller.admin;

import com.company.attendance.dto.request.master.AttendancePolicyRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.master.AttendancePolicyResponse;
import com.company.attendance.service.AttendancePolicyAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The attendance rules, as an administrator sets them.
 *
 * <p>These numbers decide what counts as a day worked, how late is late, and
 * how many corrections somebody may raise. They were configurable in the
 * database from the beginning and changeable nowhere, which meant every tweak
 * was a hand-written UPDATE against production.
 */
@RestController
@RequestMapping("/api/v1/admin/attendance-policies")
@Tag(name = "Attendance policies", description = "The rules a working day is measured against")
public class AttendancePolicyController {

    private final AttendancePolicyAdminService service;

    public AttendancePolicyController(AttendancePolicyAdminService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Operation(summary = "Every attendance policy")
    public ResponseEntity<ApiResponse<List<AttendancePolicyResponse>>> findAll() {
        return ResponseEntity.ok(ApiResponse.success(service.findAll()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('POLICY_MANAGE')")
    @Operation(summary = "Change an attendance policy")
    public ResponseEntity<ApiResponse<AttendancePolicyResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody AttendancePolicyRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                service.update(id, request),
                "Saved. The new rules apply to days settled from now on."));
    }
}
