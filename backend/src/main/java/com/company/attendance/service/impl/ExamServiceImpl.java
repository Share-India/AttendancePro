package com.company.attendance.service.impl;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.request.exam.ExamCancelRequest;
import com.company.attendance.dto.request.exam.ExamRequest;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.exam.ExamParticipantResponse;
import com.company.attendance.dto.response.exam.ExamPublishResponse;
import com.company.attendance.dto.response.exam.ExamQuestionReviewResponse;
import com.company.attendance.dto.response.exam.ExamResponse;
import com.company.attendance.dto.response.exam.ExamSummaryResponse;
import com.company.attendance.dto.response.exam.MyExamResponse;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.Exam;
import com.company.attendance.entity.ExamAttempt;
import com.company.attendance.entity.ExamAudience;
import com.company.attendance.entity.ExamParticipant;
import com.company.attendance.entity.ExamQuestion;
import com.company.attendance.entity.ExamQuestionSet;
import com.company.attendance.enums.AccessScope;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.ExamAudienceScope;
import com.company.attendance.enums.ExamFeedbackVisibility;
import com.company.attendance.enums.ExamParticipantKind;
import com.company.attendance.enums.ExamParticipantStatus;
import com.company.attendance.enums.ExamStatus;
import com.company.attendance.enums.NotificationType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.DepartmentRepository;
import com.company.attendance.repository.DesignationRepository;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.ExamAttemptRepository;
import com.company.attendance.repository.ExamAudienceRepository;
import com.company.attendance.repository.ExamParticipantRepository;
import com.company.attendance.repository.ExamQuestionRepository;
import com.company.attendance.repository.ExamQuestionSetRepository;
import com.company.attendance.repository.ExamRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.security.AccessScopeResolver;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.ExamService;
import com.company.attendance.service.NotificationService;
import com.company.attendance.service.exam.ExamSets;
import com.company.attendance.service.exam.ExamWindowService;
import com.company.attendance.util.DateTimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Setting and running weekly study exams.
 *
 * <p>Three decisions shape this class.
 *
 * <p><strong>The roster is written down at publish, not computed on read.</strong>
 * Somebody who joins on Wednesday must not appear in Monday's participation
 * figure, and Monday's figure must not move when the org chart does.
 *
 * <p><strong>A retake is a child exam over the parent's question bank.</strong>
 * See {@link ExamSets}. It is what makes the unseen-set rule enforceable.
 *
 * <p><strong>Nothing is scheduled.</strong> The window is the authority and
 * {@link ExamWindowService} brings the status up to date on every read.
 */
@Service
public class ExamServiceImpl implements ExamService {

    private static final Logger log = LoggerFactory.getLogger(ExamServiceImpl.class);

    private static final String PERM_VIEW_ALL = "EXAM_RESULT_VIEW_ALL";
    private static final String PERM_VIEW_TEAM = "EXAM_RESULT_VIEW_TEAM";
    private static final String PERM_IMPORT = "EXAM_QUESTION_IMPORT";

    private static final int REQUIRED_SETS = 3;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, h:mm a");

    private final ExamRepository examRepository;
    private final ExamQuestionSetRepository questionSetRepository;
    private final ExamQuestionRepository questionRepository;
    private final ExamAudienceRepository audienceRepository;
    private final ExamParticipantRepository participantRepository;
    private final ExamAttemptRepository attemptRepository;
    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final DesignationRepository designationRepository;
    private final OfficeRepository officeRepository;
    private final UserRepository userRepository;
    private final AccessScopeResolver accessScopeResolver;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final ExamWindowService windowService;

    /**
     * Shuffling matters here in a way it does not in most places: the set
     * somebody is dealt must not be predictable from their employee id, and
     * their option order must not be reproducible by anybody else.
     */
    private final SecureRandom random = new SecureRandom();

    public ExamServiceImpl(ExamRepository examRepository,
                           ExamQuestionSetRepository questionSetRepository,
                           ExamQuestionRepository questionRepository,
                           ExamAudienceRepository audienceRepository,
                           ExamParticipantRepository participantRepository,
                           ExamAttemptRepository attemptRepository,
                           EmployeeRepository employeeRepository,
                           DepartmentRepository departmentRepository,
                           DesignationRepository designationRepository,
                           OfficeRepository officeRepository,
                           UserRepository userRepository,
                           AccessScopeResolver accessScopeResolver,
                           AuditLogService auditLogService,
                           NotificationService notificationService,
                           ExamWindowService windowService) {
        this.examRepository = examRepository;
        this.questionSetRepository = questionSetRepository;
        this.questionRepository = questionRepository;
        this.audienceRepository = audienceRepository;
        this.participantRepository = participantRepository;
        this.attemptRepository = attemptRepository;
        this.employeeRepository = employeeRepository;
        this.departmentRepository = departmentRepository;
        this.designationRepository = designationRepository;
        this.officeRepository = officeRepository;
        this.userRepository = userRepository;
        this.accessScopeResolver = accessScopeResolver;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
        this.windowService = windowService;
    }

