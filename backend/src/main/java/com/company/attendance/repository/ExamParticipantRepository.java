package com.company.attendance.repository;

import com.company.attendance.entity.ExamParticipant;
import com.company.attendance.enums.ExamParticipantStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExamParticipantRepository extends JpaRepository<ExamParticipant, Long> {

    @Query("""
            SELECT p FROM ExamParticipant p
             JOIN FETCH p.employee e
             JOIN FETCH p.assignedSet s
             WHERE p.exam.id = :examId
             ORDER BY e.employeeCode ASC
            """)
    List<ExamParticipant> findRoster(@Param("examId") Long examId);

    Optional<ExamParticipant> findByExamIdAndEmployeeId(Long examId, Long employeeId);

    @Query("SELECT p.employee.id FROM ExamParticipant p WHERE p.exam.id = :examId")
    List<Long> findEmployeeIds(@Param("examId") Long examId);

    List<ExamParticipant> findByExamIdAndStatus(Long examId, ExamParticipantStatus status);

    /** The exams one person has been given, newest window first. */
    @Query("""
            SELECT p FROM ExamParticipant p
             JOIN FETCH p.exam x
             JOIN FETCH p.assignedSet s
             WHERE p.employee.id = :employeeId
               AND x.status <> 'CANCELLED'
             ORDER BY x.windowStart DESC
            """)
    List<ExamParticipant> findMine(@Param("employeeId") Long employeeId);

    /**
     * The sets this person has already been dealt from one question bank.
     *
     * <p>The whole retake rule rests on this. Feedback is immediate, so a
     * retake on a paper somebody has already opened hands them the answer key
     * and then re-tests them on it. The bank exam id is the <em>parent</em> for
     * a retake, which is why the question sets are never copied.
     */
    @Query("""
            SELECT DISTINCT p.assignedSet.id FROM ExamParticipant p
             JOIN p.exam x
             WHERE p.employee.id = :employeeId
               AND (x.id = :bankExamId OR x.parentExam.id = :bankExamId)
            """)
    List<Long> findSetIdsSeenBy(@Param("employeeId") Long employeeId,
                                @Param("bankExamId") Long bankExamId);

    /** Whether this person has already been given a retake of one exam. */
    @Query("""
            SELECT COUNT(p) FROM ExamParticipant p
             JOIN p.exam x
             WHERE p.employee.id = :employeeId
               AND x.parentExam.id = :examId
            """)
    long countRetakesOf(@Param("examId") Long examId, @Param("employeeId") Long employeeId);

    /**
     * Rows for the results and participation reports: every roster entry for an
     * exam whose window overlaps the range, narrowed to the visible employees.
     *
     * <p>An empty collection in a JPQL {@code IN} is a syntax error, so callers
     * pass {@code unrestricted} rather than an empty set — the same shape as
     * {@code MeetingParticipantRepository.findForReport}.
     */
    @Query("""
            SELECT p FROM ExamParticipant p
             JOIN FETCH p.employee e
             JOIN FETCH p.exam x
             JOIN FETCH p.assignedSet s
             WHERE (:unrestricted = TRUE OR e.id IN :employeeIds)
               AND x.windowStart >= :from
               AND x.windowStart < :to
               AND x.status <> 'CANCELLED'
             ORDER BY x.windowStart DESC, e.employeeCode ASC
            """)
    List<ExamParticipant> findForReport(@Param("unrestricted") boolean unrestricted,
                                        @Param("employeeIds") Collection<Long> employeeIds,
                                        @Param("from") Instant from,
                                        @Param("to") Instant to);
}
