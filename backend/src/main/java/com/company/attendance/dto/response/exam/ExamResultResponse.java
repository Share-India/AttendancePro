package com.company.attendance.dto.response.exam;

import com.company.attendance.enums.ExamSubmissionSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * How it went.
 *
 * <p>{@code passed} comes from marks against the stored pass mark, never from
 * {@code percentage}: with five-mark questions every score is a multiple of
 * five, eight correct is 40 and a pass, seven is 35 and a fail, and no float
 * rounding gets a say in anybody's result.
 *
 * <p>{@code feedbackVisible} is false when the exam holds the worked answers
 * back until the window closes. The score is still shown; {@code questions} is
 * then empty rather than half-filled.
 */
public record ExamResultResponse(
        Long attemptId,
        Long examId,
        String examTitle,
        String setCode,
        int score,
        int maxScore,
        BigDecimal percentage,
        int passMarks,
        boolean passed,
        int correctCount,
        int questionCount,
        Instant startedAt,
        Instant submittedAt,
        long secondsTaken,
        ExamSubmissionSource submissionSource,
        boolean feedbackVisible,
        List<ExamResultQuestion> questions
) {
}
