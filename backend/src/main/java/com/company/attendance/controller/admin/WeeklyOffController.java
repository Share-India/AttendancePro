package com.company.attendance.controller.admin;

import com.company.attendance.dto.request.master.WeeklyOffRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.master.WeeklyOffResponse;
import com.company.attendance.service.WeeklyOffService;
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
@RequestMapping("/api/v1/admin/weekly-offs")
@Tag(name = "Weekly offs", description = "Recurring non-working days, including alternate-week rosters")
public class WeeklyOffController {

    private final WeeklyOffService weeklyOffService;

    public WeeklyOffController(WeeklyOffService weeklyOffService) {
        this.weeklyOffService = weeklyOffService;
    }

    /** Readable by anyone signed in: these are the days their own office is shut. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List weekly-off rules")
    public ResponseEntity<ApiResponse<List<WeeklyOffResponse>>> findAll(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return ResponseEntity.ok(ApiResponse.success(weeklyOffService.findAll(activeOnly)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One rule")
    public ResponseEntity<ApiResponse<WeeklyOffResponse>> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(weeklyOffService.findById(id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('WEEKLY_OFF_MANAGE')")
    @Operation(summary = "Add a weekly-off rule")
    public ResponseEntity<ApiResponse<WeeklyOffResponse>> create(@Valid @RequestBody WeeklyOffRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(weeklyOffService.create(request), "Weekly off added"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('WEEKLY_OFF_MANAGE')")
    @Operation(summary = "Amend a rule")
    public ResponseEntity<ApiResponse<WeeklyOffResponse>> update(
            @PathVariable Long id, @Valid @RequestBody WeeklyOffRequest request) {
        return ResponseEntity.ok(ApiResponse.success(weeklyOffService.update(id, request), "Weekly off updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('WEEKLY_OFF_MANAGE')")
    @Operation(summary = "Remove a rule")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long id) {
        weeklyOffService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.success("Weekly off removed"));
    }
}
