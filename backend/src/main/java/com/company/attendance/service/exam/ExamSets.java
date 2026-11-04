package com.company.attendance.service.exam;

import com.company.attendance.entity.Exam;

/**
 * Which exam owns the question bank.
 *
 * <p>A retake is a child exam over the <em>same</em> sixty questions, not a
 * copy of them. So its participants are assigned sets that belong to the
 * original, and anything asking "which sets does this exam have" has to ask the
 * original instead.
 *
 * <p>Copying the bank would have been simpler to write and would have broken
 * {@code findSetIdsSeenBy}: two rows for the same twenty questions are two
 * different set ids, and the unseen-set rule would have cheerfully handed a
 * failing candidate the paper they had just been shown the answers to.
 *
 * <p>One line, in one place, because getting it wrong is silent.
 */
public final class ExamSets {

    private ExamSets() {
    }

    /** The exam whose {@code exam_question_sets} rows apply to this one. */
    public static Exam bank(Exam exam) {
        return exam.getParentExam() == null ? exam : exam.getParentExam();
    }

    public static Long bankExamId(Exam exam) {
        return bank(exam).getId();
    }
}
