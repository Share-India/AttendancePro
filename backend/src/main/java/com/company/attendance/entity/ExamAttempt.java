package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import com.company.attendance.enums.ExamSubmissionSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One sitting of one paper by one person.
 *
 * <p>Two things here are the record rather than a convenience.
 *
 * <p>{@code expiresAt} is fixed when the attempt starts and never extends. It
 * is the only clock that counts; the browser counts down for the candidate's
 * benefit and has no say in when their time is up.
 *
 * <p>{@code questionOrder} and {@code optionOrder} are the paper this person
 * actually sat. Without them the submission cannot be scored, the candidate
 * cannot be shown what they answered, and a disputed result cannot be settled.
 * A seeded shuffle would only hold until somebody changed the seed.
 */
@Entity
@Table(name = "exam_attempts")
@Getter
@Setter
@NoArgsConstructor
public class ExamAttempt extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private ExamParticipant participant;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber = 1;

    @Column(name = "question_order", columnDefinition = "TEXT")
    private String questionOrder;

    @Column(name = "option_order", columnDefinition = "TEXT")
    private String optionOrder;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "submission_source", length = 30)
    private ExamSubmissionSource submissionSource;

    @Column(name = "score")
    private Integer score;

    @Column(name = "max_score")
    private Integer maxScore;

    @Column(name = "percentage", precision = 5, scale = 2)
    private BigDecimal percentage;

    /** Decided on marks against the stored pass mark, never on this percentage. */
    @Column(name = "passed")
    private Boolean passed;

    public boolean isSubmitted() {
        return submittedAt != null;
    }

    public boolean hasExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
