package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import com.company.attendance.enums.ExamFeedbackVisibility;
import com.company.attendance.enums.ExamStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Duration;
import java.time.Instant;

/**
 * One sitting of a weekly study exam.
 *
 * <p>Everything about how the paper is marked lives on this row rather than in
 * code — duration, marks, pass mark, shuffling, feedback timing — because the
 * first request after launch is always to change one of them for one exam only.
 *
 * <p>A retake is a <em>child</em> of the exam it follows, not a copy of it. The
 * question bank stays attached to the parent, which is what makes the
 * unseen-set rule enforceable: two rows for the same twenty questions would be
 * two different set ids, and a failing candidate would be handed the paper they
 * had just been shown the answers to.
 */
@Entity
@Table(name = "exams")
@Getter
@Setter
@NoArgsConstructor
public class Exam extends BaseEntity {

    @Column(name = "exam_code", nullable = false, unique = true, length = 40)
    private String examCode;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "instructions", columnDefinition = "TEXT")
    private String instructions;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExamStatus status = ExamStatus.DRAFT;

    /** Stored UTC. Entered and displayed in the office zone. */
    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes = 25;

    @Column(name = "entry_closes_before_min", nullable = false)
    private int entryClosesBeforeMinutes = 5;

    @Column(name = "marks_per_question", nullable = false)
    private int marksPerQuestion = 5;

    @Column(name = "negative_marks", nullable = false)
    private int negativeMarks;

    @Column(name = "total_marks", nullable = false)
    private int totalMarks = 100;

    @Column(name = "pass_marks", nullable = false)
    private int passMarks = 40;

    @Column(name = "shuffle_questions", nullable = false)
    private boolean shuffleQuestions = true;

    @Column(name = "shuffle_options", nullable = false)
    private boolean shuffleOptions = true;

    @Column(name = "partial_credit", nullable = false)
    private boolean partialCredit;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_visibility", nullable = false, length = 20)
    private ExamFeedbackVisibility feedbackVisibility = ExamFeedbackVisibility.IMMEDIATE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_exam_id")
    private Exam parentExam;

    @Column(name = "retake_requires_unseen_set", nullable = false)
    private boolean retakeRequiresUnseenSet = true;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private Long publishedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    public boolean isRetake() {
        return parentExam != null;
    }

    /**
     * Whether somebody may still start the paper.
     *
     * <p>Starting five minutes before the window shuts gives five minutes for a
     * twenty-five minute paper. That is arithmetically right and reliably
     * produces a complaint, so the door closes early.
     */
    public boolean acceptsNewAttemptsAt(Instant now) {
        return !now.isBefore(windowStart)
                && now.isBefore(windowEnd.minus(Duration.ofMinutes(entryClosesBeforeMinutes)));
    }
}
