package com.company.attendance.controller;

import com.company.attendance.dto.request.exam.ExamCancelRequest;
import com.company.attendance.dto.request.exam.ExamRequest;
import com.company.attendance.dto.request.exam.RetakeRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.exam.ExamAnalyticsResponse;
import com.company.attendance.dto.response.exam.ExamParticipantResponse;
import com.company.attendance.dto.response.exam.ExamPublishResponse;
import com.company.attendance.dto.response.exam.ExamQuestionReviewResponse;
import com.company.attendance.dto.response.exam.ExamResponse;
import com.company.attendance.dto.response.exam.ExamRetakeResponse;
import com.company.attendance.dto.response.exam.ExamSummaryResponse;
import com.company.attendance.security.CurrentUser;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.ExamAnalyticsService;
import com.company.attendance.service.ExamRetakeService;
import com.company.attendance.service.ExamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
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

import java.util.List;

/**
 * Setting and reviewing exams.
 *
 * <p>Sitting one is {@link ExamAttemptController}, under {@code /exams/me},
 * kept apart because the two have opposite rules about the answer key: one may
 * never see it and the other exists to proof-read it.
 */
@RestController
@RequestMapping("/api/v1/exams")
@Tag(name = "Exams", description = "Weekly study exams: setting them and reading the results")
public class ExamController {

    private final ExamService examService;
    private final ExamAnalyticsService analyticsService;
    private final ExamRetakeService retakeService;

    public ExamController(ExamService examService,
                          ExamAnalyticsService analyticsService,
                          ExamRetakeService retakeService) {
        this.examService = examService;
        this.analyticsService = analyticsService;
        this.retakeService = retakeService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('EXAM_MANAGE', 'EXAM_RESULT_VIEW_TEAM', 'EXAM_RESULT_VIEW_ALL')")
    @Operation(summary = "The exam list")
    public ResponseEntity<ApiResponse<PageResponse<ExamSummaryResponse>>> search(
            @CurrentUser UserPrincipal principal,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "windowStart",
                    direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(
                examService.search(principal, status, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('EXAM_MANAGE', 'EXAM_RESULT_VIEW_TEAM', 'EXAM_RESULT_VIEW_ALL')")
    @Operation(summary = "One exam, with everything blocking its publish")
    public ResponseEntity<ApiResponse<ExamResponse>> get(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(examService.get(principal, id)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('EXAM_MANAGE')")
    @Operation(summary = "Create an exam")
    public ResponseEntity<ApiResponse<ExamResponse>> create(
            @CurrentUser UserPrincipal principal,
            @Valid @RequestBody ExamRequest request) {
        ExamResponse created = examService.create(principal, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(created, "Exam created"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('EXAM_MANAGE')")
    @Operation(summary = "Edit an exam that has not been published")
    public ResponseEntity<ApiResponse<ExamResponse>> update(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody ExamRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                examService.update(principal, id, request), "Exam updated"));
    }

    @PutMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('EXAM_MANAGE')")
    @Operation(summary = "Fix the roster, deal the papers and tell everybody")
    public ResponseEntity<ApiResponse<ExamPublishResponse>> publish(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id) {
        ExamPublishResponse published = examService.publish(principal, id);
        return ResponseEntity.ok(ApiResponse.success(published, published.message()));
    }

    @PutMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('EXAM_MANAGE')")
    @Operation(summary = "Call an exam off, with a reason everybody sees")
    public ResponseEntity<ApiResponse<ExamResponse>> cancel(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody ExamCancelRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                examService.cancel(principal, id, request), "Exam cancelled"));
    }

    @GetMapping("/{id}/participants")
    @PreAuthorize("hasAnyAuthority('EXAM_RESULT_VIEW_TEAM', 'EXAM_RESULT_VIEW_ALL')")
    @Operation(summary = "The roster with results, scoped to who you may see")
    public ResponseEntity<ApiResponse<List<ExamParticipantResponse>>> participants(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(examService.participants(principal, id)));
    }

    /**
     * The question bank with its keys.
     *
     * <p>Gated on the import permission rather than on EXAM_MANAGE: sight of an
     * answer key is the thing being controlled here, and it should travel with
     * the permission to load one rather than with the ability to schedule an
     * exam.
     */
    @GetMapping("/{id}/questions")
    @PreAuthorize("hasAuthority('EXAM_QUESTION_IMPORT')")
    @Operation(summary = "The whole question bank with its answer key, for proof-reading")
    public ResponseEntity<ApiResponse<List<ExamQuestionReviewResponse>>> questions(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(examService.questionReview(principal, id)));
    }

    @GetMapping("/{id}/analytics")
    @PreAuthorize("hasAnyAuthority('EXAM_RESULT_VIEW_TEAM', 'EXAM_RESULT_VIEW_ALL')")
    @Operation(summary = "Per-question and per-topic analysis — what the exam measured")
    public ResponseEntity<ApiResponse<ExamAnalyticsResponse>> analytics(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean includeTesters) {
        return ResponseEntity.ok(ApiResponse.success(
                analyticsService.analyse(principal, id, includeTesters)));
    }

    @PostMapping("/{id}/retake")
    @PreAuthorize("hasAuthority('EXAM_MANAGE')")
    @Operation(summary = "Open the one retake, for whoever failed or missed it")
    public ResponseEntity<ApiResponse<ExamRetakeResponse>> retake(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long id,
            @Valid @RequestBody RetakeRequest request) {
        ExamRetakeResponse retake = retakeService.open(principal, id, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(retake, retake.message()));
    }
}
