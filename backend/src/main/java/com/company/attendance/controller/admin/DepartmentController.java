package com.company.attendance.controller.admin;

import com.company.attendance.dto.request.master.DepartmentRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.master.DepartmentResponse;
import com.company.attendance.service.DepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/departments")
@Tag(name = "Departments", description = "Department master data")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    /** Readable by anyone signed in: pickers across the app need this list. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List departments")
    public ResponseEntity<ApiResponse<List<DepartmentResponse>>> findAll(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(ApiResponse.success(departmentService.findAll(activeOnly)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One department")
    public ResponseEntity<ApiResponse<DepartmentResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(departmentService.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('DEPARTMENT_MANAGE')")
    @Operation(summary = "Create a department")
    public ResponseEntity<ApiResponse<DepartmentResponse>> create(
            @Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(departmentService.create(request), "Department created"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('DEPARTMENT_MANAGE')")
    @Operation(summary = "Update a department")
    public ResponseEntity<ApiResponse<DepartmentResponse>> update(
            @PathVariable Long id, @Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                departmentService.update(id, request), "Department updated"));
    }

    /** Deactivates rather than deletes — history keeps referencing this row. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DEPARTMENT_MANAGE')")
    @Operation(summary = "Deactivate a department")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long id) {
        departmentService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.success("Department deactivated"));
    }
}
