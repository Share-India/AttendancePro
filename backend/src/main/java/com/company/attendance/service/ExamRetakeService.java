package com.company.attendance.service;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.exam.RetakeRequest;
import com.company.attendance.dto.response.exam.ExamRetakeResponse;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Exam;
import com.company.attendance.entity.ExamAttempt;
import com.company.attendance.entity.ExamAudience;
import com.company.attendance.entity.ExamParticipant;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.ExamAudienceScope;
import com.company.attendance.enums.ExamParticipantKind;
import com.company.attendance.enums.ExamParticipantStatus;
import com.company.attendance.enums.ExamStatus;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.ExamAttemptRepository;
import com.company.attendance.repository.ExamAudienceRepository;
import com.company.attendance.repository.ExamParticipantRepository;
import com.company.attendance.repository.ExamQuestionSetRepository;
import com.company.attendance.repository.ExamRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.exam.ExamWindowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Opening the one retake somebody gets.
 *
 * <p>A retake is a <em>child exam</em> over the same sixty questions, not a
 * second attempt row on the original. That shape is what makes the unseen-set
 * rule enforceable: the cohort, the window and the set each person is dealt are
 * all recorded against the retake, while the question bank — and therefore the
 * record of which sets each person has already opened — stays attached to the
 * original.
 *
 * <p>Who is on it is decided here and written down. Everything after that is
 * the ordinary publish path in {@code ExamServiceImpl}, including the
 * unseen-set assignment, so a retake cannot be published by a route that skips
 * the rule.
 */
@Service
public class ExamRetakeService {

    private static final Logger log = LoggerFactory.getLogger(ExamRetakeService.class);

    private final ExamRepository examRepository;
    private final ExamParticipantRepository participantRepository;
    private final ExamAttemptRepository attemptRepository;
    private final ExamAudienceRepository audienceRepository;
    private final ExamQuestionSetRepository questionSetRepository;
    private final ExamWindowService windowService;
    private final AuditLogService auditLogService;

    public ExamRetakeService(ExamRepository examRepository,
                             ExamParticipantRepository participantRepository,
                             ExamAttemptRepository attemptRepository,
                             ExamAudienceRepository audienceRepository,
                             ExamQuestionSetRepository questionSetRepository,
                             ExamWindowService windowService,
                             AuditLogService auditLogService) {
        this.examRepository = examRepository;
        this.participantRepository = participantRepository;
        this.attemptRepository = attemptRepository;
        this.audienceRepository = audienceRepository;
        this.questionSetRepository = questionSetRepository;
        this.windowService = windowService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public ExamRetakeResponse open(UserPrincipal principal, Long examId, RetakeRequest request) {
        Exam original = windowService.settle(examRepository.findById(examId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "That exam does not exist.")));

        assertRetakeable(original, request);

        Cohort cohort = cohortFor(original);
        if (cohort.employees().isEmpty()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Everybody who sat " + original.getExamCode() + " passed, and nobody missed "
                            + "it. There is nobody to retake it.");
        }

        Exam retake = examRepository.save(copyOf(original, request));
        writeAudience(retake, cohort.employees());

        auditLogService.record(AuditAction.EXAM_RETAKE_OPEN, "Exam", retake.getId(),
                "Opened retake " + retake.getExamCode() + " of " + original.getExamCode(), null,
                Map.of("parentExam", original.getExamCode(),
                        "cohort", cohort.employees().size(),
                        "failed", cohort.failed(),
                        "neverSat", cohort.neverSat()));

        log.info("Retake {} opened for {} ({} failed, {} never sat)", retake.getExamCode(),
                original.getExamCode(), cohort.failed(), cohort.neverSat());

