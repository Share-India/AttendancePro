package com.company.attendance.service.exam;

import com.company.attendance.entity.Exam;
import com.company.attendance.entity.ExamQuestion;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Marks one answer against the stored key.
 *
 * <p>Everything here works in canonical labels and integer marks. The client
 * submits which options were chosen and nothing else — never a score, never a
 * correctness flag — and this recomputes from the question bank every time,
 * which is also what lets a wrongly keyed question be re-scored in November
 * instead of re-sat.
 */
@Component
public class ExamScorer {

    /** What one answer was worth, and whether it was right. */
    public record Mark(boolean correct, int marksAwarded) {
    }

    /**
     * All or nothing on a multi-answer question unless the exam says otherwise:
     * every correct option and no wrong ones. Two of three right is not
     * two-thirds of knowing the answer.
     */
    public Mark mark(Exam exam, ExamQuestion question, List<String> selectedCanonicalLabels) {
        List<String> correct = question.correctLabels();
        List<String> chosen = selectedCanonicalLabels == null ? List.of()
                : selectedCanonicalLabels.stream().map(String::trim).distinct().sorted().toList();

        if (chosen.isEmpty()) {
            // A skipped question is not a wrong one: negative marking is for
            // answering wrongly, and penalising silence would reward guessing.
            return new Mark(false, 0);
        }
        if (chosen.equals(correct)) {
            return new Mark(true, question.getMarks());
        }
        if (exam.isPartialCredit() && !correct.isEmpty()) {
            long hits = chosen.stream().filter(correct::contains).count();
            boolean anyWrong = chosen.stream().anyMatch(label -> !correct.contains(label));
            if (!anyWrong && hits > 0) {
                // Deliberately floor rather than round: a partial answer should
                // not round up into the mark a complete one earns.
                return new Mark(false, (int) (question.getMarks() * hits / correct.size()));
            }
        }
        return new Mark(false, -exam.getNegativeMarks());
    }

    /**
     * Whether the paper was passed. Integer marks against the stored pass mark:
     * with five-mark questions every score is a multiple of five, seven correct
     * is 35 and a fail, and no float percentage gets a say in it.
     */
    public boolean passed(Exam exam, int score) {
        return score >= exam.getPassMarks();
    }
}
