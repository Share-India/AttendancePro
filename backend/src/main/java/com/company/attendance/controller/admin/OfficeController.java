package com.company.attendance.controller.admin;

import com.company.attendance.dto.request.master.OfficeLocationRequest;
import com.company.attendance.dto.request.master.OfficeRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.master.OfficeLocationResponse;
import com.company.attendance.dto.response.master.OfficeResponse;
import com.company.attendance.service.OfficeService;
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

/**
 * Offices and their geofence circles.
 *
 * <p>Geofence writes need GEOFENCE_MANAGE, which is an ADMIN permission — HR
 * can manage staff but cannot move the boundary that decides whether a punch
 * is accepted.
 */
@RestController
@RequestMapping("/api/v1/admin/offices")
@Tag(name = "Offices", description = "Offices and geofence configuration")
public class OfficeController {

    private final OfficeService officeService;

    public OfficeController(OfficeService officeService) {
        this.officeService = officeService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List offices with their geofence circles")
    public ResponseEntity<ApiResponse<List<OfficeResponse>>> findAll(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(ApiResponse.success(officeService.findAll(activeOnly)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One office")
    public ResponseEntity<ApiResponse<OfficeResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(officeService.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('OFFICE_MANAGE')")
    @Operation(summary = "Create an office")
    public ResponseEntity<ApiResponse<OfficeResponse>> create(@Valid @RequestBody OfficeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(officeService.create(request), "Office created"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('OFFICE_MANAGE')")
    @Operation(summary = "Update an office")
    public ResponseEntity<ApiResponse<OfficeResponse>> update(
            @PathVariable Long id, @Valid @RequestBody OfficeRequest request) {
        return ResponseEntity.ok(ApiResponse.success(officeService.update(id, request), "Office updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('OFFICE_MANAGE')")
    @Operation(summary = "Deactivate an office")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long id) {
        officeService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.success("Office deactivated"));
    }

    // --- Geofence circles ---

    @GetMapping("/{id}/locations")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Geofence circles for an office")
    public ResponseEntity<ApiResponse<List<OfficeLocationResponse>>> findLocations(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(officeService.findLocations(id)));
    }

    @PostMapping("/{id}/locations")
    @PreAuthorize("hasAuthority('GEOFENCE_MANAGE')")
    @Operation(summary = "Add a geofence circle")
    public ResponseEntity<ApiResponse<OfficeLocationResponse>> addLocation(
            @PathVariable Long id, @Valid @RequestBody OfficeLocationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(officeService.addLocation(id, request), "Geofence added"));
    }

    @PutMapping("/{id}/locations/{locationId}")
    @PreAuthorize("hasAuthority('GEOFENCE_MANAGE')")
    @Operation(summary = "Update a geofence circle")
    public ResponseEntity<ApiResponse<OfficeLocationResponse>> updateLocation(
            @PathVariable Long id,
            @PathVariable Long locationId,
            @Valid @RequestBody OfficeLocationRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                officeService.updateLocation(id, locationId, request), "Geofence updated"));
    }

    @DeleteMapping("/{id}/locations/{locationId}")
    @PreAuthorize("hasAuthority('GEOFENCE_MANAGE')")
    @Operation(summary = "Deactivate a geofence circle")
    public ResponseEntity<ApiResponse<Void>> deactivateLocation(
            @PathVariable Long id, @PathVariable Long locationId) {
        officeService.deactivateLocation(id, locationId);
        return ResponseEntity.ok(ApiResponse.success("Geofence deactivated"));
    }
}
