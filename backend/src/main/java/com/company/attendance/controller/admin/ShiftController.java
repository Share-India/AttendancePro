package com.company.attendance.controller.admin;

import com.company.attendance.dto.request.master.ShiftRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.master.ShiftResponse;
import com.company.attendance.service.ShiftService;
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
@RequestMapping("/api/v1/admin/shifts")
@Tag(name = "Shifts", description = "Shift definitions and hour thresholds")
public class ShiftController {

    private final ShiftService shiftService;

    public ShiftController(ShiftService shiftService) {
        this.shiftService = shiftService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List shifts")
    public ResponseEntity<ApiResponse<List<ShiftResponse>>> findAll(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(ApiResponse.success(shiftService.findAll(activeOnly)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One shift")
    public ResponseEntity<ApiResponse<ShiftResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(shiftService.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SHIFT_MANAGE')")
    @Operation(summary = "Create a shift")
    public ResponseEntity<ApiResponse<ShiftResponse>> create(@Valid @RequestBody ShiftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(shiftService.create(request), "Shift created"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SHIFT_MANAGE')")
    @Operation(summary = "Update a shift and its thresholds")
    public ResponseEntity<ApiResponse<ShiftResponse>> update(
            @PathVariable Long id, @Valid @RequestBody ShiftRequest request) {
        return ResponseEntity.ok(ApiResponse.success(shiftService.update(id, request), "Shift updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SHIFT_MANAGE')")
    @Operation(summary = "Deactivate a shift")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long id) {
        shiftService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.success("Shift deactivated"));
    }
}
