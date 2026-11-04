package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The provenance of a question bank — never the workbook itself.
 *
 * <p>The workbook is an answer key, and an answer key on disk is an answer key
 * waiting to leak. What is kept is the file's name, its hash and the quality
 * report, which is enough to prove afterwards which file produced this bank and
 * that whoever published it had been told what was wrong with it.
 */
@Entity
@Table(name = "exam_uploads")
@Getter
@Setter
@NoArgsConstructor
public class ExamUpload extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    @Column(name = "file_name", nullable = false, length = 260)
    private String fileName;

    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "question_count", nullable = false)
    private int questionCount;

    @Column(name = "set_count", nullable = false)
    private int setCount;

    @Column(name = "rejected_count", nullable = false)
    private int rejectedCount;

    /** The quality report as JSON. Warnings, recorded so they are checkable. */
    @Column(name = "quality_report", columnDefinition = "TEXT")
    private String qualityReport;

    @Column(name = "uploaded_by")
    private Long uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;
}
