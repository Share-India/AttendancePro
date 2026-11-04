package com.company.attendance.repository;

import com.company.attendance.entity.ExamQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExamQuestionRepository extends JpaRepository<ExamQuestion, Long> {

    /**
     * One paper, with its options already loaded.
     *
     * <p>{@code DISTINCT} because the join to options multiplies the rows. The
     * alternative — twenty lazy loads while assembling a paper — is the classic
     * way a page that looks instant in testing takes four seconds in use.
     */
    @Query("""
            SELECT DISTINCT q FROM ExamQuestion q
             LEFT JOIN FETCH q.options
             WHERE q.questionSet.id = :questionSetId
             ORDER BY q.questionNumber ASC
            """)
    List<ExamQuestion> findPaper(@Param("questionSetId") Long questionSetId);

    /**
     * Every question in an exam's bank, across all three sets.
     *
     * <p>The set is fetched too, not merely joined: every caller reads its code
     * to group by, which would otherwise be one extra query per question — and
     * outside a transaction it would not load at all.
     */
    @Query("""
            SELECT DISTINCT q FROM ExamQuestion q
             LEFT JOIN FETCH q.options
             JOIN FETCH q.questionSet s
             WHERE s.exam.id = :examId
             ORDER BY s.setCode ASC, q.questionNumber ASC
            """)
    List<ExamQuestion> findBank(@Param("examId") Long examId);
}
