package com.company.attendance.controller;

import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.employee.BulkImportResultResponse;
import com.company.attendance.service.EmployeeImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/employees/bulk-import")
@Tag(name = "Employee import", description = "Bulk employee onboarding from CSV")
public class EmployeeImportController {

    private final EmployeeImportService employeeImportService;

    public EmployeeImportController(EmployeeImportService employeeImportService) {
        this.employeeImportService = employeeImportService;
    }

    @GetMapping("/template")
    @PreAuthorize("hasAuthority('EMPLOYEE_BULK_IMPORT')")
    @Operation(summary = "Download the CSV template with required and optional columns")
    public ResponseEntity<String> template() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"employee-import-template.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(employeeImportService.csvTemplate());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('EMPLOYEE_BULK_IMPORT')")
    @Operation(summary = "Import employees from a CSV file")
    public ResponseEntity<ApiResponse<BulkImportResultResponse>> importCsv(
            @RequestParam("file") MultipartFile file) {

        BulkImportResultResponse result = employeeImportService.importCsv(file);

        // Partial success is the normal outcome, so the message states both
        // counts rather than claiming the whole import succeeded.
        String message = result.failed() == 0
                ? "Imported " + result.imported() + " employee(s)"
                : "Imported " + result.imported() + " of " + result.totalRows()
                        + " row(s); " + result.failed() + " failed";

        return ResponseEntity.ok(ApiResponse.success(result, message));
    }
}
