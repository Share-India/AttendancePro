package com.company.attendance.repository;

import com.company.attendance.entity.ExamUpload;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ExamUploadRepository extends JpaRepository<ExamUpload, Long> {

    List<ExamUpload> findByExamIdOrderByUploadedAtDesc(Long examId);
}
