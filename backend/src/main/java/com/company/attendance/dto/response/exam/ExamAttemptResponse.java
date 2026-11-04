package com.company.attendance.dto.response.exam;

import java.time.Instant;
import java.util.List;

/**
 * The paper, as it stands, for the person sitting it.
 *
 * <p>{@code expiresAt} is the server's clock and the only one that counts.
 * {@code secondsRemaining} is sent so the browser can count down without
 * trusting the device clock, which may be minutes out either way.
 */
public record ExamAttemptResponse(
        Long attemptId,
        Long examId,
        String examTitle,
        String instructions,
        String setCode,
        int questionCount,
        int totalMarks,
        int passMarks,
        int durationMinutes,
        Instant startedAt,
        Instant expiresAt,
        long secondsRemaining,
        boolean submitted,
        List<ExamAttemptQuestion> questions
) {
}
