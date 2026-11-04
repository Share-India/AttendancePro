package com.company.attendance.entity;

import com.company.attendance.enums.ExamAudienceScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One rule describing who should sit an exam, before the roster exists.
 *
 * <p>Read exactly once, when the exam is published. After that the
 * {@link ExamParticipant} rows are the record, so moving somebody between
 * departments next month cannot change who was supposed to sit last week's
 * paper. Same shape, and the same reason, as {@link MeetingAudience}.
 */
@Entity
@Table(name = "exam_audience")
@Getter
@Setter
@NoArgsConstructor
public class ExamAudience {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ExamAudienceScope scopeType;

    /** Null for GLOBAL, which means everybody active. */
    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