        return new ExamRetakeResponse(retake.getId(), retake.getExamCode(), retake.getTitle(),
                cohort.employees().size(), cohort.failed(), cohort.neverSat(), cohort.excluded(),
                cohort.employees().size() + " people are on the retake. Review it and publish "
                        + "when you are ready — each of them will be dealt a set they have not "
                        + "seen.");
    }

    // -----------------------------------------------------------------
    // The cohort
    // -----------------------------------------------------------------

    /** Who qualifies, and who was left out and why. */
    private record Cohort(Set<Employee> employees, int failed, int neverSat,
                          List<ExamRetakeResponse.Excluded> excluded) {
    }

    private Cohort cohortFor(Exam original) {
        List<ExamParticipant> roster = participantRepository.findRoster(original.getId());
        Map<Long, ExamAttempt> attempts = new LinkedHashMap<>();
        if (!roster.isEmpty()) {
            attemptRepository.findByParticipantIdIn(
                            roster.stream().map(ExamParticipant::getId).toList())
                    .forEach(attempt -> attempts.put(attempt.getParticipant().getId(), attempt));
        }

        Set<Employee> cohort = new LinkedHashSet<>();
        List<ExamRetakeResponse.Excluded> excluded = new ArrayList<>();
        int failed = 0;
        int neverSat = 0;

        for (ExamParticipant participant : roster) {
            Employee employee = participant.getEmployee();
            ExamAttempt attempt = attempts.get(participant.getId());
            boolean passed = attempt != null && Boolean.TRUE.equals(attempt.getPassed());

            if (participant.getParticipantKind() == ExamParticipantKind.TESTER) {
                // Their result is already excluded from every figure, because
                // they sat it having read the answers. Re-testing them on a
                // second paper they also wrote would measure nothing either.
                excluded.add(new ExamRetakeResponse.Excluded(employee.getId(),
                        employee.getFullName(), "wrote the paper, so is not being re-tested"));
                continue;
            }
            if (!participant.getStatus().eligibleForRetake(passed)) {
                excluded.add(new ExamRetakeResponse.Excluded(employee.getId(),
                        employee.getFullName(),
                        participant.getStatus() == ExamParticipantStatus.IN_PROGRESS
                                ? "still has the original open"
                                : "passed with " + attempt.getScore() + " marks"));
                continue;
            }
            if (participantRepository.countRetakesOf(original.getId(), employee.getId()) > 0) {
                // The limit is two sittings. A third would need a fourth
                // question set, and there are only three.
                excluded.add(new ExamRetakeResponse.Excluded(employee.getId(),
                        employee.getFullName(),
                        "has already been given a retake of this exam"));
                continue;
            }

            cohort.add(employee);
            if (participant.getStatus() == ExamParticipantStatus.SUBMITTED) {
                failed++;
            } else {
                neverSat++;
            }
        }
        return new Cohort(cohort, failed, neverSat, excluded);
    }

    // -----------------------------------------------------------------
    // The child exam
    // -----------------------------------------------------------------

    private void assertRetakeable(Exam original, RetakeRequest request) {
        if (original.isRetake()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "This is already a retake. One retake is the limit — a third sitting would "
                            + "need a fourth question set, and there are only three.");
        }
        if (original.getStatus() == ExamStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "That exam was cancelled, so there is nothing to retake.");
        }
        List<Exam> openAlready = examRepository.findByParentExamId(original.getId()).stream()
                .filter(child -> child.getStatus() != ExamStatus.CANCELLED)
                .toList();
        if (!openAlready.isEmpty()) {
            // Checked on the exam, not only per person. The per-person guard
            // below reads exam_participants, which do not exist until a retake
            // is published — so without this, two retakes could be opened side
            // by side and both published, and the second would have no unseen
            // set left to deal.
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    original.getExamCode() + " already has a retake ("
                            + openAlready.get(0).getExamCode() + "). One retake is the limit; "
                            + "cancel that one first if it was opened by mistake.");
        }
        if (original.getStatus() != ExamStatus.CLOSED) {
            // Opening a retake while the original is still running would put
            // somebody on a second paper before the first one is marked.
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Wait until " + original.getExamCode() + " has closed. Who needs a retake is "
                            + "not settled until everybody has either sat it or missed it.");
        }
        if (!request.windowEnd().isAfter(request.windowStart())) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "The retake has to close after it opens.");
        }
        if (!request.windowStart().isAfter(original.getWindowEnd())) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "The retake has to open after the original closed.");
        }
        if (questionSetRepository.findByExamIdOrderBySetCodeAsc(original.getId()).isEmpty()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "The original exam has no question sets to retake from.");
        }
    }

    /**
     * The child exam.
     *
     * <p>Everything about how the paper is marked is copied, not re-entered: a
     * retake scored differently from the exam it retakes is not a retake of it.
     *
     * <p>It starts at QUESTIONS_LOADED rather than DRAFT because its questions
     * are already loaded — they are the original's.
     */
    private Exam copyOf(Exam original, RetakeRequest request) {
        Exam retake = new Exam();
        retake.setExamCode(nextCode(original));
        retake.setTitle(request.title() == null || request.title().isBlank()
                ? original.getTitle() + " (retake)" : request.title().trim());
        retake.setDescription(original.getDescription());
        retake.setInstructions(original.getInstructions());
        retake.setStatus(ExamStatus.QUESTIONS_LOADED);
        retake.setParentExam(original);
        retake.setWindowStart(request.windowStart());
        retake.setWindowEnd(request.windowEnd());
        retake.setDurationMinutes(request.durationMinutes() == null
                ? original.getDurationMinutes() : request.durationMinutes());
        retake.setEntryClosesBeforeMinutes(original.getEntryClosesBeforeMinutes());
        retake.setMarksPerQuestion(original.getMarksPerQuestion());
        retake.setNegativeMarks(original.getNegativeMarks());
        retake.setTotalMarks(original.getTotalMarks());
        retake.setPassMarks(original.getPassMarks());
        retake.setShuffleQuestions(original.isShuffleQuestions());
        retake.setShuffleOptions(original.isShuffleOptions());
        retake.setPartialCredit(original.isPartialCredit());
        retake.setFeedbackVisibility(original.getFeedbackVisibility());
        retake.setRetakeRequiresUnseenSet(original.isRetakeRequiresUnseenSet());
        return retake;
    }

    /** {@code PK-W42} becomes {@code PK-W42-R}, then {@code -R2} if that exists. */
    private String nextCode(Exam original) {
        String base = original.getExamCode() + "-R";
        if (!examRepository.existsByExamCodeIgnoreCase(base)) {
            return base;
        }
        for (int suffix = 2; suffix < 20; suffix++) {
            String candidate = base + suffix;
            if (!examRepository.existsByExamCodeIgnoreCase(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                "There are already nineteen retakes of " + original.getExamCode()
                        + ". Something has gone wrong.");
    }

    /**
     * The cohort is written as one audience rule per person rather than as a
     * scope.
     *
     * <p>Snapshotting at publish is the right default for a fresh exam, but a
     * retake cohort is a decision already taken — "the people who failed last
     * Tuesday" must not re-resolve into a different list on Friday.
     */
    private void writeAudience(Exam retake, Set<Employee> employees) {
        for (Employee employee : employees) {
            ExamAudience rule = new ExamAudience();
            rule.setExam(retake);
            rule.setScopeType(ExamAudienceScope.EMPLOYEE);
            rule.setScopeId(employee.getId());
            audienceRepository.save(rule);
        }
    }
}
