package com.company.attendance.dto.response.exam;

import com.company.attendance.enums.ExamDifficulty;
import com.company.attendance.enums.ExamStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the exam measured.
 *
 * <p>For a study exam this is the primary output, not anyone's individual mark:
 * "31 of 40 people got the reset-benefit question wrong" names the next
 * training session, which no score ever does.
 *
 * <p>{@code includeTesters} is carried on the response because the figures mean
 * something different with and without the people who wrote the paper, and a
 * screen showing one while labelled the other would be worse than no screen.
 */
public record ExamAnalyticsResponse(Long examId,
                                    String examCode,
                                    String title,
                                    ExamStatus status,
                                    boolean includeTesters,
                                    int rosterSize,
                                    int candidateCount,
                                    int testerCount,
                                    int submittedCount,
                                    int inProgressCount,
                                    int notAttemptedCount,
                                    int passedCount,
                                    int failedCount,
                                    BigDecimal passRate,
                                    BigDecimal averageScore,
                                    BigDecimal averagePercentage,
                                    Integer highestScore,
                                    Integer lowestScore,
                                    long averageSeconds,
                                    List<QuestionStat> questions,
                                    List<TopicStat> topics) {

    /**
     * @param asked       how many papers carried the question, not how many
     *                    answer rows exist — somebody who never touched it
     *                    leaves no row, and counting only rows turns "nobody
     *                    could answer this" into a perfect score
     * @param correctRate null, deliberately, when nobody was asked: a question
     *                    nobody sat is unmeasured, not failed, and reporting it
     *                    as 0% would sort forty untaken questions to the top of
     *                    a list whose whole job is to name what to teach next
     */
    public record QuestionStat(Long questionId,
                               String setCode,
                               int questionNumber,
                               String topic,
                               ExamDifficulty difficulty,
                               String questionText,
                               List<String> correctLabels,
                               int asked,
                               int answered,
                               int skipped,
                               int correct,
                               BigDecimal correctRate,
                               String commonestWrongLabels,
                               String commonestWrongText,
                               int commonestWrongCount) {
    }

    public record TopicStat(String topic,
                            int questionCount,
                            int asked,
                            int correct,
                            BigDecimal correctRate) {
    }
}
