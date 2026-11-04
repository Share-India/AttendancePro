package com.company.attendance.dto.response.exam;

import com.company.attendance.enums.ExamDifficulty;
import com.company.attendance.enums.ExamQuestionType;

import java.util.List;

/**
 * The question bank with its keys, for proof-reading before publish.
 *
 * <p>Gated on EXAM_QUESTION_IMPORT and never scoped: there is no safe partial
 * view of an answer key. A retake shows its parent's bank, because that is the
 * bank it is sat from.
 */
public record ExamQuestionReviewResponse(String setCode,
                                         int questionCount,
                                         int totalMarks,
                                         List<Question> questions) {

    public record Question(Long id,
                           int questionNumber,
                           String topic,
                           ExamDifficulty difficulty,
                           ExamQuestionType questionType,
                           String questionText,
                           int marks,
                           String explanation,
                           String sourceReference,
                           List<String> correctLabels,
                           List<Option> options) {
    }

    public record Option(String label, String text, boolean correct) {
    }
}
