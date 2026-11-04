package com.company.attendance.repository;

import com.company.attendance.entity.ExamAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamAnswerRepository extends JpaRepository<ExamAnswer, Long> {

    @Query("""
            SELECT a FROM ExamAnswer a
             JOIN FETCH a.question q
             WHERE a.attempt.id = :attemptId
            """)
    List<ExamAnswer> findByAttemptId(@Param("attemptId") Long attemptId);

    Optional<ExamAnswer> findByAttemptIdAndQuestionId(Long attemptId, Long questionId);

    /**
     * Per question: how many answered it and how many got it right.
     *
     * <p>Counted only over submitted attempts, because an answer sitting in a
     * paper somebody is still writing is not evidence of anything yet.
     */
    @Query("""
            SELECT a.question.id, COUNT(a), SUM(CASE WHEN a.correct = TRUE THEN 1 ELSE 0 END)
              FROM ExamAnswer a
              JOIN a.attempt t
              JOIN t.participant p
             WHERE p.exam.id = :examId
               AND t.submittedAt IS NOT NULL
               AND (:includeTesters = TRUE OR p.participantKind = 'CANDIDATE')
             GROUP BY a.question.id
            """)
    List<Object[]> questionStats(@Param("examId") Long examId,
                                 @Param("includeTesters") boolean includeTesters);

    /**
     * The wrong answers people gave, commonest first — the distractor that is
     * working. Ordered so the first row for a question is its winner.
     */
    @Query("""
            SELECT a.question.id, a.selectedOptions, COUNT(a) AS hits
              FROM ExamAnswer a
              JOIN a.attempt t
              JOIN t.participant p
             WHERE p.exam.id = :examId
               AND t.submittedAt IS NOT NULL
               AND a.correct = FALSE
               AND a.selectedOptions IS NOT NULL
               AND (:includeTesters = TRUE OR p.participantKind = 'CANDIDATE')
             GROUP BY a.question.id, a.selectedOptions
             ORDER BY a.question.id ASC, hits DESC
            """)
    List<Object[]> wrongAnswerSpread(@Param("examId") Long examId,
                                     @Param("includeTesters") boolean includeTesters);
}
