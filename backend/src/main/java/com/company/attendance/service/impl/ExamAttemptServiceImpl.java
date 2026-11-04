package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.exam.AnswerSaveRequest;
import com.company.attendance.dto.response.exam.ExamAttemptOption;
import com.company.attendance.dto.response.exam.ExamAttemptQuestion;
import com.company.attendance.dto.response.exam.ExamAttemptResponse;
import com.company.attendance.dto.response.exam.ExamResultOption;
import com.company.attendance.dto.response.exam.ExamResultQuestion;
import com.company.attendance.dto.response.exam.ExamResultResponse;
import com.company.attendance.entity.Exam;
import com.company.attendance.entity.ExamAnswer;
import com.company.attendance.entity.ExamAttempt;
import com.company.attendance.entity.ExamParticipant;
import com.company.attendance.entity.ExamQuestion;
import com.company.attendance.entity.ExamQuestionOption;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.ExamParticipantStatus;
import com.company.attendance.enums.ExamQuestionType;
import com.company.attendance.enums.ExamStatus;
import com.company.attendance.enums.ExamSubmissionSource;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.ExamAnswerRepository;
import com.company.attendance.repository.ExamAttemptRepository;
import com.company.attendance.repository.ExamParticipantRepository;
import com.company.attendance.repository.ExamQuestionRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.ExamAttemptService;
import com.company.attendance.service.exam.ExamAttemptStarter;
import com.company.attendance.service.exam.ExamScoringService;
import com.company.attendance.service.exam.ExamWindowService;
import com.company.attendance.util.PaperLayout;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** See {@link ExamAttemptService}. */
@Service
public class ExamAttemptServiceImpl implements ExamAttemptService {

    private static final Logger log = LoggerFactory.getLogger(ExamAttemptServiceImpl.class);

    private final ExamParticipantRepository participantRepository;
    private final ExamAttemptRepository attemptRepository;
    private final ExamAnswerRepository answerRepository;
    private final ExamQuestionRepository questionRepository;
    private final ExamAttemptStarter attemptStarter;
    private final ExamScoringService scoringService;
    private final ExamWindowService windowService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * Not {@code Random}: the option order a candidate is dealt must not be
     * reproducible by anybody who knows when their attempt started.
     */
    private final SecureRandom random = new SecureRandom();

