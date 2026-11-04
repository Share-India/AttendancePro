package com.company.attendance.repository;

import com.company.attendance.entity.Exam;
import com.company.attendance.enums.ExamStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface ExamRepository extends JpaRepository<Exam, Long> {

    boolean existsByExamCodeIgnoreCase(String examCode);

    @Query("""
            SELECT e FROM Exam e
             WHERE (:status IS NULL OR e.status = :status)
             ORDER BY e.windowStart DESC
            """)
    Page<Exam> search(@Param("status") ExamStatus status, Pageable pageable);

    /**
     * Exams whose status has fallen behind their own window.
     *
     * <p>There is no scheduler in this application, so nothing moves an exam
     * from PUBLISHED to OPEN to CLOSED on a timer. This feeds the lazy
     * settlement path: the exam catches up the next time anybody reads it.
     */
    @Query("""
            SELECT e FROM Exam e
             WHERE e.status IN ('PUBLISHED', 'OPEN')
               AND (e.windowStart <= :now OR e.windowEnd <= :now)
            """)
    List<Exam> findNeedingSettlement(@Param("now") Instant now);

    /** A retake points at the exam it follows. Used to refuse a third sitting. */
    List<Exam> findByParentExamId(Long parentExamId);
}
