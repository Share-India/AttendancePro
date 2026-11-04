package com.company.attendance.dto.response.exam;

import java.util.List;

/**
 * One question as the person sitting the paper sees it.
 *
 * <p><strong>This type has nowhere to put the answer.</strong> No correctness
 * flag, no explanation, no source reference, no canonical label — and that is
 * the whole design. The rule the module is built around is that the answer key
 * must never reach the browser during an attempt, and a rule like that is not
 * satisfied by care. It has to be structural.
 *
 * <p>So there are two disjoint families: this one and {@link ExamResultQuestion}.
 * Never merge them into a single type with the key nulled out on the way past —
 * a null-out skipped once is a total and silent compromise, and the exam would
 * carry on looking as though it worked. {@code ExamSecurityIT} walks the
 * serialised JSON of a live attempt and fails if anything matching
 * {@code correct|answer|explanation|source} appears anywhere in the tree.
 *
 * @param options in displayed order, relabelled A, B, C… for this candidate
 * @param selectedLabels what they have chosen so far, in their own labels
 */
public record ExamAttemptQuestion(
        Long questionId,
        int displayNumber,
        String questionText,
        String topic,
        int marks,
        boolean multiSelect,
        List<ExamAttemptOption> options,
        List<String> selectedLabels
) {
}
