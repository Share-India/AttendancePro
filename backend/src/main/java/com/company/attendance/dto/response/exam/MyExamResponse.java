package com.company.attendance.dto.response.exam;

import com.company.attendance.enums.ExamParticipantStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One exam as the person sitting it sees it.
 *
 * <p>{@code bucket} and {@code blockedReason} are decided on the server. The
 * alternative is every client re-deriving "can I start this yet" from a window,
 * a duration, an entry cut-off and a status, and getting it subtly wrong.
 */
public record MyExamResponse(
        Long examId,
        String examCode,
        String title,
        String description,
        String instructions,
        Instant windowStart,
        Instant windowEnd,
        int durationMinutes,
        int entryClosesBeforeMinutes,
        int questionCount,
        int totalMarks,
        int passMarks,
        boolean retake,
        String setCode,
        ExamParticipantStatus status,
        Bucket bucket,
        String blockedReason,
        Long attemptId,
        Integer score,
        Integer maxScore,
        BigDecimal percentage,
        Boolean passed,
        Instant submittedAt,
        boolean feedbackAvailable) {

    /** Which list this exam belongs in on the employee screen. */
    public enum Bucket {
        OPEN,
        IN_PROGRESS,
        UPCOMING,
        COMPLETED,
        MISSED
    }
}
