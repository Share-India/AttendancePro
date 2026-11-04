package com.company.attendance.dto.response.exam;

import java.util.List;

/**
 * The retake that was opened, and who is on it.
 *
 * <p>{@code excluded} names the people who were left off and why. A cohort that
 * silently differs from what HR expected is the kind of thing nobody notices
 * until somebody asks why they were never retested.
 */
public record ExamRetakeResponse(Long examId,
                                 String examCode,
                                 String title,
                                 int cohortSize,
                                 int failedCount,
                                 int neverSatCount,
                                 List<Excluded> excluded,
                                 String message) {

    public record Excluded(Long employeeId, String employeeName, String reason) {
    }
}
