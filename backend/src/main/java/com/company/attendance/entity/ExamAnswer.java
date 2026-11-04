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
import java.util.List;

/**
 * What one person answered to one question.
 *
 * <p>This is the record; {@code ExamAttempt.score} is derived from it — the
 * same relationship as {@code attendance_punch_events} to {@code attendance}.
 * A question found to be wrongly keyed in November is then a re-score rather
 * than two hundred people sitting the paper again.
 *
 * <p>Selected options are stored as the canonical labels from the question
 * bank, not the shuffled labels the candidate clicked. Translation happens
 * once, on the way in.
 */
@Entity
@Table(name = "exam_answers")
@Getter
@Setter
@NoArgsConstructor
public class ExamAnswer extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    private ExamAttempt attempt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private ExamQuestion question;

    /** Comma-separated canonical labels, or null when the question was skipped. */
    @Column(name = "selected_options", length = 20)
    private String selectedOptions;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    /** Signed, so negative marking needs no migration to switch on. */
    @Column(name = "marks_awarded", nullable = false)
    private int marksAwarded;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;

    public boolean isSkipped() {
        return selectedOptions == null || selectedOptions.isBlank();
    }

    /** The canonical labels chosen, in sorted order, or empty when skipped. */
    public List<String> selectedLabels() {
        return isSkipped() ? List.of() : List.of(selectedOptions.split(","));
    }
}
