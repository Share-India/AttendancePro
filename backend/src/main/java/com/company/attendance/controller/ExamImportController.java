package com.company.attendance.controller;

import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.exam.ExamImportResultResponse;
import com.company.attendance.security.CurrentUser;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.ExamImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Loading a question workbook into an exam. */
@RestController
@RequestMapping("/api/v1/exams")
@Tag(name = "Exam questions", description = "Uploading a question workbook")
public class ExamImportController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument."
            + "spreadsheetml.sheet";

    private final ExamImportService importService;

    public ExamImportController(ExamImportService importService) {
        this.importService = importService;
    }

    /**
     * The blank workbook.
     *
     * <p>Generated from the same column list the parser reads, so the template
     * cannot drift away from what is actually accepted.
     */
    @GetMapping("/import/template")
    @PreAuthorize("hasAuthority('EXAM_QUESTION_IMPORT')")
    @Operation(summary = "Download the blank question workbook")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"exam-question-template.xlsx\"")
                .contentType(MediaType.parseMediaType(XLSX))
                .body(importService.template());
    }

    /**
     * Uploads a workbook.
     *
     * <p>{@code commit=false} is a dry run: the file is parsed and checked from
     * end to end and nothing is written, so an upload expected to be wrong can
     * be looked at without leaving a half-loaded question bank behind. A commit
     * that finds any problem also writes nothing, so the dry run is a
     * convenience rather than a step everybody pays on every upload.
     */
    @PostMapping(value = "/{id}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('EXAM_QUESTION_IMPORT')")
    @Operation(summary = "Upload a question workbook, with or without committing it")
    public ResponseEntity<ApiResponse<ExamImportResultResponse>> importWorkbook(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean commit) {

        ExamImportResultResponse result =
                importService.importWorkbook(principal, id, file, commit);
        return ResponseEntity.ok(ApiResponse.success(result, result.message()));
    }
}
