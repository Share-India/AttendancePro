package com.company.attendance.entity;

import com.company.attendance.entity.base.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One paper: set A, B or C.
 *
 * <p>The count and the total are stored rather than computed because publish
 * checks that the three sets agree. A pass mark of 40 is not the same bar
 * against a paper worth 95, and nothing in the results would ever reveal it.
 */
@Entity
@Table(name = "exam_question_sets")
@Getter
@Setter
@NoArgsConstructor
public class ExamQuestionSet extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_id", nullable = false)
    private Exam exam;

    @Column(name = "set_code", nullable = false, length = 10)
    private String setCode;

    @Column(name = "question_count", nullable = false)
    private int questionCount;

    @Column(name = "total_marks", nullable = false)
    private int totalMarks;

    @OneToMany(mappedBy = "questionSet", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("questionNumber ASC")
    private List<ExamQuestion> questions = new ArrayList<>();

    public void addQuestion(ExamQuestion question) {
        question.setQuestionSet(this);
        questions.add(question);
    }
}
