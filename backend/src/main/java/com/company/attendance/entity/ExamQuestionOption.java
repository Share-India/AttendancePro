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

/**
 * One option, under its canonical label.
 *
 * <p>Canonical means "as the workbook wrote it". What a candidate sees is a
 * permutation recorded on their own attempt, and their click is translated back
 * to this label on the way in — once, so that scoring and the analytics never
 * have to unshuffle anything.
 */
@Entity
@Table(name = "exam_question_options")
@Getter
@Setter
@NoArgsConstructor
public class ExamQuestionOption extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private ExamQuestion question;

    @Column(name = "option_label", nullable = false, length = 4)
    private String optionLabel;

    @Column(name = "option_text", nullable = false, columnDefinition = "TEXT")
    private String optionText;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
