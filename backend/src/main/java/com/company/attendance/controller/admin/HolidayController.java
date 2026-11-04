package com.company.attendance.controller.admin;

import com.company.attendance.dto.request.master.HolidayRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.master.HolidayResponse;
import com.company.attendance.service.HolidayService;
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
@RequestMapping("/api/v1/admin/holidays")
@Tag(name = "Holidays", description = "The company holiday calendar")
public class HolidayController {

    private final HolidayService holidayService;

    public HolidayController(HolidayService holidayService) {
        this.holidayService = holidayService;
    }

    /**
     * Readable by anyone signed in. These are the days the office is shut;
     * keeping them from the people who work there would serve nobody, and
     * the employee calendar already shows them.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List holidays")
    public ResponseEntity<ApiResponse<List<HolidayResponse>>> findAll(
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(ApiResponse.success(holidayService.findAll(year, activeOnly)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One holiday")
    public ResponseEntity<ApiResponse<HolidayResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(holidayService.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('HOLIDAY_MANAGE')")
    @Operation(summary = "Declare a holiday")
    public ResponseEntity<ApiResponse<HolidayResponse>> create(@Valid @RequestBody HolidayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(holidayService.create(request), "Holiday declared"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('HOLIDAY_MANAGE')")
    @Operation(summary = "Amend a holiday")
    public ResponseEntity<ApiResponse<HolidayResponse>> update(
            @PathVariable Long id, @Valid @RequestBody HolidayRequest request) {
        return ResponseEntity.ok(ApiResponse.success(holidayService.update(id, request), "Holiday updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('HOLIDAY_MANAGE')")
    @Operation(summary = "Withdraw a holiday")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long id) {
        holidayService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.success("Holiday withdrawn"));
    }
}
