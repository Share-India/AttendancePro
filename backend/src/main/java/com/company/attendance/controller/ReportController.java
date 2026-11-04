package com.company.attendance.controller;

import com.company.attendance.dto.request.report.ReportFilter;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.report.ReportResult;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.ReportType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.security.CurrentUser;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.ReportExporter;
import com.company.attendance.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/**
 * The report suite.
 *
 * <p>The same path serves the on-screen table and the download; only the
 * {@code format} parameter differs. That is deliberate — the two can never drift
 * apart and show different numbers, which is the classic way a report loses a
 * reader's trust.
 */
@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Reports", description = "Attendance, leave and working-hours reports")
public class ReportController {

    private final ReportService reportService;
    private final ReportExporter reportExporter;

    public ReportController(ReportService reportService, ReportExporter reportExporter) {
        this.reportService = reportService;
        this.reportExporter = reportExporter;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('REPORT_VIEW_TEAM', 'REPORT_VIEW_ALL')")
    @Operation(summary = "The reports you can run")
    public ResponseEntity<ApiResponse<List<ReportSummary>>> catalogue() {
        List<ReportSummary> types = java.util.Arrays.stream(ReportType.values())
                .map(type -> new ReportSummary(
                        type.name(), type.getTitle(), type.getDescription(), type.requiresEmployee()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(types));
    }

    @GetMapping("/{type}")
    @PreAuthorize("hasAnyAuthority('REPORT_VIEW_TEAM', 'REPORT_VIEW_ALL')")
    @Operation(summary = "Run a report, paged for the screen")
    public ResponseEntity<ApiResponse<ReportResult>> run(
            @CurrentUser UserPrincipal principal,
            @PathVariable String type,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) List<Long> employeeIds,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long officeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 50) Pageable pageable) {

        ReportFilter filter = new ReportFilter(
                fromDate, toDate, employeeIds, departmentId, officeId, status, search);

        return ResponseEntity.ok(ApiResponse.success(
                reportService.generate(principal, parseType(type), filter, pageable)));
    }

    /**
     * Downloads the whole report.
     *
     * <p>Streamed rather than assembled: the rows are written to the response
     * as they are produced, so a large export does not sit in heap waiting to
     * be sent. It is also never paged — an export that silently contained only
     * the first fifty rows would be the most damaging thing here.
     */
    @GetMapping("/{type}/export")
    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    @Operation(summary = "Download a report as CSV, Excel or PDF")
    public ResponseEntity<StreamingResponseBody> export(
            @CurrentUser UserPrincipal principal,
            @PathVariable String type,
            @RequestParam(defaultValue = "CSV") String format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) List<Long> employeeIds,
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) Long officeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String timezone) {

        ReportExporter.Format exportFormat = ReportExporter.Format.parse(format);
        ReportFilter filter = new ReportFilter(
                fromDate, toDate, employeeIds, departmentId, officeId, status, null);

        // Generated before the response starts, so a validation failure still
        // becomes a clean JSON error rather than a corrupt half-written file.
        ReportResult report = reportService.generateAll(principal, parseType(type), filter);

        ZoneId zone = resolveZone(timezone);
        String fileName = reportExporter.fileName(report, exportFormat);

        StreamingResponseBody body = out -> reportExporter.write(report, exportFormat, zone, out);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(exportFormat.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(body);
    }

    // -----------------------------------------------------------------

    private ReportType parseType(String value) {
        try {
            return ReportType.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                    "There is no report called '" + value + "'.");
        }
    }

    /**
     * The timezone times are rendered in.
     *
     * <p>Taken from the caller so an export reads in the reader's own day, and
     * falls back rather than failing: a bad timezone string is not a reason to
     * refuse someone their report.
     */
    private ZoneId resolveZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return com.company.attendance.util.DateTimeUtils.FALLBACK_ZONE;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (Exception e) {
            return com.company.attendance.util.DateTimeUtils.FALLBACK_ZONE;
        }
    }

    /** One entry in the report picker. */
    public record ReportSummary(String type, String title, String description, boolean requiresEmployee) {
    }
}
