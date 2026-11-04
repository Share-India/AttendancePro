package com.company.attendance.dto.response.exam;

import com.company.attendance.enums.ExamAudienceScope;
import com.company.attendance.enums.ExamFeedbackVisibility;
import com.company.attendance.enums.ExamStatus;

import java.time.Instant;
import java.util.List;

/**
 * One exam in full, for whoever is setting it.
 *
 * <p>{@code publishBlockers} is a list rather than a boolean so the form can
 * show everything wrong with the paper at once. Discovering the next problem
 * after each correction is how a five-minute job becomes an afternoon.
 */
public record ExamResponse(
        Long id,
        String examCode,
        String title,
        String description,
        String instructions,
        ExamStatus status,
        Instant windowStart,
        Instant windowEnd,
        int durationMinutes,
        int entryClosesBeforeMinutes,
        int marksPerQuestion,
        int negativeMarks,
        int totalMarks,
        int passMarks,
        boolean shuffleQuestions,
        boolean shuffleOptions,
        boolean partialCredit,
        ExamFeedbackVisibility feedbackVisibility,
        boolean retake,
        Long parentExamId,
        String parentExamCode,
        boolean retakeRequiresUnseenSet,
        Instant publishedAt,
        Instant closedAt,
        String cancellationReason,
        List<Audience> audience,
        List<SetSummary> sets,
        int rosterSize,
        List<String> publishBlockers,
        boolean canEdit,
        boolean canPublish,
        boolean canImportQuestions) {

    public record Audience(ExamAudienceScope scopeType, Long scopeId, String scopeName) {
    }

    public record SetSummary(Long id, String setCode, int questionCount, int totalMarks) {
    }
}
