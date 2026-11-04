package com.company.attendance.dto.response.exam;

import java.util.List;
import java.util.Map;

/**
 * The ways a question paper can be passed without knowing anything.
 *
 * <p>Not polish. The first real workbook written for this system could be
 * passed at 92% by ticking B twenty times, and at 80% by always choosing the
 * longest option. It would have run perfectly, produced an excellent pass rate
 * and measured nothing at all — question quality fails silently, which is why
 * it has to be looked for deliberately rather than noticed.
 *
 * <p>Everything here is a warning. HR can publish regardless, and sometimes
 * should. The point is that nobody publishes twenty B answers without having
 * been told.
 *
 * <p>Stored on the upload row as JSON, so "we were told" is checkable later.
 */
public record ExamQualityReport(List<SetQuality> sets,
                                List<String> duplicateQuestions,
                                List<String> warnings,
                                boolean clean) {

    /**
     * @param answerSpread how many questions have each answer key, so a set
     *                     with fifteen Bs out of twenty is visible at a glance
     * @param correctIsLongestRate above about 0.40, "pick the longest" starts
     *                             to beat reading the question
     */
    public record SetQuality(String setCode,
                             int questionCount,
                             Map<String, Integer> answerSpread,
                             int correctIsLongest,
                             double correctIsLongestRate,
                             double absoluteRateCorrect,
                             double absoluteRateWrong,
                             int lopsidedOptionCount,
                             int shortOptionSetCount,
                             List<String> warnings) {
    }
}
