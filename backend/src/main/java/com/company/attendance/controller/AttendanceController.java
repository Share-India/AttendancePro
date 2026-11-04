package com.company.attendance.controller;

import com.company.attendance.dto.request.attendance.BreakRequest;
import com.company.attendance.dto.request.attendance.PunchRequest;
import com.company.attendance.dto.request.attendance.AttendanceOverrideRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.attendance.AttendanceCalendarResponse;
import com.company.attendance.dto.response.attendance.AttendanceResponse;
import com.company.attendance.dto.response.attendance.AttendanceStatusResponse;
import com.company.attendance.dto.response.attendance.AttendanceSummaryResponse;
import com.company.attendance.entity.Attendance;
import com.company.attendance.security.CurrentUser;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.AttendanceOverrideService;
import com.company.attendance.service.AttendanceService;
import com.company.attendance.service.BreakService;
import com.company.attendance.service.PunchService;
import com.company.attendance.util.DateTimeUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Attendance: punching, breaks and reads.
 *
 * <p>Every write takes its employee from the JWT. There is no endpoint that
 * lets one person punch as another, by design — a corrected record goes through
 * regularization, which is reviewed and audited.
 */
@RestController
@RequestMapping("/api/v1/attendance")
@Tag(name = "Attendance", description = "Punch in and out, breaks, history and calendar")
public class AttendanceController {

    private final PunchService punchService;
    private final BreakService breakService;
    private final AttendanceService attendanceService;
    private final AttendanceOverrideService attendanceOverrideService;

    public AttendanceController(PunchService punchService,
                                BreakService breakService,
                                AttendanceService attendanceService,
                                AttendanceOverrideService attendanceOverrideService) {
        this.punchService = punchService;
        this.breakService = breakService;
        this.attendanceService = attendanceService;
        this.attendanceOverrideService = attendanceOverrideService;
    }

    // -----------------------------------------------------------------
    // Punch
    // -----------------------------------------------------------------

    @PostMapping("/punch-in")
    @PreAuthorize("hasAuthority('ATTENDANCE_PUNCH')")
    @Operation(summary = "Punch in for today, validated against the geofence and shift window")
    public ResponseEntity<ApiResponse<Map<String, Object>>> punchIn(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody PunchRequest request) {

        Attendance attendance = punchService.punchIn(principal, request);

        // Spelled out rather than formatMinutes(): "25m" reads as metres in an
        // app where the previous sentence the employee saw was about distance.
        String message = attendance.getLateMinutes() > 0
                ? "Punched in. You are " + attendance.getLateMinutes()
                        + (attendance.getLateMinutes() == 1 ? " minute" : " minutes")
                        + " past your grace period."
                : "Punched in successfully";

        return ResponseEntity.ok(ApiResponse.success(summarise(attendance), message));
    }

    @PostMapping("/punch-out")
    @PreAuthorize("hasAuthority('ATTENDANCE_PUNCH')")
    @Operation(summary = "Punch out and settle the day's hours and status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> punchOut(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody PunchRequest request) {

        Attendance attendance = punchService.punchOut(principal, request);

        return ResponseEntity.ok(ApiResponse.success(summarise(attendance),
                "Punched out. You worked " + DateTimeUtils.formatMinutes(attendance.getWorkingMinutes()) + "."));
    }

    // -----------------------------------------------------------------
    // Breaks
    // -----------------------------------------------------------------

    @PostMapping("/break/start")
    @PreAuthorize("hasAuthority('ATTENDANCE_PUNCH')")
    @Operation(summary = "Start a break")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startBreak(
            @CurrentUser UserPrincipal principal,
            @RequestBody(required = false) BreakRequest request) {

        var attendanceBreak = breakService.startBreak(principal, request);

        return ResponseEntity.ok(ApiResponse.success(
                Map.of("breakId", attendanceBreak.getId(),
                        "breakType", attendanceBreak.getBreakType().getName(),
                        "startedAt", attendanceBreak.getBreakStartTime(),
                        "maxMinutes", attendanceBreak.getBreakType().getMaxMinutes()),
                attendanceBreak.getBreakType().getName() + " started"));
    }

    @PostMapping("/break/end")
    @PreAuthorize("hasAuthority('ATTENDANCE_PUNCH')")
    @Operation(summary = "End the open break")
    public ResponseEntity<ApiResponse<Map<String, Object>>> endBreak(@CurrentUser UserPrincipal principal) {
        var attendanceBreak = breakService.endBreak(principal);

        String message = attendanceBreak.isExceededLimit()
                ? "Break ended after " + DateTimeUtils.formatMinutes(attendanceBreak.getDurationMinutes())
                        + ", over the allowed limit."
                : "Break ended after " + DateTimeUtils.formatMinutes(attendanceBreak.getDurationMinutes()) + ".";

        return ResponseEntity.ok(ApiResponse.success(
                Map.of("breakId", attendanceBreak.getId(),
                        "durationMinutes", attendanceBreak.getDurationMinutes(),
                        "exceededLimit", attendanceBreak.isExceededLimit()),
                message));
    }

