package com.company.attendance.controller;

import com.company.attendance.dto.request.exam.AnswerSaveRequest;
import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.exam.ExamAttemptResponse;
import com.company.attendance.dto.response.exam.ExamResultResponse;
import com.company.attendance.dto.response.exam.MyExamResponse;
import com.company.attendance.security.CurrentUser;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.ExamAttemptService;
import com.company.attendance.service.ExamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Sitting an exam.
 *
 * <p>Everything here is about the signed-in person and nobody else. There is no
 * employee id in any path or body: identity comes from the JWT, which is what
 * stops one candidate reading another's paper by changing a number in the URL.
 *
 * <p>Separate from {@link ExamController} on purpose. These endpoints return
 * the sitting DTOs, which have nowhere to put an answer key; that controller
 * returns the ones that carry it. Keeping them apart means a future endpoint
 * added to the wrong file looks wrong.
 */
@RestController
@RequestMapping("/api/v1/exams/me")
@Tag(name = "Exam attempts", description = "Sitting an exam, and being told how it went")
public class ExamAttemptController {

    private final ExamService examService;
    private final ExamAttemptService attemptService;

    public ExamAttemptController(ExamService examService, ExamAttemptService attemptService) {
        this.examService = examService;
        this.attemptService = attemptService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('EXAM_VIEW_SELF')")
    @Operation(summary = "The exams I have been given")
    public ResponseEntity<ApiResponse<List<MyExamResponse>>> myExams(
            @CurrentUser UserPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.success(examService.myExams(principal)));
    }

    /**
     * Starts the paper, or hands back the one already running.
     *
     * <p>A POST because it creates the attempt and fixes the clock. Idempotent
     * by the unique key on (participant, attempt number), so a double tap on
     * Start gets the same paper rather than a second one.
     */
    @PostMapping("/{examId}/attempt")
    @PreAuthorize("hasAuthority('EXAM_ATTEMPT')")
    @Operation(summary = "Start my attempt, or resume the one already open")
    public ResponseEntity<ApiResponse<ExamAttemptResponse>> start(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long examId) {
        ExamAttemptResponse paper = attemptService.start(principal, examId);
        return ResponseEntity.ok(ApiResponse.success(paper, "Your time has started"));
    }

    @GetMapping("/attempts/{attemptId}")
    @PreAuthorize("hasAuthority('EXAM_ATTEMPT')")
    @Operation(summary = "My paper as it stands, so a refresh costs nothing")
    public ResponseEntity<ApiResponse<ExamAttemptResponse>> resume(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long attemptId) {
        return ResponseEntity.ok(ApiResponse.success(
                attemptService.resume(principal, attemptId)));
    }

    /**
     * Autosave, one question at a time.
     *
     * <p>A PUT with the question in the body rather than in the path: the
     * operation is "this is my answer to that question", and it is idempotent.
     */
    @PutMapping("/attempts/{attemptId}/answers")
    @PreAuthorize("hasAuthority('EXAM_ATTEMPT')")
    @Operation(summary = "Save one answer")
    public ResponseEntity<ApiResponse<Void>> saveAnswer(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long attemptId,
            @Valid @RequestBody AnswerSaveRequest request) {
        attemptService.saveAnswer(principal, attemptId, request);
        return ResponseEntity.ok(ApiResponse.success("Saved"));
    }

    @PostMapping("/attempts/{attemptId}/submit")
    @PreAuthorize("hasAuthority('EXAM_ATTEMPT')")
    @Operation(summary = "Hand the paper in and see the result")
    public ResponseEntity<ApiResponse<ExamResultResponse>> submit(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long attemptId) {
        ExamResultResponse result = attemptService.submit(principal, attemptId);
        return ResponseEntity.ok(ApiResponse.success(result,
                result.passed() ? "Passed" : "Submitted"));
    }

    /** Refuses with 403 until the paper is in. */
    @GetMapping("/attempts/{attemptId}/result")
    @PreAuthorize("hasAuthority('EXAM_ATTEMPT')")
    @Operation(summary = "My result and the worked answers")
    public ResponseEntity<ApiResponse<ExamResultResponse>> result(
            @CurrentUser UserPrincipal principal,
            @PathVariable Long attemptId) {
        return ResponseEntity.ok(ApiResponse.success(
                attemptService.result(principal, attemptId)));
    }
}
