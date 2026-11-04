package com.company.attendance.service;

import com.company.attendance.dto.response.exam.ExamAnalyticsResponse;
import com.company.attendance.entity.Exam;
import com.company.attendance.entity.ExamAttempt;
import com.company.attendance.entity.ExamParticipant;
import com.company.attendance.entity.ExamQuestion;
import com.company.attendance.entity.ExamQuestionOption;
import com.company.attendance.enums.AccessScope;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.ExamParticipantKind;
import com.company.attendance.enums.ExamParticipantStatus;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.ExamAnswerRepository;
import com.company.attendance.repository.ExamAttemptRepository;
import com.company.attendance.repository.ExamParticipantRepository;
import com.company.attendance.repository.ExamQuestionRepository;
import com.company.attendance.repository.ExamRepository;
import com.company.attendance.security.AccessScopeResolver;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.exam.ExamSets;
import com.company.attendance.service.exam.ExamWindowService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the exam measured.
 *
 * <p>The one place that decides what "correct rate" and "pass rate" mean. Both
 * the analysis screen and the question-analysis report are built from this
 * rather than each computing its own figures — a reporting change that quietly
 * became a second, divergent definition of a pass rate is exactly the kind of
 * thing nobody notices until two screens disagree in a meeting.
 *
 * <p>For a study exam this is the primary output. "31 of 40 people got the
 * reset-benefit question wrong" names the next training session, which no
 * individual score ever does.
 */
@Service
public class ExamAnalyticsService {

    private static final String PERM_VIEW_ALL = "EXAM_RESULT_VIEW_ALL";
    private static final String PERM_VIEW_TEAM = "EXAM_RESULT_VIEW_TEAM";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ExamRepository examRepository;
    private final ExamParticipantRepository participantRepository;
    private final ExamAttemptRepository attemptRepository;
    private final ExamAnswerRepository answerRepository;
    private final ExamQuestionRepository questionRepository;
    private final AccessScopeResolver accessScopeResolver;
    private final ExamWindowService windowService;

    public ExamAnalyticsService(ExamRepository examRepository,
                                ExamParticipantRepository participantRepository,
                                ExamAttemptRepository attemptRepository,
                                ExamAnswerRepository answerRepository,
                                ExamQuestionRepository questionRepository,
                                AccessScopeResolver accessScopeResolver,
                                ExamWindowService windowService) {
        this.examRepository = examRepository;
        this.participantRepository = participantRepository;
        this.attemptRepository = attemptRepository;
        this.answerRepository = answerRepository;
        this.questionRepository = questionRepository;
        this.accessScopeResolver = accessScopeResolver;
        this.windowService = windowService;
    }

