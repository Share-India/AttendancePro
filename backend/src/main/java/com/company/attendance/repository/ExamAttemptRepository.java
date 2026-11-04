package com.company.attendance.repository;

import com.company.attendance.entity.ExamAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExamAttemptRepository extends JpaRepository<ExamAttempt, Long> {

    Optional<ExamAttempt> findByParticipantIdAndAttemptNumber(Long participantId, int attemptNumber);

    List<ExamAttempt> findByParticipantIdIn(Collection<Long> participantIds);

    /**
     * Papers still open past their own clock, for one exam.
     *
     * <p>Ids rather than entities: each is then scored in its own transaction,
     * so one malformed row cannot roll back everybody else's submission.
     */
    @Query("""
            SELECT a.id FROM ExamAttempt a
             WHERE a.participant.exam.id = :examId
               AND a.submittedAt IS NULL
               AND a.expiresAt <= :now
            """)
    List<Long> findExpiredUnsubmitted(@Param("examId") Long examId, @Param("now") Instant now);

    /**
     * How many submitted papers each set carried.
     *
     * <p>Needed by the question analysis: somebody who never touched a question
     * leaves no answer row, so counting only rows would turn "nobody could
     * answer this" into a perfect score.
     */
    @Query("""
            SELECT p.assignedSet.id, COUNT(a)
              FROM ExamAttempt a
              JOIN a.participant p
             WHERE p.exam.id = :examId
               AND a.submittedAt IS NOT NULL
               AND (:includeTesters = TRUE OR p.participantKind = 'CANDIDATE')
             GROUP BY p.assignedSet.id
            """)
    List<Object[]> submittedCountPerSet(@Param("examId") Long examId,
                                        @Param("includeTesters") boolean includeTesters);
}
