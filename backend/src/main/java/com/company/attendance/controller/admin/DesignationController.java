package com.company.attendance.controller.admin;

import com.company.attendance.dto.request.master.DesignationRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.master.DesignationResponse;
import com.company.attendance.service.DesignationService;
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
@RequestMapping("/api/v1/admin/designations")
@Tag(name = "Designations", description = "Designation master data")
public class DesignationController {

    private final DesignationService designationService;

    public DesignationController(DesignationService designationService) {
        this.designationService = designationService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List designations")
    public ResponseEntity<ApiResponse<List<DesignationResponse>>> findAll(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(ApiResponse.success(designationService.findAll(activeOnly)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One designation")
    public ResponseEntity<ApiResponse<DesignationResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(designationService.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('DESIGNATION_MANAGE')")
    @Operation(summary = "Create a designation")
    public ResponseEntity<ApiResponse<DesignationResponse>> create(
            @Valid @RequestBody DesignationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(designationService.create(request), "Designation created"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('DESIGNATION_MANAGE')")
    @Operation(summary = "Update a designation")
    public ResponseEntity<ApiResponse<DesignationResponse>> update(
            @PathVariable Long id, @Valid @RequestBody DesignationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                designationService.update(id, request), "Designation updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('DESIGNATION_MANAGE')")
    @Operation(summary = "Deactivate a designation")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long id) {
        designationService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.success("Designation deactivated"));
    }
}