    public ExamAttemptServiceImpl(ExamParticipantRepository participantRepository,
                                  ExamAttemptRepository attemptRepository,
                                  ExamAnswerRepository answerRepository,
                                  ExamQuestionRepository questionRepository,
                                  ExamAttemptStarter attemptStarter,
                                  ExamScoringService scoringService,
                                  ExamWindowService windowService,
                                  AuditLogService auditLogService,
                                  ObjectMapper objectMapper) {
        this.participantRepository = participantRepository;
        this.attemptRepository = attemptRepository;
        this.answerRepository = answerRepository;
        this.questionRepository = questionRepository;
        this.attemptStarter = attemptStarter;
        this.scoringService = scoringService;
        this.windowService = windowService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------
    // Start
    // -----------------------------------------------------------------

    /**
     * Starts the attempt, or hands back the one that is already running.
     *
     * <p>Idempotent by the unique key rather than by checking first: two taps
     * on Start arrive together, both see nothing, and only the database can
     * settle which of them wins. Same pattern as punch-in.
     */
    @Override
    @Transactional
    public ExamAttemptResponse start(UserPrincipal principal, Long examId) {
        ExamParticipant participant = requireParticipant(examId, principal);
        Exam exam = windowService.settle(participant.getExam());
        Instant now = Instant.now();

        Optional<ExamAttempt> existing =
                attemptRepository.findByParticipantIdAndAttemptNumber(participant.getId(), 1);
        if (existing.isPresent()) {
            return paper(existing.get(), participant, exam, now);
        }

        assertCanStart(exam, now);

        List<ExamQuestion> questions =
                questionRepository.findPaper(participant.getAssignedSet().getId());
        if (questions.isEmpty()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "This paper has no questions. Tell whoever set the exam.");
        }

        PaperLayout layout = layoutFor(exam, questions);

        ExamAttempt attempt = new ExamAttempt();
        attempt.setParticipant(participant);
        attempt.setAttemptNumber(1);
        attempt.setStartedAt(now);
        // The only clock that counts, and it never extends: whichever runs out
        // first, the duration or the window, ends the attempt.
        attempt.setExpiresAt(earlier(now.plus(Duration.ofMinutes(exam.getDurationMinutes())),
                exam.getWindowEnd()));
        attempt.setQuestionOrder(layout.questionOrderJson(objectMapper));
        attempt.setOptionOrder(layout.optionOrderJson(objectMapper));
        attempt.setMaxScore(questions.stream().mapToInt(ExamQuestion::getMarks).sum());

        ExamAttempt saved = attemptStarter.insert(attempt)
                .orElseGet(() -> attemptRepository
                        .findByParticipantIdAndAttemptNumber(participant.getId(), 1)
                        .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                                "The attempt could not be started. Try again.")));

        participant.setStatus(ExamParticipantStatus.IN_PROGRESS);
        participantRepository.save(participant);

        log.info("Exam {} started by employee {} on set {}", exam.getExamCode(),
                participant.getEmployee().getId(), participant.getAssignedSet().getSetCode());

        return paper(saved, participant, exam, now);
    }

    @Override
    @Transactional(readOnly = true)
    public ExamAttemptResponse resume(UserPrincipal principal, Long attemptId) {
        ExamAttempt attempt = requireOwnAttempt(principal, attemptId);
        return paper(attempt, attempt.getParticipant(), attempt.getParticipant().getExam(),
                Instant.now());
    }

    // -----------------------------------------------------------------
    // Autosave
    // -----------------------------------------------------------------

    /**
     * Records one answer as it is given.
     *
     * <p>Nothing is marked yet: correctness is computed at submission from the
     * stored key, so an answer row carries no hint of itself while the paper is
     * still open.
     */
    @Override
    @Transactional
    public void saveAnswer(UserPrincipal principal, Long attemptId, AnswerSaveRequest request) {
        ExamAttempt attempt = requireOwnAttempt(principal, attemptId);
        Instant now = Instant.now();

        if (attempt.isSubmitted()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "This paper has been handed in and can no longer be changed.");
        }
        if (attempt.hasExpiredAt(now)) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Your time is up. The paper will be submitted as it stood.");
        }

        ExamQuestion question = questionRepository.findById(request.questionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "That question does not exist."));
        assertQuestionBelongsToPaper(attempt, question);

        PaperLayout layout = layoutOf(attempt);
        List<String> canonical = layout.canonicalFor(question.getId(), request.selectedLabels());

        ExamAnswer answer = answerRepository
                .findByAttemptIdAndQuestionId(attempt.getId(), question.getId())
                .orElseGet(ExamAnswer::new);
        answer.setAttempt(attempt);
        answer.setQuestion(question);
        answer.setSelectedOptions(canonical.isEmpty() ? null : String.join(",", canonical));
        answer.setAnsweredAt(now);
        answerRepository.save(answer);
    }

    // -----------------------------------------------------------------
    // Submit
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public ExamResultResponse submit(UserPrincipal principal, Long attemptId) {
        ExamAttempt attempt = requireOwnAttempt(principal, attemptId);
        Instant now = Instant.now();

        if (attempt.isSubmitted()) {
            // A second tap on Submit is not an error worth showing somebody;
            // it is the same paper, already handed in.
            return result(attempt, feedbackVisible(attempt, now));
        }

        if (attempt.hasExpiredAt(now)) {
            // Marked as it stood at expiry, and recorded as AUTO_TIMEOUT rather
            // than SELF so the record does not claim they handed it in on time.
            //
            // Deliberately not an exception. Throwing here would roll back the
            // scoring in the same transaction and lose the result entirely —
            // and there is nothing late to reject in any case, because answers
            // arrive by autosave and saveAnswer already refuses them past the
            // clock. Submitting late gains nobody a second.
            scoringService.score(attempt, ExamSubmissionSource.AUTO_TIMEOUT,
                    attempt.getExpiresAt());
        } else {
            scoringService.score(attempt, ExamSubmissionSource.SELF, now);
        }

        auditLogService.record(AuditAction.EXAM_SUBMIT, "ExamAttempt", attempt.getId(),
                "Submitted " + attempt.getParticipant().getExam().getExamCode()
                        + " set " + attempt.getParticipant().getAssignedSet().getSetCode(),
                null,
                Map.of("score", attempt.getScore(),
                        "maxScore", attempt.getMaxScore(),
                        "passed", Boolean.TRUE.equals(attempt.getPassed()),
                        "source", String.valueOf(attempt.getSubmissionSource())));

        return result(attempt, feedbackVisible(attempt, Instant.now()));
    }

    // -----------------------------------------------------------------
    // Result
    // -----------------------------------------------------------------

    /**
     * The result and the worked answers — a different endpoint from the paper
     * on purpose: this one refuses outright until the attempt is in.
     */
    @Override
    @Transactional(readOnly = true)
    public ExamResultResponse result(UserPrincipal principal, Long attemptId) {
        ExamAttempt attempt = requireOwnAttempt(principal, attemptId);
        if (!attempt.isSubmitted()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "The result is available once you submit the paper.");
        }
        return result(attempt, feedbackVisible(attempt, Instant.now()));
    }

    private boolean feedbackVisible(ExamAttempt attempt, Instant now) {
        Exam exam = attempt.getParticipant().getExam();
        return switch (exam.getFeedbackVisibility()) {
            case IMMEDIATE -> true;
            case AFTER_CLOSE -> exam.getStatus() == ExamStatus.CLOSED
                    || now.isAfter(exam.getWindowEnd());
            case NEVER -> false;
        };
    }

    private ExamResultResponse result(ExamAttempt attempt, boolean withFeedback) {
        Exam exam = attempt.getParticipant().getExam();
        PaperLayout layout = layoutOf(attempt);
        List<ExamQuestion> questions = orderedQuestions(attempt, layout);
        Map<Long, ExamAnswer> answers = answersOf(attempt);

        List<ExamResultQuestion> review = new ArrayList<>();
        int correctCount = 0;
        int display = 1;

        for (ExamQuestion question : questions) {
            ExamAnswer answer = answers.get(question.getId());
            if (answer != null && answer.isCorrect()) {
                correctCount++;
            }
            if (withFeedback) {
                review.add(reviewOf(question, answer, layout, display));
            }
            display++;
        }

        long seconds = attempt.getSubmittedAt() == null ? 0
                : Duration.between(attempt.getStartedAt(), attempt.getSubmittedAt()).toSeconds();

        return new ExamResultResponse(
                attempt.getId(), exam.getId(), exam.getTitle(),
                attempt.getParticipant().getAssignedSet().getSetCode(),
                attempt.getScore() == null ? 0 : attempt.getScore(),
                attempt.getMaxScore() == null ? 0 : attempt.getMaxScore(),
                attempt.getPercentage(), exam.getPassMarks(),
                Boolean.TRUE.equals(attempt.getPassed()), correctCount, questions.size(),
                attempt.getStartedAt(), attempt.getSubmittedAt(), seconds,
                attempt.getSubmissionSource(), withFeedback, review);
    }

    private ExamResultQuestion reviewOf(ExamQuestion question, ExamAnswer answer,
                                        PaperLayout layout, int displayNumber) {
        List<String> chosen = answer == null ? List.of() : answer.selectedLabels();

        List<ExamResultOption> options = new ArrayList<>();
        for (String canonical : displayOrder(question, layout)) {
            question.getOptions().stream()
                    .filter(option -> option.getOptionLabel().equals(canonical))
                    .findFirst()
                    .ifPresent(option -> options.add(new ExamResultOption(
                            layout.displayedFor(question.getId(), canonical),
                            option.getOptionText(),
                            option.isCorrect(),
                            chosen.contains(canonical))));
        }

        return new ExamResultQuestion(
                question.getId(), displayNumber, question.getQuestionText(), question.getTopic(),
                question.getMarks(), answer == null ? 0 : answer.getMarksAwarded(),
                answer != null && answer.isCorrect(), chosen.isEmpty(), options,
                chosen.stream()
                        .map(label -> layout.displayedFor(question.getId(), label))
                        .filter(Objects::nonNull)
                        .toList(),
                question.correctLabels().stream()
                        .map(label -> layout.displayedFor(question.getId(), label))
                        .filter(Objects::nonNull)
                        .toList(),
                question.getExplanation(), question.getSourceReference());
    }

    // -----------------------------------------------------------------
    // The paper
    // -----------------------------------------------------------------

    private ExamAttemptResponse paper(ExamAttempt attempt, ExamParticipant participant,
                                      Exam exam, Instant now) {
        PaperLayout layout = layoutOf(attempt);
        List<ExamQuestion> questions = orderedQuestions(attempt, layout);
        Map<Long, ExamAnswer> answers = answersOf(attempt);

        List<ExamAttemptQuestion> paper = new ArrayList<>();
        int display = 1;
        int totalMarks = 0;
        for (ExamQuestion question : questions) {
            totalMarks += question.getMarks();
            paper.add(sittingView(question, answers.get(question.getId()), layout, display++));
        }

        long remaining = Math.max(0, Duration.between(now, attempt.getExpiresAt()).toSeconds());

        return new ExamAttemptResponse(
                attempt.getId(), exam.getId(), exam.getTitle(), exam.getInstructions(),
                participant.getAssignedSet().getSetCode(), questions.size(), totalMarks,
                exam.getPassMarks(), exam.getDurationMinutes(), attempt.getStartedAt(),
                attempt.getExpiresAt(), remaining, attempt.isSubmitted(), paper);
    }

    /**
     * Builds the sitting view.
     *
     * <p>Nothing in this method can reach an explanation, a source or a
     * correctness flag, because {@link ExamAttemptQuestion} has nowhere to put
     * them. That is the point: the rule is enforced by the type, not by this
     * method remembering.
     */
    private ExamAttemptQuestion sittingView(ExamQuestion question, ExamAnswer answer,
                                            PaperLayout layout, int displayNumber) {
        List<ExamAttemptOption> options = new ArrayList<>();
        char label = 'A';
        for (String canonical : displayOrder(question, layout)) {
            for (ExamQuestionOption option : question.getOptions()) {
                if (option.getOptionLabel().equals(canonical)) {
                    options.add(new ExamAttemptOption(String.valueOf(label++),
                            option.getOptionText()));
                    break;
                }
            }
        }

        List<String> selected = answer == null ? List.of()
                : answer.selectedLabels().stream()
                        .map(canonical -> layout.displayedFor(question.getId(), canonical))
                        .filter(Objects::nonNull)
                        .toList();

        return new ExamAttemptQuestion(question.getId(), displayNumber, question.getQuestionText(),
                question.getTopic(), question.getMarks(),
                question.getQuestionType() == ExamQuestionType.MULTI_CHOICE,
                options, selected);
    }

    /** The permutation, generated once per attempt and then never again. */
    private PaperLayout layoutFor(Exam exam, List<ExamQuestion> questions) {
        List<ExamQuestion> ordered = new ArrayList<>(questions);
        if (exam.isShuffleQuestions()) {
            Collections.shuffle(ordered, random);
        }

        Map<Long, List<String>> optionOrder = new LinkedHashMap<>();
        for (ExamQuestion question : ordered) {
            List<String> labels = new ArrayList<>(question.getOptions().stream()
                    .map(ExamQuestionOption::getOptionLabel).toList());
            if (exam.isShuffleOptions()) {
                // The structural defence that works even when nobody reads the
                // quality report: a paper where the longest option is usually
                // right stops being beatable by position.
                Collections.shuffle(labels, random);
            }
            optionOrder.put(question.getId(), labels);
        }
        return new PaperLayout(ordered.stream().map(ExamQuestion::getId).toList(), optionOrder);
    }

    private PaperLayout layoutOf(ExamAttempt attempt) {
        return PaperLayout.from(objectMapper, attempt.getQuestionOrder(), attempt.getOptionOrder());
    }

    /**
     * The option labels in this candidate's own order, falling back to the
     * canonical order for an attempt whose layout predates shuffling.
     */
    private List<String> displayOrder(ExamQuestion question, PaperLayout layout) {
        return layout.optionOrder().getOrDefault(question.getId(),
                question.getOptions().stream().map(ExamQuestionOption::getOptionLabel).toList());
    }

    private List<ExamQuestion> orderedQuestions(ExamAttempt attempt, PaperLayout layout) {
        Map<Long, ExamQuestion> byId = new LinkedHashMap<>();
        questionRepository.findPaper(attempt.getParticipant().getAssignedSet().getId())
                .forEach(question -> byId.put(question.getId(), question));

        List<ExamQuestion> ordered = new ArrayList<>();
        for (Long questionId : layout.questionOrder()) {
            ExamQuestion question = byId.remove(questionId);
            if (question != null) {
                ordered.add(question);
            }
        }
        // Anything the layout does not mention is still owed to the candidate.
        ordered.addAll(byId.values());
        return ordered;
    }

    private Map<Long, ExamAnswer> answersOf(ExamAttempt attempt) {
        Map<Long, ExamAnswer> answers = new LinkedHashMap<>();
        answerRepository.findByAttemptId(attempt.getId())
                .forEach(answer -> answers.put(answer.getQuestion().getId(), answer));
        return answers;
    }

    // -----------------------------------------------------------------
    // Guards
    // -----------------------------------------------------------------

    private void assertCanStart(Exam exam, Instant now) {
        if (exam.getStatus() == ExamStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION, "That exam was cancelled.");
        }
        if (!exam.getStatus().isLive()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    exam.getStatus() == ExamStatus.CLOSED
                            ? "That exam has closed." : "That exam is not open yet.");
        }
        if (now.isBefore(exam.getWindowStart())) {
            throw new BusinessException(ErrorCode.OUTSIDE_PUNCH_WINDOW,
                    "That exam has not opened yet.");
        }
        if (!now.isBefore(exam.getWindowEnd())) {
            throw new BusinessException(ErrorCode.OUTSIDE_PUNCH_WINDOW, "That exam has closed.");
        }
        if (!exam.acceptsNewAttemptsAt(now)) {
            // Starting five minutes before the window shuts would give five
            // minutes for a twenty-five minute paper, which is arithmetically
            // right and reliably produces a complaint.
            throw new BusinessException(ErrorCode.OUTSIDE_PUNCH_WINDOW,
                    "The exam closes in less than " + exam.getEntryClosesBeforeMinutes()
                            + " minutes, which is not long enough to sit it. Ask for a retake.");
        }
    }

    private void assertQuestionBelongsToPaper(ExamAttempt attempt, ExamQuestion question) {
        if (!question.getQuestionSet().getId()
                .equals(attempt.getParticipant().getAssignedSet().getId())) {
            // Answering a question from a set you were not dealt is either a bug
            // or an attempt to learn which questions the other papers hold.
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "That question is not on your paper.");
        }
    }

    private ExamParticipant requireParticipant(Long examId, UserPrincipal principal) {
        return participantRepository
                .findByExamIdAndEmployeeId(examId, requireEmployeeId(principal))
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED,
                        "You are not on the list for that exam."));
    }

    /** Somebody else's paper is not readable, whatever the id in the URL says. */
    private ExamAttempt requireOwnAttempt(UserPrincipal principal, Long attemptId) {
        ExamAttempt attempt = attemptRepository.findById(attemptId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "That attempt does not exist."));

        if (!attempt.getParticipant().getEmployee().getId().equals(requireEmployeeId(principal))) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "That is not your attempt.");
        }
        return attempt;
    }

    private Long requireEmployeeId(UserPrincipal principal) {
        if (principal == null || principal.getEmployeeId() == null) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "This account is not linked to an employee record.");
        }
        return principal.getEmployeeId();
    }

    private Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