    @GetMapping("/break/types")
    @PreAuthorize("hasAuthority('ATTENDANCE_PUNCH')")
    @Operation(summary = "Break types available to choose from")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> breakTypes() {
        List<Map<String, Object>> types = breakService.findBreakTypes().stream()
                .map(type -> Map.<String, Object>of(
                        "code", type.getCode(),
                        "name", type.getName(),
                        "maxMinutes", type.getMaxMinutes(),
                        "paid", type.isPaid()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(types));
    }

    // -----------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------

    @GetMapping("/me/status")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW_SELF')")
    @Operation(summary = "Live state: worked minutes, break state and what you can do now")
    public ResponseEntity<ApiResponse<AttendanceStatusResponse>> status(@CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(attendanceService.getCurrentStatus(principal)));
    }

    @GetMapping("/me/today")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW_SELF')")
    @Operation(summary = "Today's attendance record")
    public ResponseEntity<ApiResponse<AttendanceResponse>> today(@CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(attendanceService.getToday(principal)));
    }

    @GetMapping("/me/history")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW_SELF')")
    @Operation(summary = "Your attendance history")
    public ResponseEntity<ApiResponse<PageResponse<AttendanceResponse>>> myHistory(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @PageableDefault(size = 31, sort = "attendanceDate", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return ResponseEntity.ok(ApiResponse.success(
                attendanceService.getHistory(principal, null, fromDate, toDate, pageable)));
    }

    @GetMapping("/me/calendar")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW_SELF')")
    @Operation(summary = "A month of days, including holidays and weekly offs")
    public ResponseEntity<ApiResponse<AttendanceCalendarResponse>> myCalendar(
            @CurrentUser UserPrincipal principal,
            @RequestParam int year,
            @RequestParam int month) {

        return ResponseEntity.ok(ApiResponse.success(
                attendanceService.getCalendar(principal, null, year, month)));
    }

    @GetMapping("/me/summary")
    @PreAuthorize("hasAuthority('ATTENDANCE_VIEW_SELF')")
    @Operation(summary = "Monthly aggregates for the dashboard cards")
    public ResponseEntity<ApiResponse<AttendanceSummaryResponse>> mySummary(
            @CurrentUser UserPrincipal principal,
            @RequestParam int year,
            @RequestParam int month) {

        return ResponseEntity.ok(ApiResponse.success(
                attendanceService.getSummary(principal, null, year, month)));
    }

    @GetMapping("/{employeeId}/history")
    @PreAuthorize("hasAnyAuthority('ATTENDANCE_VIEW_TEAM', 'ATTENDANCE_VIEW_ALL')")
    @Operation(summary = "Another employee's history, subject to your data scope")
    public ResponseEntity<ApiResponse<PageResponse<AttendanceResponse>>> employeeHistory(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @PageableDefault(size = 31, sort = "attendanceDate", direction = Sort.Direction.DESC)
            Pageable pageable) {

        return ResponseEntity.ok(ApiResponse.success(
                attendanceService.getHistory(principal, employeeId, fromDate, toDate, pageable)));
    }

    @GetMapping("/{employeeId}/calendar")
    @PreAuthorize("hasAnyAuthority('ATTENDANCE_VIEW_TEAM', 'ATTENDANCE_VIEW_ALL')")
    @Operation(summary = "Another employee's calendar, subject to your data scope")
    public ResponseEntity<ApiResponse<AttendanceCalendarResponse>> employeeCalendar(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long employeeId,
            @RequestParam int year,
            @RequestParam int month) {

        return ResponseEntity.ok(ApiResponse.success(
                attendanceService.getCalendar(principal, employeeId, year, month)));
    }

    /**
     * Sets a day's punch times directly, with no request and no approval.
     *
     * <p>SUPER_ADMIN only — see V20 for why ADMIN and HR are excluded. The
     * ordinary route is a regularization, which keeps a second pair of eyes on
     * anything that changes somebody's pay; this is the escape hatch for the
     * days that flow cannot reach, and every use is audited with its reason.
     *
     * <p>Addressed by employee and date rather than by attendance id, because
     * one of the cases it exists for is a day with no attendance row at all.
     */
    @PutMapping("/{employeeId}/{date}/override")
    @PreAuthorize("hasAuthority('ATTENDANCE_OVERRIDE')")
    @Operation(summary = "Set punch times directly (super admin only, always audited)")
    public ResponseEntity<ApiResponse<Map<String, Object>>> override(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long employeeId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody AttendanceOverrideRequest request) {

        Attendance updated = attendanceOverrideService.override(principal, employeeId, date, request);
        return ResponseEntity.ok(ApiResponse.success(summarise(updated),
                "Times updated for " + date + ". The change has been recorded in the audit trail."));
    }

    // -----------------------------------------------------------------

    /** The slice of a punch result the client actually needs back. */
    private Map<String, Object> summarise(Attendance attendance) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("attendanceId", attendance.getId());
        payload.put("attendanceDate", attendance.getAttendanceDate());
        payload.put("status", attendance.getAttendanceStatus().name());
        payload.put("punchInTime", attendance.getPunchInTime());
        payload.put("punchOutTime", attendance.getPunchOutTime());
        payload.put("workingMinutes", attendance.getWorkingMinutes());
        payload.put("breakMinutes", attendance.getBreakMinutes());
        payload.put("lateMinutes", attendance.getLateMinutes());
        payload.put("earlyExitMinutes", attendance.getEarlyExitMinutes());
        payload.put("overtimeMinutes", attendance.getOvertimeMinutes());
        payload.put("workMode", attendance.getWorkMode().name());
        payload.put("punchInLocation", attendance.getPunchInLocation());
        payload.put("punchOutLocation", attendance.getPunchOutLocation());
        payload.put("suspicious", attendance.isSuspicious());
        return payload;
    }
}
