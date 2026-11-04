package com.company.attendance.repository;

import com.company.attendance.entity.ExamQuestionSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamQuestionSetRepository extends JpaRepository<ExamQuestionSet, Long> {

    List<ExamQuestionSet> findByExamIdOrderBySetCodeAsc(Long examId);

    Optional<ExamQuestionSet> findByExamIdAndSetCode(Long examId, String setCode);
}
