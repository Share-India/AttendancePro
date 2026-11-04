package com.company.attendance.dto.response.exam;

import java.util.List;

/**
 * One question worked through, after submission.
 *
 * <p>The other half of the pair described in {@link ExamAttemptQuestion}: this
 * type carries the key, the explanation and the source, and it is only ever
 * built by the result path, which refuses until the attempt has been submitted.
 *
 * <p>Labels here are the candidate's own displayed labels, so the paper they
 * are shown back matches the one they sat.
 */
public record ExamResultQuestion(
        Long questionId,
        int displayNumber,
        String questionText,
        String topic,
        int marks,
        int marksAwarded,
        boolean correct,
        boolean skipped,
        List<ExamResultOption> options,
        List<String> chosenLabels,
        List<String> correctLabels,
        String explanation,
        String sourceReference
) {
}