    @Transactional
    public ExamAnalyticsResponse analyse(UserPrincipal principal, Long examId,
                                         boolean includeTesters) {
        AccessScope scope = accessScopeResolver.resolve(principal, PERM_VIEW_ALL, PERM_VIEW_TEAM);
        if (scope == AccessScope.SELF) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "Exam analysis is for managers and HR.");
        }

        Exam exam = windowService.settle(examRepository.findById(examId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "That exam does not exist.")));

        // Deliberately not scoped to the caller's subtree. The per-question
        // figures are about the paper, not about people, and a manager's slice
        // of four answers would read as evidence while being noise. Individual
        // results stay scoped — see ExamServiceImpl.participants.
        List<ExamParticipant> roster = participantRepository.findRoster(exam.getId());
        List<ExamParticipant> counted = roster.stream()
                .filter(p -> includeTesters
                        || p.getParticipantKind() == ExamParticipantKind.CANDIDATE)
                .toList();

        Map<Long, ExamAttempt> attempts = attemptsOf(counted);
        List<ExamAttempt> submitted = attempts.values().stream()
                .filter(ExamAttempt::isSubmitted)
                .toList();
        long passes = submitted.stream().filter(a -> Boolean.TRUE.equals(a.getPassed())).count();

        List<ExamAnalyticsResponse.QuestionStat> questions = questionStats(exam, includeTesters);

        return new ExamAnalyticsResponse(
                exam.getId(), exam.getExamCode(), exam.getTitle(), exam.getStatus(),
                includeTesters,
                roster.size(),
                (int) roster.stream()
                        .filter(p -> p.getParticipantKind() == ExamParticipantKind.CANDIDATE)
                        .count(),
                (int) roster.stream()
                        .filter(p -> p.getParticipantKind() == ExamParticipantKind.TESTER)
                        .count(),
                submitted.size(),
                (int) counted.stream()
                        .filter(p -> p.getStatus() == ExamParticipantStatus.IN_PROGRESS).count(),
                (int) counted.stream().filter(this::neverSat).count(),
                (int) passes,
                submitted.size() - (int) passes,
                rate(passes, submitted.size()),
                averageScore(submitted),
                averagePercentage(submitted),
                submitted.stream().map(ExamAttempt::getScore).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null),
                submitted.stream().map(ExamAttempt::getScore).filter(Objects::nonNull)
                        .min(Comparator.naturalOrder()).orElse(null),
                averageSeconds(submitted),
                questions,
                topicRollup(questions));
    }

    // -----------------------------------------------------------------
    // Per question
    // -----------------------------------------------------------------

    private List<ExamAnalyticsResponse.QuestionStat> questionStats(Exam exam,
                                                                   boolean includeTesters) {
        List<ExamQuestion> questions = questionRepository.findBank(ExamSets.bankExamId(exam));
        if (questions.isEmpty()) {
            return List.of();
        }

        Map<Long, long[]> stats = new LinkedHashMap<>();
        for (Object[] row : answerRepository.questionStats(exam.getId(), includeTesters)) {
            stats.put(((Number) row[0]).longValue(),
                    new long[] {((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
        }

        Map<Long, Integer> askedPerSet = new LinkedHashMap<>();
        for (Object[] row : attemptRepository.submittedCountPerSet(exam.getId(), includeTesters)) {
            askedPerSet.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }

        Map<Long, String[]> commonestWrong = commonestWrong(exam.getId(), includeTesters);

        List<ExamAnalyticsResponse.QuestionStat> rows = new ArrayList<>();
        for (ExamQuestion question : questions) {
            long[] counts = stats.getOrDefault(question.getId(), new long[] {0, 0});
            int answered = (int) counts[0];
            int correct = (int) counts[1];
            // How many papers carried the question, not how many answer rows
            // exist: somebody who never touched it leaves no row, and counting
            // only rows turns "nobody could answer this" into a perfect score.
            int asked = Math.max(
                    askedPerSet.getOrDefault(question.getQuestionSet().getId(), 0), answered);
            String[] wrong = commonestWrong.get(question.getId());

            rows.add(new ExamAnalyticsResponse.QuestionStat(
                    question.getId(), question.getQuestionSet().getSetCode(),
                    question.getQuestionNumber(), question.getTopic(), question.getDifficulty(),
                    question.getQuestionText(), question.correctLabels(),
                    asked, answered, asked - answered, correct,
                    // Out of everybody who was asked: a question twenty people
                    // skipped is a question twenty people could not answer.
                    rate(correct, asked),
                    wrong == null ? null : wrong[0],
                    wrong == null ? null : textOf(question, wrong[0]),
                    wrong == null ? 0 : Integer.parseInt(wrong[1])));
        }

        // Worst first. The list is read to decide what to teach next, so the
        // question thirty-one people got wrong belongs at the top of it.
        rows.sort(Comparator
                .comparing(ExamAnalyticsResponse.QuestionStat::correctRate,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ExamAnalyticsResponse.QuestionStat::setCode)
                .thenComparingInt(ExamAnalyticsResponse.QuestionStat::questionNumber));
        return rows;
    }

    /**
     * The wrong answer most people gave — the distractor that is working.
     *
     * <p>The spread query returns rows already ordered by count, so the first
     * row for a question is its winner.
     */
    private Map<Long, String[]> commonestWrong(Long examId, boolean includeTesters) {
        Map<Long, String[]> commonest = new LinkedHashMap<>();
        for (Object[] row : answerRepository.wrongAnswerSpread(examId, includeTesters)) {
            commonest.putIfAbsent(((Number) row[0]).longValue(),
                    new String[] {String.valueOf(row[1]),
                            String.valueOf(((Number) row[2]).intValue())});
        }
        return commonest;
    }

    /**
     * A multi-select wrong answer is a combination such as {@code A,C}; naming
     * the whole combination is more useful than naming one of its halves.
     */
    private String textOf(ExamQuestion question, String labels) {
        List<String> texts = new ArrayList<>();
        for (String label : labels.split(",")) {
            question.getOptions().stream()
                    .filter(option -> option.getOptionLabel().equals(label.trim()))
                    .map(ExamQuestionOption::getOptionText)
                    .findFirst()
                    .ifPresent(texts::add);
        }
        return texts.isEmpty() ? null : String.join(" + ", texts);
    }

    private List<ExamAnalyticsResponse.TopicStat> topicRollup(
            List<ExamAnalyticsResponse.QuestionStat> questions) {
        Map<String, int[]> byTopic = new LinkedHashMap<>();
        for (ExamAnalyticsResponse.QuestionStat question : questions) {
            // A set nobody was dealt contributes no evidence about its topic.
            // Counting its questions would drag the topic to 0% and send
            // training after something that was never tested.
            if (question.asked() == 0) {
                continue;
            }
            String topic = question.topic() == null || question.topic().isBlank()
                    ? "Untagged" : question.topic();
            int[] totals = byTopic.computeIfAbsent(topic, key -> new int[3]);
            totals[0]++;
            totals[1] += question.asked();
            totals[2] += question.correct();
        }

        List<ExamAnalyticsResponse.TopicStat> rows = new ArrayList<>();
        byTopic.forEach((topic, totals) -> rows.add(new ExamAnalyticsResponse.TopicStat(
                topic, totals[0], totals[1], totals[2], rate(totals[2], totals[1]))));
        rows.sort(Comparator.comparing(ExamAnalyticsResponse.TopicStat::correctRate,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    // -----------------------------------------------------------------
    // Arithmetic
    // -----------------------------------------------------------------

    private Map<Long, ExamAttempt> attemptsOf(List<ExamParticipant> roster) {
        if (roster.isEmpty()) {
            return Map.of();
        }
        Map<Long, ExamAttempt> byParticipant = new LinkedHashMap<>();
        attemptRepository.findByParticipantIdIn(roster.stream()
                        .map(ExamParticipant::getId).toList())
                .forEach(attempt -> byParticipant.put(attempt.getParticipant().getId(), attempt));
        return byParticipant;
    }

    /** Still assigned, or settled as an absentee when the window closed. */
    private boolean neverSat(ExamParticipant participant) {
        return participant.getStatus() == ExamParticipantStatus.NOT_ATTEMPTED
                || participant.getStatus() == ExamParticipantStatus.ASSIGNED;
    }

    private long averageSeconds(List<ExamAttempt> submitted) {
        List<Long> durations = submitted.stream()
                .filter(a -> a.getStartedAt() != null && a.getSubmittedAt() != null)
                .map(a -> Duration.between(a.getStartedAt(), a.getSubmittedAt()).toSeconds())
                .toList();
        return durations.isEmpty() ? 0
                : durations.stream().mapToLong(Long::longValue).sum() / durations.size();
    }

    private BigDecimal averageScore(List<ExamAttempt> submitted) {
        int[] scores = submitted.stream().map(ExamAttempt::getScore).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).toArray();
        if (scores.length == 0) {
            return BigDecimal.ZERO;
        }
        long sum = 0;
        for (int score : scores) {
            sum += score;
        }
        return BigDecimal.valueOf(sum)
                .divide(BigDecimal.valueOf(scores.length), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal averagePercentage(List<ExamAttempt> submitted) {
        List<BigDecimal> values = submitted.stream().map(ExamAttempt::getPercentage)
                .filter(Objects::nonNull).toList();
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * Null when there is nothing to divide by, and deliberately not zero.
     *
     * <p>A question nobody was asked has no correct rate — it is unmeasured,
     * not failed. Reporting it as 0% would sort forty untaken questions to the
     * top of a list whose entire job is to name the next training session,
     * which is the same kind of quiet data lie this module exists to avoid.
     */
    private BigDecimal rate(long part, long whole) {
        return whole == 0 ? null
                : BigDecimal.valueOf(part).multiply(HUNDRED)
                        .divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
    }
}