    // -----------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public PageResponse<ExamSummaryResponse> search(UserPrincipal principal, String status,
                                                    Pageable pageable) {
        windowService.settleAllOverdue();

        Page<Exam> page = examRepository.search(parseStatus(status), pageable);
        return PageResponse.of(page, this::toSummary);
    }

    @Override
    @Transactional
    public ExamResponse get(UserPrincipal principal, Long examId) {
        return toResponse(windowService.settle(require(examId)), principal);
    }

    @Override
    @Transactional
    public List<ExamParticipantResponse> participants(UserPrincipal principal, Long examId) {
        Exam exam = windowService.settle(require(examId));

        AccessScope scope = accessScopeResolver.resolve(principal, PERM_VIEW_ALL, PERM_VIEW_TEAM);
        if (scope == AccessScope.SELF) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "Exam results are for managers and HR. Your own result is under My Exams.");
        }
        Optional<Set<Long>> visible = accessScopeResolver.visibleEmployeeIds(principal, scope);

        List<ExamParticipant> roster = participantRepository.findRoster(exam.getId()).stream()
                .filter(p -> visible.isEmpty() || visible.get().contains(p.getEmployee().getId()))
                .toList();
        if (roster.isEmpty()) {
            return List.of();
        }

        Map<Long, ExamAttempt> attempts = attemptsByParticipant(roster);

        List<ExamParticipantResponse> rows = new ArrayList<>();
        for (ExamParticipant participant : roster) {
            Employee employee = participant.getEmployee();
            ExamAttempt attempt = attempts.get(participant.getId());
            boolean done = attempt != null && attempt.isSubmitted();

            rows.add(new ExamParticipantResponse(
                    participant.getId(),
                    employee.getId(),
                    employee.getFullName(),
                    employee.getEmployeeCode(),
                    employee.getDepartment() == null ? null : employee.getDepartment().getName(),
                    participant.getAssignedSet().getSetCode(),
                    participant.getParticipantKind(),
                    participant.getStatus(),
                    attempt == null ? null : attempt.getId(),
                    done ? attempt.getScore() : null,
                    done ? attempt.getMaxScore() : null,
                    done ? attempt.getPercentage() : null,
                    done ? attempt.getPassed() : null,
                    attempt == null ? null : attempt.getStartedAt(),
                    done ? attempt.getSubmittedAt() : null,
                    done ? Duration.between(attempt.getStartedAt(), attempt.getSubmittedAt())
                            .toSeconds() : null,
                    done ? attempt.getSubmissionSource() : null));
        }
        rows.sort(Comparator.comparing(ExamParticipantResponse::employeeName,
                Comparator.nullsLast(String::compareToIgnoreCase)));
        return rows;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExamQuestionReviewResponse> questionReview(UserPrincipal principal, Long examId) {
        Exam exam = require(examId);

        // No scoping, deliberately: there is no safe partial view of an answer
        // key, so this is gated on the import permission at the endpoint and
        // then shown whole. A retake shows its parent's bank.
        Map<String, List<ExamQuestionReviewResponse.Question>> bySet = new LinkedHashMap<>();
        for (ExamQuestion question : questionRepository.findBank(ExamSets.bankExamId(exam))) {
            bySet.computeIfAbsent(question.getQuestionSet().getSetCode(), key -> new ArrayList<>())
                    .add(new ExamQuestionReviewResponse.Question(
                            question.getId(),
                            question.getQuestionNumber(),
                            question.getTopic(),
                            question.getDifficulty(),
                            question.getQuestionType(),
                            question.getQuestionText(),
                            question.getMarks(),
                            question.getExplanation(),
                            question.getSourceReference(),
                            question.correctLabels(),
                            question.getOptions().stream()
                                    .map(option -> new ExamQuestionReviewResponse.Option(
                                            option.getOptionLabel(),
                                            option.getOptionText(),
                                            option.isCorrect()))
                                    .toList()));
        }

        List<ExamQuestionReviewResponse> sets = new ArrayList<>();
        bySet.forEach((setCode, questions) -> {
            questions.sort(Comparator.comparingInt(
                    ExamQuestionReviewResponse.Question::questionNumber));
            sets.add(new ExamQuestionReviewResponse(setCode, questions.size(),
                    questions.stream()
                            .mapToInt(ExamQuestionReviewResponse.Question::marks).sum(),
                    questions));
        });
        sets.sort(Comparator.comparing(ExamQuestionReviewResponse::setCode));
        return sets;
    }

    /**
     * The exams one person has been given.
     *
     * <p>Built from their participant rows rather than from the exam list, so
     * somebody who joined after an exam was published simply does not see it —
     * the same snapshot the participation figures are counted from.
     */
    @Override
    @Transactional
    public List<MyExamResponse> myExams(UserPrincipal principal) {
        if (principal.getEmployeeId() == null) {
            return List.of();
        }

        List<ExamParticipant> mine = participantRepository.findMine(principal.getEmployeeId());
        if (mine.isEmpty()) {
            return List.of();
        }

        // Settle the ones whose window has moved on, so a candidate opening the
        // screen sees "closed" rather than a Start button that then refuses.
        mine.stream()
                .map(ExamParticipant::getExam)
                .filter(exam -> exam.getStatus().isLive())
                .distinct()
                .forEach(windowService::settle);

        Map<Long, ExamAttempt> attempts = attemptsByParticipant(mine);
        Instant now = Instant.now();

        return mine.stream()
                .map(participant -> myExam(participant, attempts.get(participant.getId()), now))
                .sorted(Comparator.comparing(MyExamResponse::windowStart).reversed())
                .toList();
    }

    // -----------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------

    @Override
    @Transactional
    public ExamResponse create(UserPrincipal principal, ExamRequest request) {
        String code = request.examCode().trim().toUpperCase(Locale.ROOT);
        if (examRepository.existsByExamCodeIgnoreCase(code)) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE,
                    "An exam with the code " + code + " already exists.");
        }

        Exam exam = new Exam();
        exam.setExamCode(code);
        applyEditableFields(exam, request);
        Exam saved = examRepository.save(exam);
        replaceAudience(saved, request.audience());

        auditLogService.record(AuditAction.CREATE, "Exam", saved.getId(),
                "Created exam " + saved.getExamCode(), null,
                Map.of("code", saved.getExamCode(), "title", saved.getTitle()));

        log.info("Exam {} created", saved.getExamCode());
        return toResponse(saved, principal);
    }

    @Override
    @Transactional
    public ExamResponse update(UserPrincipal principal, Long examId, ExamRequest request) {
        Exam exam = require(examId);
        if (!exam.getStatus().acceptsQuestions()) {
            // After publish the roster exists and people may already be sitting
            // it. Moving the window or the pass mark underneath them changes an
            // exam somebody is halfway through.
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "A published exam cannot be edited. Cancel it and create another.");
        }
        applyEditableFields(exam, request);
        replaceAudience(exam, request.audience());

        auditLogService.record(AuditAction.UPDATE, "Exam", exam.getId(),
                "Updated exam " + exam.getExamCode(), null,
                Map.of("title", exam.getTitle()));

        return toResponse(examRepository.save(exam), principal);
    }

    @Override
    @Transactional
    public ExamResponse cancel(UserPrincipal principal, Long examId, ExamCancelRequest request) {
        Exam exam = require(examId);
        if (exam.getStatus() == ExamStatus.CLOSED) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "That exam has already closed, so there is nothing to call off.");
        }
        ExamStatus before = exam.getStatus();
        exam.setStatus(ExamStatus.CANCELLED);
        exam.setCancellationReason(request.reason().trim());
        Exam saved = examRepository.save(exam);

        auditLogService.record(AuditAction.EXAM_CANCEL, "Exam", saved.getId(),
                "Cancelled exam " + saved.getExamCode(),
                Map.of("status", before.name()),
                Map.of("status", ExamStatus.CANCELLED.name(), "reason", request.reason()));

        // Only tell people who were actually expecting it. A cancelled draft
        // was never announced.
        if (before != ExamStatus.DRAFT && before != ExamStatus.QUESTIONS_LOADED) {
            notifyRoster(saved, participantRepository.findRoster(saved.getId()),
                    NotificationType.EXAM_PUBLISHED,
                    "Exam cancelled",
                    saved.getTitle() + " has been cancelled. " + request.reason().trim(),
                    principal);
        }

        log.info("Exam {} cancelled", saved.getExamCode());
        return toResponse(saved, principal);
    }

    /**
     * Fixes the roster and deals the papers.
     *
     * <p>Everything that would make the published exam wrong is collected
     * first and reported together, so HR fixes the paper in one pass instead of
     * discovering the next problem after each correction.
     */
    @Override
    @Transactional
    public ExamPublishResponse publish(UserPrincipal principal, Long examId) {
        Exam exam = require(examId);

        List<String> blockers = publishBlockers(exam);
        if (!blockers.isEmpty()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "This exam is not ready to publish: " + String.join("; ", blockers));
        }

        List<ExamQuestionSet> sets = setsOf(exam);
        List<Employee> roster = resolveAudience(exam);
        if (roster.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Nobody matches that audience, so there would be nobody to publish it to.");
        }

        Set<Long> testers = new HashSet<>(userRepository.findEmployeeIdsWithPermission(PERM_IMPORT));

        Map<String, Integer> perSet = new LinkedHashMap<>();
        List<ExamParticipant> participants = new ArrayList<>();

        for (Map.Entry<Employee, ExamQuestionSet> dealt : assignSets(exam, roster, sets).entrySet()) {
            ExamParticipant participant = new ExamParticipant();
            participant.setExam(exam);
            participant.setEmployee(dealt.getKey());
            participant.setAssignedSet(dealt.getValue());
            participant.setParticipantKind(testers.contains(dealt.getKey().getId())
                    ? ExamParticipantKind.TESTER : ExamParticipantKind.CANDIDATE);
            participants.add(participantRepository.save(participant));
            perSet.merge(dealt.getValue().getSetCode(), 1, Integer::sum);
        }

        exam.setStatus(ExamStatus.PUBLISHED);
        exam.setPublishedAt(Instant.now());
        exam.setPublishedBy(principal.getEmployeeId());
        Exam saved = examRepository.save(exam);

        int testerCount = (int) participants.stream()
                .filter(p -> p.getParticipantKind() == ExamParticipantKind.TESTER).count();

        auditLogService.record(AuditAction.EXAM_PUBLISH, "Exam", saved.getId(),
                "Published " + saved.getExamCode() + " to " + participants.size() + " people",
                Map.of("status", ExamStatus.QUESTIONS_LOADED.name()),
                Map.of("status", ExamStatus.PUBLISHED.name(),
                        "participants", participants.size(),
                        "perSet", perSet));

        // Everybody on the roster is told, testers included: they are excluded
        // from the figures, not from the exam. An exam nobody was told about has
        // a participation problem that looks like a knowledge problem.
        notifyRoster(saved, participants,
                saved.isRetake() ? NotificationType.EXAM_RETAKE_OPENED
                        : NotificationType.EXAM_PUBLISHED,
                saved.isRetake() ? "Retake available" : "Exam assigned",
                saved.getTitle() + " — opens " + formatWhen(saved.getWindowStart())
                        + ", " + saved.getDurationMinutes() + " minutes, pass mark "
                        + saved.getPassMarks(),
                principal);

        log.info("Exam {} published to {} people, split {}",
                saved.getExamCode(), participants.size(), perSet);

        return new ExamPublishResponse(saved.getId(), participants.size(),
                participants.size() - testerCount, testerCount, perSet,
                participants.size() + " people assigned across " + sets.size() + " sets.");
    }

    // -----------------------------------------------------------------
    // Publishing internals
    // -----------------------------------------------------------------

    /**
     * The sets this exam is sat from.
     *
     * <p>A retake has none of its own — see {@link ExamSets}.
     */
    private List<ExamQuestionSet> setsOf(Exam exam) {
        return questionSetRepository.findByExamIdOrderBySetCodeAsc(ExamSets.bankExamId(exam));
    }

    /**
     * Who gets which paper.
     *
     * <p>A first sitting is shuffled and then dealt in turn. Shuffling alone
     * leaves the split uneven; dealing alone makes the set somebody gets a
     * function of their employee id, which is stable across every exam they
     * ever sit.
     *
     * <p>A retake cannot deal blindly. Feedback is immediate, so reusing
     * somebody's own paper hands them the answer key and then re-tests them on
     * it. Each person is given the least-used set they have not opened, which
     * keeps the split as even as the constraint allows.
     */
    private Map<Employee, ExamQuestionSet> assignSets(Exam exam, List<Employee> roster,
                                                      List<ExamQuestionSet> sets) {
        List<Employee> shuffled = new ArrayList<>(roster);
        Collections.shuffle(shuffled, random);

        Map<Employee, ExamQuestionSet> dealt = new LinkedHashMap<>();

        if (!exam.isRetake()) {
            for (int i = 0; i < shuffled.size(); i++) {
                dealt.put(shuffled.get(i), sets.get(i % sets.size()));
            }
            return dealt;
        }

        Long bankExamId = ExamSets.bankExamId(exam);
        Map<Long, Integer> load = new LinkedHashMap<>();
        sets.forEach(set -> load.put(set.getId(), 0));

        for (Employee employee : shuffled) {
            Set<Long> seen = exam.isRetakeRequiresUnseenSet()
                    ? new LinkedHashSet<>(
                            participantRepository.findSetIdsSeenBy(employee.getId(), bankExamId))
                    : Set.of();

            ExamQuestionSet choice = sets.stream()
                    .filter(set -> !seen.contains(set.getId()))
                    .min(Comparator.comparingInt(set -> load.get(set.getId())))
                    .orElseThrow(() -> new BusinessException(ErrorCode.POLICY_VIOLATION,
                            employee.getFullName() + " has already sat every set, so there is no "
                                    + "paper left to retake them on. Load a new question bank, or "
                                    + "turn off the unseen-set rule and accept that the retake "
                                    + "measures nothing."));

            dealt.put(employee, choice);
            load.merge(choice.getId(), 1, Integer::sum);
        }
        return dealt;
    }

    /**
     * Everything that would make the published exam wrong, as a list rather
     * than the first thing thrown.
     */
    List<String> publishBlockers(Exam exam) {
        List<String> blockers = new ArrayList<>();

        if (!exam.getStatus().canPublish()) {
            blockers.add("it is " + exam.getStatus().name().toLowerCase(Locale.ROOT)
                    .replace('_', ' ') + " rather than ready to publish");
        }

        List<ExamQuestionSet> sets = setsOf(exam);
        if (sets.size() != REQUIRED_SETS) {
            blockers.add("there are " + sets.size() + " question sets, not " + REQUIRED_SETS);
        }
        if (sets.stream().anyMatch(set -> set.getQuestionCount() == 0)) {
            blockers.add("one of the sets has no questions");
        }

        // The bar has to be the same whichever paper somebody is dealt. A pass
        // mark of 40 is not the same thing against a set worth 95, and nothing
        // in the results would ever show it.
        long distinctTotals = sets.stream().map(ExamQuestionSet::getTotalMarks).distinct().count();
        if (sets.size() > 1 && distinctTotals > 1) {
            blockers.add("the sets are worth different totals ("
                    + sets.stream().map(s -> s.getSetCode() + "=" + s.getTotalMarks()).toList()
                    + "), so the pass mark would mean something different depending on which "
                    + "paper somebody was dealt");
        }

        int paperTotal = sets.stream().mapToInt(ExamQuestionSet::getTotalMarks).max().orElse(0);
        if (paperTotal > 0 && exam.getPassMarks() > paperTotal) {
            blockers.add("the pass mark (" + exam.getPassMarks() + ") is higher than the paper is "
                    + "worth (" + paperTotal + ")");
        }
        if (!exam.getWindowEnd().isAfter(exam.getWindowStart())) {
            blockers.add("the window closes before it opens");
        }
        if (audienceRepository.findByExamId(exam.getId()).isEmpty()) {
            blockers.add("nobody has been chosen to sit it");
        }

        // Said before publish rather than discovered during it. One query per
        // person, which is affordable because a retake cohort is the people who
        // failed, not the whole organisation.
        if (exam.isRetake() && exam.isRetakeRequiresUnseenSet() && !sets.isEmpty()) {
            List<String> stuck = resolveAudience(exam).stream()
                    .filter(employee -> participantRepository
                            .findSetIdsSeenBy(employee.getId(), ExamSets.bankExamId(exam))
                            .size() >= sets.size())
                    .map(Employee::getFullName)
                    .sorted()
                    .toList();
            if (!stuck.isEmpty()) {
                blockers.add(String.join(", ", stuck) + (stuck.size() == 1 ? " has" : " have")
                        + " already sat every set, so there is no unseen paper left");
            }
        }
        return blockers;
    }

    /** The audience rules turned into concrete people, once, at publish. */
    private List<Employee> resolveAudience(Exam exam) {
        List<ExamAudience> rules = audienceRepository.findByExamId(exam.getId());
        if (rules.isEmpty()) {
            return List.of();
        }

        Set<Long> ids = new LinkedHashSet<>();
        List<Employee> all = null;

        for (ExamAudience rule : rules) {
            switch (rule.getScopeType()) {
                case GLOBAL -> {
                    all = all == null ? employeeRepository.findAll() : all;
                    all.stream().filter(Employee::isActive).map(Employee::getId).forEach(ids::add);
                }
                case DEPARTMENT -> {
                    all = all == null ? employeeRepository.findAll() : all;
                    all.stream()
                            .filter(Employee::isActive)
                            .filter(e -> e.getDepartment() != null
                                    && e.getDepartment().getId().equals(rule.getScopeId()))
                            .map(Employee::getId)
                            .forEach(ids::add);
                }
                case OFFICE -> {
                    all = all == null ? employeeRepository.findAll() : all;
                    all.stream()
                            .filter(Employee::isActive)
                            .filter(e -> e.getOffice() != null
                                    && e.getOffice().getId().equals(rule.getScopeId()))
                            .map(Employee::getId)
                            .forEach(ids::add);
                }
                case DESIGNATION -> {
                    all = all == null ? employeeRepository.findAll() : all;
                    all.stream()
                            .filter(Employee::isActive)
                            .filter(e -> e.getDesignation() != null
                                    && e.getDesignation().getId().equals(rule.getScopeId()))
                            .map(Employee::getId)
                            .forEach(ids::add);
                }
                case EMPLOYEE -> {
                    if (rule.getScopeId() != null) {
                        ids.add(rule.getScopeId());
                    }
                }
                default -> throw new IllegalStateException("Unhandled scope " + rule.getScopeType());
            }
        }

        if (ids.isEmpty()) {
            return List.of();
        }
        // The window start, not today: an exam set up on Friday for Monday
        // should be dealt to the people employed on Monday.
        return employeeRepository.findAllById(ids).stream()
                .filter(Employee::isActive)
                .filter(employee -> wasEmployedOn(employee, exam.getWindowStart()))
                .sorted(Comparator.comparing(Employee::getEmployeeCode))
                .toList();
    }

    /**
     * Whether somebody was on the payroll when the exam opens.
     *
     * <p>The guard that stops a new joiner being counted as having missed an
     * exam that predates them, and the same check the dashboard applies before
     * calling anybody absent.
     */
    private boolean wasEmployedOn(Employee employee, Instant at) {
        ZoneId zone = DateTimeUtils.zoneOf(employee);
        var date = DateTimeUtils.localDate(at, zone);
        if (employee.getJoiningDate() != null && employee.getJoiningDate().isAfter(date)) {
            return false;
        }
        return employee.getExitDate() == null || !employee.getExitDate().isBefore(date);
    }

    private void applyEditableFields(Exam exam, ExamRequest request) {
        if (!request.windowEnd().isAfter(request.windowStart())) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "The exam has to close after it opens.");
        }
        if (request.passMarks() > request.marksPerQuestion() * 100) {
            // A sanity bound only. The real check is against the loaded paper,
            // in publishBlockers, because the total is not known until then.
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "That pass mark is higher than any paper could be worth.");
        }

        exam.setTitle(request.title().trim());
        exam.setDescription(blankToNull(request.description()));
        exam.setInstructions(blankToNull(request.instructions()));
        exam.setWindowStart(request.windowStart());
        exam.setWindowEnd(request.windowEnd());
        exam.setDurationMinutes(request.durationMinutes());
        exam.setEntryClosesBeforeMinutes(request.entryClosesBeforeMinutes());
        exam.setMarksPerQuestion(request.marksPerQuestion());
        exam.setNegativeMarks(request.negativeMarks());
        exam.setPassMarks(request.passMarks());
        exam.setShuffleQuestions(request.shuffleQuestions());
        exam.setShuffleOptions(request.shuffleOptions());
        exam.setPartialCredit(request.partialCredit());
        exam.setFeedbackVisibility(request.feedbackVisibility() == null
                ? ExamFeedbackVisibility.IMMEDIATE : request.feedbackVisibility());
    }

    private void replaceAudience(Exam exam, List<ExamRequest.AudienceRule> rules) {
        audienceRepository.deleteByExamId(exam.getId());
        if (rules == null || rules.isEmpty()) {
            return;
        }

        Set<String> seen = new LinkedHashSet<>();
        for (ExamRequest.AudienceRule rule : rules) {
            ExamAudienceScope type = parseScope(rule.scopeType());
            if (type.needsScopeId() && rule.scopeId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Choose which " + type.name().toLowerCase(Locale.ROOT)
                                + " the exam is for.");
            }
            // The unique key would reject a repeat anyway; skipping it here
            // means a duplicated row in the form is not an error worth showing.
            if (!seen.add(type + ":" + rule.scopeId())) {
                continue;
            }
            ExamAudience row = new ExamAudience();
            row.setExam(exam);
            row.setScopeType(type);
            row.setScopeId(type == ExamAudienceScope.GLOBAL ? null : rule.scopeId());
            audienceRepository.save(row);
        }
    }

    // -----------------------------------------------------------------
    // Assembling responses
    // -----------------------------------------------------------------

    /** One exam as the person sitting it sees it, with Start already decided. */
    private MyExamResponse myExam(ExamParticipant participant, ExamAttempt attempt, Instant now) {
        Exam exam = participant.getExam();
        ExamQuestionSet mine = participant.getAssignedSet();
        boolean submitted = attempt != null && attempt.isSubmitted();
        boolean closed = exam.getStatus() == ExamStatus.CLOSED || !now.isBefore(exam.getWindowEnd());

        MyExamResponse.Bucket bucket;
        String blocked = null;

        if (submitted) {
            bucket = MyExamResponse.Bucket.COMPLETED;
        } else if (attempt != null && !closed) {
            bucket = MyExamResponse.Bucket.IN_PROGRESS;
        } else if (closed) {
            // Started and never handed in still reads as missed here: the
            // settlement path will have scored it, and until it has there is
            // nothing to show them.
            bucket = MyExamResponse.Bucket.MISSED;
            blocked = "This exam has closed";
        } else if (now.isBefore(exam.getWindowStart())) {
            bucket = MyExamResponse.Bucket.UPCOMING;
            blocked = "Opens " + formatWhen(exam.getWindowStart());
        } else if (!exam.acceptsNewAttemptsAt(now)) {
            bucket = MyExamResponse.Bucket.OPEN;
            blocked = "Too late to start — the exam closes in under "
                    + exam.getEntryClosesBeforeMinutes() + " minutes";
        } else {
            bucket = MyExamResponse.Bucket.OPEN;
        }

        return new MyExamResponse(
                exam.getId(), exam.getExamCode(), exam.getTitle(), exam.getDescription(),
                exam.getInstructions(), exam.getWindowStart(), exam.getWindowEnd(),
                exam.getDurationMinutes(), exam.getEntryClosesBeforeMinutes(),
                mine.getQuestionCount(), mine.getTotalMarks(), exam.getPassMarks(),
                exam.isRetake(), mine.getSetCode(), participant.getStatus(), bucket, blocked,
                attempt == null ? null : attempt.getId(),
                submitted ? attempt.getScore() : null,
                submitted ? attempt.getMaxScore() : null,
                submitted ? attempt.getPercentage() : null,
                submitted ? attempt.getPassed() : null,
                submitted ? attempt.getSubmittedAt() : null,
                submitted && feedbackReadable(exam, now));
    }

    /**
     * Whether the worked answers are readable yet.
     *
     * <p>The score always is once the paper is in. The explanations may be held
     * back until the window shuts so the first person out of the room cannot
     * brief the queue.
     */
    private boolean feedbackReadable(Exam exam, Instant now) {
        return switch (exam.getFeedbackVisibility()) {
            case IMMEDIATE -> true;
            case AFTER_CLOSE -> exam.getStatus() == ExamStatus.CLOSED
                    || now.isAfter(exam.getWindowEnd());
            case NEVER -> false;
        };
    }

    private ExamResponse toResponse(Exam exam, UserPrincipal principal) {
        List<ExamQuestionSet> sets = setsOf(exam);
        List<ExamAudience> rules = audienceRepository.findByExamId(exam.getId());
        // Once: for a retake this runs a per-person query over the cohort.
        List<String> blockers = publishBlockers(exam);

        return new ExamResponse(
                exam.getId(), exam.getExamCode(), exam.getTitle(), exam.getDescription(),
                exam.getInstructions(), exam.getStatus(), exam.getWindowStart(),
                exam.getWindowEnd(), exam.getDurationMinutes(),
                exam.getEntryClosesBeforeMinutes(), exam.getMarksPerQuestion(),
                exam.getNegativeMarks(), exam.getTotalMarks(), exam.getPassMarks(),
                exam.isShuffleQuestions(), exam.isShuffleOptions(), exam.isPartialCredit(),
                exam.getFeedbackVisibility(), exam.isRetake(),
                exam.getParentExam() == null ? null : exam.getParentExam().getId(),
                exam.getParentExam() == null ? null : exam.getParentExam().getExamCode(),
                exam.isRetakeRequiresUnseenSet(), exam.getPublishedAt(), exam.getClosedAt(),
                exam.getCancellationReason(),
                rules.stream()
                        .map(rule -> new ExamResponse.Audience(rule.getScopeType(),
                                rule.getScopeId(), scopeName(rule)))
                        .toList(),
                sets.stream()
                        .map(set -> new ExamResponse.SetSummary(set.getId(), set.getSetCode(),
                                set.getQuestionCount(), set.getTotalMarks()))
                        .toList(),
                participantRepository.findEmployeeIds(exam.getId()).size(),
                blockers,
                exam.getStatus().acceptsQuestions(),
                blockers.isEmpty(),
                exam.getStatus().acceptsQuestions() && !exam.isRetake()
                        && principal.hasPermission(PERM_IMPORT));
    }

    private ExamSummaryResponse toSummary(Exam exam) {
        List<ExamQuestionSet> sets = setsOf(exam);
        List<ExamParticipant> roster = participantRepository.findByExamIdAndStatus(
                exam.getId(), ExamParticipantStatus.SUBMITTED);

        return new ExamSummaryResponse(
                exam.getId(), exam.getExamCode(), exam.getTitle(), exam.getStatus(),
                exam.getWindowStart(), exam.getWindowEnd(), exam.getDurationMinutes(),
                exam.getPassMarks(), exam.getTotalMarks(), sets.size(),
                sets.stream().mapToInt(ExamQuestionSet::getQuestionCount).max().orElse(0),
                participantRepository.findEmployeeIds(exam.getId()).size(),
                roster.size(), exam.isRetake(),
                exam.getParentExam() == null ? null : exam.getParentExam().getId());
    }

    /**
     * A readable name for an audience rule.
     *
     * <p>Looked up here rather than stored on the rule: a department renamed
     * next month should read with its new name, and the rule is only ever a
     * pointer.
     */
    private String scopeName(ExamAudience rule) {
        if (rule.getScopeId() == null) {
            return "Everybody";
        }
        return switch (rule.getScopeType()) {
            case GLOBAL -> "Everybody";
            case DEPARTMENT -> departmentRepository.findById(rule.getScopeId())
                    .map(d -> d.getName()).orElse("Department " + rule.getScopeId());
            case OFFICE -> officeRepository.findById(rule.getScopeId())
                    .map(o -> o.getName()).orElse("Office " + rule.getScopeId());
            case DESIGNATION -> designationRepository.findById(rule.getScopeId())
                    .map(d -> d.getName()).orElse("Designation " + rule.getScopeId());
            case EMPLOYEE -> employeeRepository.findById(rule.getScopeId())
                    .map(Employee::getFullName).orElse("Employee " + rule.getScopeId());
        };
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private Map<Long, ExamAttempt> attemptsByParticipant(List<ExamParticipant> roster) {
        if (roster.isEmpty()) {
            return Map.of();
        }
        Map<Long, ExamAttempt> byParticipant = new LinkedHashMap<>();
        attemptRepository.findByParticipantIdIn(roster.stream()
                        .map(ExamParticipant::getId).toList())
                // Highest attempt number wins, so a retake's own row does not
                // lose to the original sitting it followed.
                .stream()
                .sorted(Comparator.comparingInt(ExamAttempt::getAttemptNumber))
                .forEach(attempt -> byParticipant.put(attempt.getParticipant().getId(), attempt));
        return byParticipant;
    }

    private void notifyRoster(Exam exam, List<ExamParticipant> roster, NotificationType type,
                              String title, String body, UserPrincipal actor) {
        String link = "/exams";
        for (ExamParticipant participant : roster) {
            notificationService.notifyEmployee(participant.getEmployee().getId(), type,
                    title, body, link, "Exam", exam.getId(),
                    actor == null ? null : actor.getUserId(), null);
        }
    }

    /**
     * The window in the office's own words.
     *
     * <p>An exam belongs to no single employee, so there is no per-person zone
     * to read here the way attendance does. The platform's configured zone is
     * the right one: a 10:00 exam is 10:00 where the people are.
     */
    private String formatWhen(Instant at) {
        return WHEN.format(at.atZone(DateTimeUtils.FALLBACK_ZONE));
    }

    private Exam require(Long examId) {
        return examRepository.findById(examId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "That exam does not exist."));
    }

    private ExamStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ExamStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "There is no exam status called '" + raw + "'.");
        }
    }

    private ExamAudienceScope parseScope(String raw) {
        try {
            return ExamAudienceScope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException unknown) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "There is no audience type called '" + raw + "'.");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
