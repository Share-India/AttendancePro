package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import com.company.attendance.enums.ExamDifficulty;
import com.company.attendance.enums.ExamQuestionType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * One question, its options and its key.
 *
 * <p>{@code topic} is the workbook's Product column. It is what makes "the team
 * is weak on this product" an answerable question, which is the main output of
 * a study exam — so it is normalised on import rather than stored as typed.
 */
@Entity
@Table(name = "exam_questions")
@Getter
@Setter
@NoArgsConstructor
public class ExamQuestion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_set_id", nullable = false)
    private ExamQuestionSet questionSet;

    @Column(name = "question_number", nullable = false)
    private int questionNumber;

    @Column(name = "topic", length = 120)
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", length = 20)
    private ExamDifficulty difficulty;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type", nullable = false, length = 20)
    private ExamQuestionType questionType = ExamQuestionType.SINGLE_CHOICE;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Column(name = "marks", nullable = false)
    private int marks = 5;

    /** What the candidate reads after submitting. The point of the exercise. */
    @Column(name = "explanation", columnDefinition = "TEXT")
    private String explanation;

    @Column(name = "source_reference", length = 500)
    private String sourceReference;

    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<ExamQuestionOption> options = new ArrayList<>();

    public void addOption(ExamQuestionOption option) {
        option.setQuestion(this);
        options.add(option);
    }

    /** The key, sorted, so a comparison never depends on the order it was typed. */
    public List<String> correctLabels() {
        return options.stream()
                .filter(ExamQuestionOption::isCorrect)
                .map(ExamQuestionOption::getOptionLabel)
                .sorted()
                .toList();
    }
}
