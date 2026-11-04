package com.company.attendance.controller;

import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.dashboard.AttendanceTrendResponse;
import com.company.attendance.dto.response.dashboard.DashboardSummaryResponse;
import com.company.attendance.dto.response.dashboard.TeamMemberDayResponse;
import com.company.attendance.security.CurrentUser;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.DashboardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The team and organisation views.
 *
 * <p>One set of endpoints serves both a manager and an HR user. The permission
 * decides how wide the answer is, so there is no separate "HR dashboard" URL
 * that a manager could reach by guessing.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Team and organisation roll-ups")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyAuthority('ATTENDANCE_VIEW_TEAM', 'ATTENDANCE_VIEW_ALL')")
    @Operation(summary = "One day rolled up for everyone you can see")
    public ResponseEntity<ApiResponse<DashboardSummaryResponse>> summary(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long officeId) {

        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getSummary(principal, date, departmentId, officeId)));
    }

    @GetMapping("/roster")
    @PreAuthorize("hasAnyAuthority('ATTENDANCE_VIEW_TEAM', 'ATTENDANCE_VIEW_ALL')")
    @Operation(summary = "The same day, person by person")
    public ResponseEntity<ApiResponse<PageResponse<TeamMemberDayResponse>>> roster(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String dayState,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long officeId,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {

        return ResponseEntity.ok(ApiResponse.success(dashboardService.getRoster(
                principal, date, dayState, departmentId, officeId, search, pageable)));
    }

    @GetMapping("/trend")
    @PreAuthorize("hasAnyAuthority('ATTENDANCE_VIEW_TEAM', 'ATTENDANCE_VIEW_ALL')")
    @Operation(summary = "Daily attendance across a range, for the chart")
    public ResponseEntity<ApiResponse<AttendanceTrendResponse>> trend(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {

        return ResponseEntity.ok(ApiResponse.success(
                dashboardService.getTrend(principal, fromDate, toDate)));
    }
}
