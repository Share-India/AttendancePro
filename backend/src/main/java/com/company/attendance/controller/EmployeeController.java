package com.company.attendance.controller;

import com.company.attendance.dto.filter.EmployeeFilter;
import com.company.attendance.dto.request.employee.EmployeeCreateRequest;
import com.company.attendance.dto.request.employee.EmployeeRoleAssignmentRequest;
import com.company.attendance.dto.request.employee.EmployeeSelfUpdateRequest;
import com.company.attendance.dto.request.employee.EmployeeUpdateRequest;
import com.company.attendance.dto.request.master.ManagerAssignmentRequest;
import com.company.attendance.dto.request.master.ShiftAssignmentRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.employee.EmployeeDetailResponse;
import com.company.attendance.dto.response.employee.EmployeeSummaryResponse;
import com.company.attendance.dto.response.master.ShiftAssignmentResponse;
import com.company.attendance.security.CurrentUser;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.EmployeeService;
import com.company.attendance.service.ShiftService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Employee directory and lifecycle.
 *
 * <p>Two access checks run on every read: the annotation decides whether the
 * caller may use the endpoint, and the service narrows the result to the rows
 * their scope allows. A path variable alone never grants access.
 */
@RestController
@RequestMapping("/api/v1/employees")
@Tag(name = "Employees", description = "Employee directory, lifecycle and assignments")
public class EmployeeController {

    private final EmployeeService employeeService;
    private final ShiftService shiftService;

    public EmployeeController(EmployeeService employeeService, ShiftService shiftService) {
        this.employeeService = employeeService;
        this.shiftService = shiftService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('EMPLOYEE_VIEW_ALL', 'EMPLOYEE_VIEW_TEAM')")
    @Operation(summary = "Search employees, narrowed to the caller's data scope")
    public ResponseEntity<ApiResponse<PageResponse<EmployeeSummaryResponse>>> search(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long designationId,
            @RequestParam(required = false) Long officeId,
            @RequestParam(required = false) String employmentType,
            @RequestParam(required = false) String employmentStatus,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20, sort = "employeeCode", direction = Sort.Direction.ASC) Pageable pageable) {

        EmployeeFilter filter = new EmployeeFilter(
                search, departmentId, designationId, officeId, employmentType, employmentStatus, active);

        return ResponseEntity.ok(ApiResponse.success(
                employeeService.search(principal, filter, pageable)));
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('EMPLOYEE_VIEW_SELF')")
    @Operation(summary = "The signed-in employee's own record")
    public ResponseEntity<ApiResponse<EmployeeDetailResponse>> me(@CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(employeeService.findSelf(principal)));
    }

    @PutMapping("/me")
    @PreAuthorize("hasAuthority('EMPLOYEE_VIEW_SELF')")
    @Operation(summary = "Update your own contact details")
    public ResponseEntity<ApiResponse<EmployeeDetailResponse>> updateMe(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody EmployeeSelfUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                employeeService.updateSelf(principal, request), "Profile updated"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('EMPLOYEE_VIEW_ALL', 'EMPLOYEE_VIEW_TEAM', 'EMPLOYEE_VIEW_SELF')")
    @Operation(summary = "One employee, subject to the caller's data scope")
    public ResponseEntity<ApiResponse<EmployeeDetailResponse>> findById(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(employeeService.findById(principal, id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('EMPLOYEE_CREATE')")
    @Operation(summary = "Create an employee and provision their login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @Valid @RequestBody EmployeeCreateRequest request) {

        var result = employeeService.create(request);

        // The temporary password is returned exactly once, here. It is never
        // stored in readable form and never logged.
        Map<String, Object> payload = Map.of(
                "employee", result.employee(),
                "temporaryPassword", result.temporaryPassword(),
                "note", "Share this password securely. The employee must change it at first sign-in.");

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(payload, "Employee created"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('EMPLOYEE_UPDATE')")
    @Operation(summary = "Update an employee record")
    public ResponseEntity<ApiResponse<EmployeeDetailResponse>> update(
            @PathVariable Long id,
            @Valid @RequestBody EmployeeUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                employeeService.update(id, request), "Employee updated"));
    }

    @PatchMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('EMPLOYEE_DEACTIVATE')")
    @Operation(summary = "Deactivate an employee and revoke their sessions")
    public ResponseEntity<ApiResponse<Void>> deactivate(
            @PathVariable Long id,
            @RequestParam(required = false) String reason) {
        employeeService.deactivate(id, reason);
        return ResponseEntity.ok(ApiResponse.success("Employee deactivated"));
    }

    @PatchMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('EMPLOYEE_DEACTIVATE')")
    @Operation(summary = "Reactivate a previously deactivated employee")
    public ResponseEntity<ApiResponse<Void>> reactivate(@PathVariable Long id) {
        employeeService.reactivate(id);
        return ResponseEntity.ok(ApiResponse.success("Employee reactivated"));
    }

    @PutMapping("/{id}/manager")
    @PreAuthorize("hasAuthority('EMPLOYEE_UPDATE')")
    @Operation(summary = "Set an employee's reporting manager")
    public ResponseEntity<ApiResponse<EmployeeDetailResponse>> assignManager(
            @PathVariable Long id,
            @Valid @RequestBody ManagerAssignmentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                employeeService.assignManager(id, request), "Reporting manager updated"));
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('USER_ROLE_ASSIGN')")
    @Operation(summary = "Replace an employee's roles and revoke their sessions")
    public ResponseEntity<ApiResponse<EmployeeDetailResponse>> assignRoles(
            @PathVariable Long id,
            @Valid @RequestBody EmployeeRoleAssignmentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                employeeService.assignRoles(id, request),
                "Roles updated. The employee must sign in again for them to take effect."));
    }

    @PutMapping("/{id}/shift")
    @PreAuthorize("hasAuthority('SHIFT_ASSIGN')")
    @Operation(summary = "Assign a shift from an effective date")
    public ResponseEntity<ApiResponse<ShiftAssignmentResponse>> assignShift(
            @PathVariable Long id,
            @Valid @RequestBody ShiftAssignmentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                shiftService.assign(id, request), "Shift assigned"));
    }

    @GetMapping("/{id}/shifts")
    @PreAuthorize("hasAnyAuthority('EMPLOYEE_VIEW_ALL', 'EMPLOYEE_VIEW_TEAM', 'EMPLOYEE_VIEW_SELF')")
    @Operation(summary = "Shift assignment history")
    public ResponseEntity<ApiResponse<List<ShiftAssignmentResponse>>> shiftHistory(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id) {
        // Reuse the detail read for its scope check before exposing history.
        employeeService.findById(principal, id);
        return ResponseEntity.ok(ApiResponse.success(shiftService.findAssignmentHistory(id)));
    }
}
