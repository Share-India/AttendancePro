package com.company.attendance.service.exam;

import com.company.attendance.entity.Exam;
import com.company.attendance.entity.ExamAnswer;
import com.company.attendance.entity.ExamAttempt;
import com.company.attendance.entity.ExamParticipant;
import com.company.attendance.entity.ExamQuestion;
import com.company.attendance.enums.ExamParticipantStatus;
import com.company.attendance.enums.ExamSubmissionSource;
import com.company.attendance.repository.ExamAnswerRepository;
import com.company.attendance.repository.ExamAttemptRepository;
import com.company.attendance.repository.ExamParticipantRepository;
import com.company.attendance.repository.ExamQuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Marks a paper and writes the outcome.
 *
 * <p>A component of its own rather than a method on the attempt service,
 * because two callers need it and neither may depend on the other: the
 * candidate pressing Submit, and {@link ExamWindowService} closing papers whose
 * clock has run out. Routing the second through the attempt service would make
 * those two beans circular.
 *
 * <p>Every answer is re-marked from the question bank rather than trusting
 * anything stored on the answer row. That is what makes a wrongly keyed
 * question a re-score in November instead of two hundred people sitting the
 * paper again.
 */
@Service
public class ExamScoringService {

    private static final Logger log = LoggerFactory.getLogger(ExamScoringService.class);

    private final ExamAttemptRepository attemptRepository;
    private final ExamAnswerRepository answerRepository;
    private final ExamQuestionRepository questionRepository;
    private final ExamParticipantRepository participantRepository;
    private final ExamScorer scorer;

    public ExamScoringService(ExamAttemptRepository attemptRepository,
                              ExamAnswerRepository answerRepository,
                              ExamQuestionRepository questionRepository,
                              ExamParticipantRepository participantRepository,
                              ExamScorer scorer) {
        this.attemptRepository = attemptRepository;
        this.answerRepository = answerRepository;
        this.questionRepository = questionRepository;
        this.participantRepository = participantRepository;
        this.scorer = scorer;
    }

    /**
     * Marks the paper as it stood at {@code at} and records the outcome.
     *
     * <p>Joins the caller's transaction: a candidate pressing Submit should see
     * their result written with everything else that submission touched.
     */
    @Transactional
    public void score(ExamAttempt attempt, ExamSubmissionSource source, Instant at) {
        Exam exam = attempt.getParticipant().getExam();
        List<ExamQuestion> questions =
                questionRepository.findPaper(attempt.getParticipant().getAssignedSet().getId());

        Map<Long, ExamAnswer> answers = new LinkedHashMap<>();
        answerRepository.findByAttemptId(attempt.getId())
                .forEach(answer -> answers.put(answer.getQuestion().getId(), answer));

        int total = 0;
        int max = 0;
        for (ExamQuestion question : questions) {
            max += question.getMarks();
            ExamAnswer answer = answers.get(question.getId());

            ExamScorer.Mark mark = scorer.mark(exam, question,
                    answer == null ? List.of() : answer.selectedLabels());
            total += mark.marksAwarded();

            if (answer != null) {
                answer.setCorrect(mark.correct());
                answer.setMarksAwarded(mark.marksAwarded());
                answerRepository.save(answer);
            }
        }

        attempt.setScore(total);
        attempt.setMaxScore(max);
        attempt.setPercentage(max == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(total * 100.0 / max).setScale(2, RoundingMode.HALF_UP));
        // On marks against the stored pass mark. The percentage above is for
        // display and never decides anybody's result.
        attempt.setPassed(scorer.passed(exam, total));
        attempt.setSubmittedAt(at);
        attempt.setSubmissionSource(source);
        attemptRepository.save(attempt);

        ExamParticipant participant = attempt.getParticipant();
        participant.setStatus(ExamParticipantStatus.SUBMITTED);
        participantRepository.save(participant);

        log.info("Exam {} scored {}/{} ({}) for employee {} by {}",
                exam.getExamCode(), total, max,
                Boolean.TRUE.equals(attempt.getPassed()) ? "pass" : "fail",
                participant.getEmployee().getId(), source);
    }

    /**
     * Ends a paper whose clock has run out.
     *
     * <p>In its own transaction, so closing two hundred attempts cannot lose
     * all of them to one malformed row. Which source applies is decided here
     * rather than by the caller: whether the candidate's own time ran out or
     * the window shut underneath them is a property of the attempt.
     *
     * <p>Scored as it stood at expiry, not as it stands now — the candidate
     * stopped being able to change it the moment their time ran out.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoSubmitExpired(Long attemptId) {
        ExamAttempt attempt = attemptRepository.findById(attemptId).orElse(null);
        if (attempt == null || attempt.isSubmitted()) {
            return;
        }
        Instant windowEnd = attempt.getParticipant().getExam().getWindowEnd();
        ExamSubmissionSource source = !attempt.getExpiresAt().isBefore(windowEnd)
                ? ExamSubmissionSource.AUTO_WINDOW_CLOSE
                : ExamSubmissionSource.AUTO_TIMEOUT;

        score(attempt, source, attempt.getExpiresAt());
    }
}
