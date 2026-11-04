package com.company.attendance.repository;

import com.company.attendance.entity.ExamAudience;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExamAudienceRepository extends JpaRepository<ExamAudience, Long> {

    List<ExamAudience> findByExamId(Long examId);

    @Modifying
    @Query("DELETE FROM ExamAudience a WHERE a.exam.id = :examId")
    void deleteByExamId(@Param("examId") Long examId);
}
