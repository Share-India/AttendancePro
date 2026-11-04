package com.company.attendance.dto.response.exam;

import com.company.attendance.enums.ExamStatus;

import java.time.Instant;

/** One row in the exam list. */
public record ExamSummaryResponse(
        Long id,
        String examCode,
        String title,
        ExamStatus status,
        Instant windowStart,
        Instant windowEnd,
        int durationMinutes,
        int passMarks,
        int totalMarks,
        int setCount,
        int questionCount,
        int rosterSize,
        int submittedCount,
        boolean retake,
        Long parentExamId
) {
}
