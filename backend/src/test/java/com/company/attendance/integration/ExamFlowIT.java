package com.company.attendance.integration;

import com.company.attendance.dto.request.exam.AnswerSaveRequest;
import com.company.attendance.dto.request.exam.ExamRequest;
import com.company.attendance.dto.response.exam.ExamAnalyticsResponse;
import com.company.attendance.dto.response.exam.ExamAttemptResponse;
import com.company.attendance.dto.response.exam.ExamParticipantResponse;
import com.company.attendance.dto.response.exam.ExamPublishResponse;
import com.company.attendance.dto.response.exam.ExamResultResponse;
import com.company.attendance.dto.response.exam.MyExamResponse;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeShift;
import com.company.attendance.entity.ExamAttempt;
import com.company.attendance.entity.ExamQuestion;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.Permission;
import com.company.attendance.entity.Role;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.User;
import com.company.attendance.enums.ExamFeedbackVisibility;
import com.company.attendance.enums.ExamParticipantStatus;
import com.company.attendance.enums.ExamStatus;
import com.company.attendance.enums.ExamSubmissionSource;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.ExamAttemptRepository;
import com.company.attendance.repository.ExamParticipantRepository;
import com.company.attendance.repository.ExamQuestionRepository;
import com.company.attendance.repository.ExamRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.PermissionRepository;
import com.company.attendance.repository.RoleRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.ExamAnalyticsService;
import com.company.attendance.service.ExamAttemptService;
import com.company.attendance.service.ExamImportService;
import com.company.attendance.service.ExamService;
import com.company.attendance.support.DatabaseCleaner;
import com.company.attendance.support.ExamWorkbookFixture;
import com.company.attendance.util.PaperLayout;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The exam exists to teach, and the figures it produces are the output that
 * matters. These tests guard the handful of places where it could run perfectly
 * and measure something other than what it claims.
 *
 * <p>The roster has to be frozen when it is published, or last week's
 * participation rate moves whenever the org chart does. Pass has to be decided
 * on marks, or a rounding rule gets a say in somebody's result. A paper the
 * clock ended has to be scored as it stood, not lost. An exam nobody closed has
 * to produce numbers anyway, because there is no scheduler to close it. And the
 * people who wrote the paper have to stay out of the rates, or every question
 * looks better understood than it is.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExamFlowIT {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument."
            + "spreadsheetml.sheet";
    private static final int QUESTIONS_PER_SET = 20;

    @Autowired ExamService examService;
    @Autowired ExamAttemptService attemptService;
    @Autowired ExamImportService importService;
    @Autowired ExamAnalyticsService analyticsService;
    @Autowired ExamRepository examRepository;
    @Autowired ExamParticipantRepository participantRepository;
    @Autowired ExamAttemptRepository attemptRepository;
    @Autowired ExamQuestionRepository questionRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired EmployeeShiftRepository employeeShiftRepository;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PermissionRepository permissionRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired DatabaseCleaner databaseCleaner;

    private Office office;
    private Shift shift;
    private Employee hrPerson;
    private final List<Employee> staff = new ArrayList<>();

    private UserPrincipal hr;
    private final List<UserPrincipal> candidates = new ArrayList<>();

    @BeforeEach
    void seed() {
        databaseCleaner.clean();
        staff.clear();
        candidates.clear();

        office = new Office();
        office.setCode("HO");
        office.setName("Head Office");
        office.setTimezone("Asia/Kolkata");
        office = officeRepository.save(office);

        shift = new Shift();
        shift.setCode("GEN");
        shift.setName("General Shift");
        shift.setStartTime(LocalTime.of(9, 30));
        shift.setEndTime(LocalTime.of(18, 30));
        shift = shiftRepository.save(shift);

        // The person who sets the paper. Given EXAM_QUESTION_IMPORT through a
        // real role, because that grant is what marks somebody a tester.
        hrPerson = employee("EMP8000", "Priya", "Sharma");
        hr = principalWithRealRole(hrPerson, "HR8000",
                Set.of("EXAM_MANAGE", "EXAM_QUESTION_IMPORT", "EXAM_RESULT_VIEW_ALL",
                        "EXAM_VIEW_SELF", "EXAM_ATTEMPT"));

        for (int i = 1; i <= 9; i++) {
            Employee person = employee("EMP80" + String.format("%02d", i), "Candidate", "No" + i);
            staff.add(person);
            candidates.add(principal(person, "USR80" + String.format("%02d", i),
                    Set.of("EXAM_VIEW_SELF", "EXAM_ATTEMPT")));
        }
    }

    // ------------------------------------------------------------ publishing

    @Test
    @DisplayName("publishing deals the three sets evenly and freezes who is on the roster")
    void publishDealsSetsEvenlyAndFreezesTheRoster() {
        Long examId = loadedExam("PK-W01", openWindow());

        ExamPublishResponse published = examService.publish(hr, examId);

        // Ten people over three sets: 4/3/3 in some order. Anything lopsided
        // means the dealing has gone wrong in a way no later screen shows.
        assertThat(published.rosterSize()).isEqualTo(10);
        assertThat(published.perSet().values()).allSatisfy(count ->
                assertThat(count).isBetween(3, 4));
        assertThat(published.perSet().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(10);

        // The one who loaded the questions read the answers doing it.
        assertThat(published.testerCount()).isEqualTo(1);
        assertThat(published.candidateCount()).isEqualTo(9);

        Set<Long> frozen = new HashSet<>(participantRepository.findEmployeeIds(examId));

        // Somebody hired after publish must not appear retrospectively.
        Employee latecomer = employee("EMP8099", "New", "Starter");
        assertThat(participantRepository.findEmployeeIds(examId))
                .hasSameElementsAs(frozen)
                .doesNotContain(latecomer.getId());
    }

    @Test
    @DisplayName("every blocker is listed at once rather than one per attempt")
    void publishBlockersComeTogether() {
        Long examId = examService.create(hr, request("PK-EMPTY", openWindow(), List.of())).id();

        assertThat(examService.get(hr, examId).publishBlockers())
                .anySatisfy(blocker -> assertThat(blocker).contains("question sets"))
                .anySatisfy(blocker -> assertThat(blocker).contains("nobody has been chosen"));

        assertThatThrownBy(() -> examService.publish(hr, examId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not ready to publish");
    }

    @Test
    @DisplayName("sets worth different totals are refused, because the pass mark would drift")
    void setsMustBeWorthTheSame() {
        Long examId = examService.create(hr, request("PK-UNEVEN", openWindow(), globalAudience()))
                .id();
        importService.importWorkbook(hr, examId, file(ExamWorkbookFixture
                .threeSets(QUESTIONS_PER_SET)
                .withoutQuestion("C", 20)
                .build()), true);

        assertThat(examService.get(hr, examId).publishBlockers())
                .anySatisfy(blocker -> assertThat(blocker)
                        .contains("worth different totals")
                        .contains("C=95"));

        assertThatThrownBy(() -> examService.publish(hr, examId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a published exam cannot be edited underneath the people sitting it")
    void publishedExamIsFrozen() {
        Long examId = loadedExam("PK-FROZEN", openWindow());
        examService.publish(hr, examId);

        assertThatThrownBy(() -> examService.update(hr, examId,
                request("PK-FROZEN", openWindow(), globalAudience())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be edited");
    }

    // --------------------------------------------------------------- sitting

    @Test
    @DisplayName("starting twice gives the same paper, not a second attempt")
    void startIsIdempotent() {
        Long examId = publishedExam("PK-START", openWindow());
        UserPrincipal candidate = candidates.get(0);

        ExamAttemptResponse first = attemptService.start(candidate, examId);
        ExamAttemptResponse second = attemptService.start(candidate, examId);

        assertThat(second.attemptId()).isEqualTo(first.attemptId());
        assertThat(first.questions()).hasSize(QUESTIONS_PER_SET);
        assertThat(first.totalMarks()).isEqualTo(100);

        Long participantId = participantRepository
                .findByExamIdAndEmployeeId(examId, staff.get(0).getId()).orElseThrow().getId();
        assertThat(attemptRepository.findByParticipantIdIn(List.of(participantId))).hasSize(1);
    }

    @Test
    @DisplayName("the clock is the server's, and it never runs past the window")
    void expiryIsTheEarlierOfDurationAndWindow() {
        // A window shorter than the duration: the window wins.
        Instant start = Instant.now().minus(Duration.ofMinutes(1));
        Long examId = publishedExam("PK-CLOCK", new Instant[] {start,
                start.plus(Duration.ofMinutes(10))});

        ExamAttemptResponse paper = attemptService.start(candidates.get(0), examId);

        assertThat(paper.expiresAt()).isBeforeOrEqualTo(
                start.plus(Duration.ofMinutes(10)).plusSeconds(1));
        assertThat(paper.secondsRemaining()).isLessThan(Duration.ofMinutes(25).toSeconds());
    }

    @Test
    @DisplayName("an answer can be changed as often as the candidate likes, until it is in")
    void answersAutosaveAndCanBeChanged() {
        Long examId = publishedExam("PK-SAVE", openWindow());
        UserPrincipal candidate = candidates.get(0);
        ExamAttemptResponse paper = attemptService.start(candidate, examId);
        Long questionId = paper.questions().get(0).questionId();

        attemptService.saveAnswer(candidate, paper.attemptId(),
                new AnswerSaveRequest(questionId, List.of("A")));
        attemptService.saveAnswer(candidate, paper.attemptId(),
                new AnswerSaveRequest(questionId, List.of("C")));

        assertThat(attemptService.resume(candidate, paper.attemptId()).questions())
                .filteredOn(question -> question.questionId().equals(questionId))
                .singleElement()
                .satisfies(question ->
                        assertThat(question.selectedLabels()).containsExactly("C"));

        // Clearing it is an answer too.
        attemptService.saveAnswer(candidate, paper.attemptId(),
                new AnswerSaveRequest(questionId, List.of()));
        assertThat(attemptService.resume(candidate, paper.attemptId()).questions())
                .filteredOn(question -> question.questionId().equals(questionId))
                .singleElement()
                .satisfies(question -> assertThat(question.selectedLabels()).isEmpty());
    }

    @Test
    @DisplayName("a question from somebody else's set is refused")
    void cannotAnswerAQuestionFromAnotherPaper() {
        Long examId = publishedExam("PK-OTHERSET", openWindow());
        UserPrincipal candidate = candidates.get(0);
        ExamAttemptResponse paper = attemptService.start(candidate, examId);

        String mySet = paper.setCode();
        ExamQuestion fromAnotherSet = otherSetQuestion(examId, mySet);

        assertThatThrownBy(() -> attemptService.saveAnswer(candidate, paper.attemptId(),
                new AnswerSaveRequest(fromAnotherSet.getId(), List.of("A"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not on your paper");
    }

    // --------------------------------------------------------------- scoring

    @Test
    @DisplayName("eight correct passes at 40 and seven fails at 35 — marks, not a percentage")
    void passIsDecidedOnMarksAtTheBoundary() {
        Long examId = publishedExam("PK-BOUNDARY", openWindow());

        ExamResultResponse sevenRight = sitExam(candidates.get(0), examId, 7);
        ExamResultResponse eightRight = sitExam(candidates.get(1), examId, 8);

        assertThat(sevenRight.correctCount()).isEqualTo(7);
        assertThat(sevenRight.score()).isEqualTo(35);
        assertThat(sevenRight.passed()).isFalse();

        assertThat(eightRight.correctCount()).isEqualTo(8);
        assertThat(eightRight.score()).isEqualTo(40);
        assertThat(eightRight.passed()).isTrue();

        // 35 of 100 is 35%, and 40 is 40%. Neither number decided anything —
        // the pass mark did.
        assertThat(sevenRight.percentage()).isEqualByComparingTo("35.00");
        assertThat(eightRight.percentage()).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("the result carries the worked answer for every question")
    void resultExplainsEveryQuestion() {
        Long examId = publishedExam("PK-FEEDBACK", openWindow());
        ExamResultResponse result = sitExam(candidates.get(0), examId, 5);

        assertThat(result.feedbackVisible()).isTrue();
        assertThat(result.questions()).hasSize(QUESTIONS_PER_SET);
        assertThat(result.questions()).allSatisfy(question -> {
            assertThat(question.explanation()).isNotBlank();
            assertThat(question.correctLabels()).isNotEmpty();
            assertThat(question.options()).hasSize(4);
            assertThat(question.options())
                    .filteredOn(option -> option.correct())
                    .hasSize(1);
        });
        // Five right, fifteen wrong, and the key shown for all twenty.
        assertThat(result.questions()).filteredOn(question -> question.correct()).hasSize(5);
    }

    @Test
    @DisplayName("a skipped question scores nothing and is not marked wrong against the candidate")
    void skippedQuestionsAreNotPenalised() {
        Long examId = publishedExam("PK-SKIP", openWindow());
        ExamResultResponse result = sitExam(candidates.get(0), examId, 3);

        assertThat(result.questions()).filteredOn(question -> question.skipped())
                .hasSize(QUESTIONS_PER_SET - 3)
                .allSatisfy(question -> {
                    assertThat(question.marksAwarded()).isZero();
                    assertThat(question.correct()).isFalse();
                });
        assertThat(result.score()).isEqualTo(15);
    }

    @Test
    @DisplayName("submitting twice returns the same result rather than an error")
    void submitIsIdempotent() {
        Long examId = publishedExam("PK-RESUBMIT", openWindow());
        UserPrincipal candidate = candidates.get(0);

        ExamResultResponse first = sitExam(candidate, examId, 10);
        ExamResultResponse again = attemptService.submit(candidate, first.attemptId());

        assertThat(again.score()).isEqualTo(first.score());
        assertThat(again.submissionSource()).isEqualTo(ExamSubmissionSource.SELF);

        // To the millisecond, not the nanosecond. The first response carries
        // the in-memory Instant and the second has been round-tripped through
        // a TIMESTAMP column, which keeps fewer digits. The paper was handed in
        // once; that is what is being asserted.
        assertThat(again.submittedAt().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(first.submittedAt().truncatedTo(ChronoUnit.MILLIS));
    }

    // ----------------------------------------------- the clock and the window

    @Test
    @DisplayName("a paper whose time ran out is scored as it stood, and marked as the clock's")
    void expiredPaperIsScoredNotLost() {
        Long examId = publishedExam("PK-EXPIRED", openWindow());
        UserPrincipal candidate = candidates.get(0);

        ExamAttemptResponse paper = attemptService.start(candidate, examId);
        answerCorrectly(candidate, paper, 6);

        // Their time runs out while they are still looking at it.
        expireAttempt(paper.attemptId());

        assertThatThrownBy(() -> attemptService.saveAnswer(candidate, paper.attemptId(),
                new AnswerSaveRequest(paper.questions().get(0).questionId(), List.of("A"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Your time is up");

        // Submitting late still produces the result: throwing here would roll
        // back the scoring in the same transaction and lose their morning.
        ExamResultResponse result = attemptService.submit(candidate, paper.attemptId());

        assertThat(result.score()).isEqualTo(30);
        assertThat(result.submissionSource()).isEqualTo(ExamSubmissionSource.AUTO_TIMEOUT);
    }

    @Test
    @DisplayName("an exam nobody closed closes itself on the next read, and settles the absentees")
    void closedWindowSettlesItselfWithoutAScheduler() {
        Long examId = publishedExam("PK-SELFCLOSE", openWindow());

        // One person sits it and leaves it open; everybody else never starts.
        UserPrincipal candidate = candidates.get(0);
        ExamAttemptResponse paper = attemptService.start(candidate, examId);
        answerCorrectly(candidate, paper, 9);

        // The window shuts. There is no sweep to notice.
        closeWindow(examId);

        // Any read settles it. This is the whole no-scheduler design.
        assertThat(examService.get(hr, examId).status()).isEqualTo(ExamStatus.CLOSED);

        List<ExamParticipantResponse> roster = examService.participants(hr, examId);

        assertThat(roster)
                .filteredOn(row -> row.employeeId().equals(staff.get(0).getId()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.status()).isEqualTo(ExamParticipantStatus.SUBMITTED);
                    assertThat(row.score()).isEqualTo(45);
                    assertThat(row.submissionSource())
                            .isEqualTo(ExamSubmissionSource.AUTO_WINDOW_CLOSE);
                });

        assertThat(roster)
                .filteredOn(row -> !row.employeeId().equals(staff.get(0).getId()))
                .allSatisfy(row -> {
                    assertThat(row.status()).isEqualTo(ExamParticipantStatus.NOT_ATTEMPTED);
                    assertThat(row.score()).isNull();
                });
    }

    @Test
    @DisplayName("nobody can start a paper once there is not time left to sit it")
    void startingTooLateIsRefused() {
        // Three minutes left, and the door shuts five minutes before the end.
        Instant now = Instant.now();
        Long examId = publishedExam("PK-TOOLATE", new Instant[] {
                now.minus(Duration.ofMinutes(20)), now.plus(Duration.ofMinutes(3))});

        assertThatThrownBy(() -> attemptService.start(candidates.get(0), examId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not long enough to sit it");

        // And the employee screen says so before they press anything.
        assertThat(examService.myExams(candidates.get(0)))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.bucket()).isEqualTo(MyExamResponse.Bucket.OPEN);
                    assertThat(row.blockedReason()).contains("Too late to start");
                });
    }

    // ------------------------------------------------------------- analytics

    @Test
    @DisplayName("the analysis leaves out whoever wrote the paper, and says that it has")
    void analyticsExcludeTesters() {
        Long examId = publishedExam("PK-ANALYSIS", openWindow());

        // The author sits it having read the answers and gets everything right.
        sitExam(hr, examId, QUESTIONS_PER_SET);
        // Two candidates do rather less well.
        sitExam(candidates.get(0), examId, 4);
        sitExam(candidates.get(1), examId, 6);

        ExamAnalyticsResponse excluded = analyticsService.analyse(hr, examId, false);
        assertThat(excluded.testerCount()).isEqualTo(1);
        assertThat(excluded.submittedCount()).isEqualTo(2);
        assertThat(excluded.passedCount()).isZero();
        assertThat(excluded.passRate()).isEqualByComparingTo("0.00");

        ExamAnalyticsResponse included = analyticsService.analyse(hr, examId, true);
        assertThat(included.submittedCount()).isEqualTo(3);
        assertThat(included.passedCount()).isEqualTo(1);
        // Including the author moves the pass rate from nothing to a third,
        // which is precisely why the default leaves them out.
        assertThat(included.passRate()).isEqualByComparingTo("33.33");
    }

    @Test
    @DisplayName("a question nobody was dealt has no correct rate, rather than nought per cent")
    void unaskedQuestionsAreUnmeasuredNotFailed() {
        Long examId = publishedExam("PK-UNASKED", openWindow());
        ExamResultResponse sat = sitExam(candidates.get(0), examId, 20);

        ExamAnalyticsResponse analysis = analyticsService.analyse(hr, examId, false);

        // Only one set was sat, so two sets' worth of questions were never
        // asked. Those must not sort to the top of a list read to decide what
        // to teach next.
        assertThat(analysis.questions())
                .filteredOn(question -> question.asked() == 0)
                .isNotEmpty()
                .allSatisfy(question -> assertThat(question.correctRate()).isNull());

        assertThat(analysis.questions())
                .filteredOn(question -> question.setCode().equals(sat.setCode()))
                .allSatisfy(question -> {
                    assertThat(question.asked()).isEqualTo(1);
                    assertThat(question.correctRate()).isEqualByComparingTo("100.00");
                });

        // And the topic rollup ignores the untaken sets entirely.
        assertThat(analysis.topics())
                .isNotEmpty()
                .allSatisfy(topic -> assertThat(topic.asked()).isPositive());
    }

    @Test
    @DisplayName("the commonest wrong answer is named, because it is the distractor that works")
    void commonestWrongAnswerIsNamed() {
        Long examId = publishedExam("PK-DISTRACTOR", openWindow());

        // Everybody answers everything wrongly, which is the condition under
        // which the report has to be most useful: a question nobody got right
        // is the next training session, and which wrong answer they all gave
        // says what they actually believe.
        for (UserPrincipal candidate : candidates) {
            ExamAttemptResponse paper = attemptService.start(candidate, examId);
            answerEverythingWrongly(candidate, paper);
            attemptService.submit(candidate, paper.attemptId());
        }

        ExamAnalyticsResponse analysis = analyticsService.analyse(hr, examId, false);

        assertThat(analysis.questions())
                .filteredOn(question -> question.asked() > 0)
                .isNotEmpty()
                .allSatisfy(question -> {
                    assertThat(question.correct()).isZero();
                    assertThat(question.correctRate()).isEqualByComparingTo("0.00");
                    assertThat(question.commonestWrongLabels()).isNotBlank();
                    assertThat(question.commonestWrongText()).isNotBlank();
                    assertThat(question.commonestWrongCount()).isPositive();
                });

        // Worst first: the list is read top-down to decide what to teach.
        assertThat(analysis.questions().get(0).correctRate()).isEqualByComparingTo("0.00");
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Sits the paper, answering {@code correctCount} questions correctly and
     * leaving the rest blank.
     *
     * <p>Reads the key from the repository rather than from the attempt
     * payload, which is the point: the candidate's own view has nowhere to put
     * it. See {@code ExamSecurityIT}.
     */
    private ExamResultResponse sitExam(UserPrincipal candidate, Long examId, int correctCount) {
        ExamAttemptResponse paper = attemptService.start(candidate, examId);
        answerCorrectly(candidate, paper, correctCount);
        return attemptService.submit(candidate, paper.attemptId());
    }

    private void answerCorrectly(UserPrincipal candidate, ExamAttemptResponse paper, int howMany) {
        PaperLayout layout = layoutOf(paper.attemptId());
        for (int i = 0; i < howMany && i < paper.questions().size(); i++) {
            Long questionId = paper.questions().get(i).questionId();
            attemptService.saveAnswer(candidate, paper.attemptId(), new AnswerSaveRequest(
                    questionId, correctDisplayedLabels(layout, questionId)));
        }
    }

    private void answerEverythingWrongly(UserPrincipal candidate, ExamAttemptResponse paper) {
        PaperLayout layout = layoutOf(paper.attemptId());
        for (var question : paper.questions()) {
            List<String> correct = correctDisplayedLabels(layout, question.questionId());
            String wrong = question.options().stream()
                    .map(option -> option.label())
                    .filter(label -> !correct.contains(label))
                    .findFirst()
                    .orElseThrow();
            attemptService.saveAnswer(candidate, paper.attemptId(),
                    new AnswerSaveRequest(question.questionId(), List.of(wrong)));
        }
    }

    /** The labels this candidate saw against the question's real answer. */
    private List<String> correctDisplayedLabels(PaperLayout layout, Long questionId) {
        ExamQuestion question = questionWithOptions(questionId);
        return question.correctLabels().stream()
                .map(canonical -> layout.displayedFor(questionId, canonical))
                .toList();
    }

    private PaperLayout layoutOf(Long attemptId) {
        ExamAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
        return PaperLayout.from(objectMapper, attempt.getQuestionOrder(), attempt.getOptionOrder());
    }

    private ExamQuestion questionWithOptions(Long questionId) {
        // findPaper fetches the options; findById would hand back a row whose
        // option list cannot be read outside a transaction.
        Long setId = questionRepository.findById(questionId).orElseThrow()
                .getQuestionSet().getId();
        return questionRepository.findPaper(setId).stream()
                .filter(question -> question.getId().equals(questionId))
                .findFirst().orElseThrow();
    }

    private ExamQuestion otherSetQuestion(Long examId, String notThisSet) {
        return questionRepository.findBank(examId).stream()
                .filter(question -> !question.getQuestionSet().getSetCode().equals(notThisSet))
                .findFirst().orElseThrow();
    }

    /** Pushes an attempt's clock into the past, without waiting for it. */
    private void expireAttempt(Long attemptId) {
        ExamAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
        attempt.setExpiresAt(Instant.now().minus(Duration.ofMinutes(1)));
        attemptRepository.saveAndFlush(attempt);
    }

    /** Moves the window into the past, as if the exam had simply finished. */
    private void closeWindow(Long examId) {
        var exam = examRepository.findById(examId).orElseThrow();
        exam.setWindowStart(Instant.now().minus(Duration.ofHours(2)));
        exam.setWindowEnd(Instant.now().minus(Duration.ofMinutes(1)));
        examRepository.saveAndFlush(exam);

        for (Long attemptId : attemptRepository.findAll().stream()
                .filter(attempt -> attempt.getSubmittedAt() == null)
                .map(ExamAttempt::getId).toList()) {
            expireAttemptTo(attemptId, exam.getWindowEnd());
        }
    }

    /**
     * Expires an attempt exactly at the window's end, which is how the real
     * thing distinguishes "ran out of time" from "the exam finished".
     */
    private void expireAttemptTo(Long attemptId, Instant at) {
        ExamAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
        attempt.setExpiresAt(at);
        attemptRepository.saveAndFlush(attempt);
    }

    private Long loadedExam(String code, Instant[] window) {
        Long examId = examService.create(hr, request(code, window, globalAudience())).id();
        importService.importWorkbook(hr, examId,
                file(ExamWorkbookFixture.threeSets(QUESTIONS_PER_SET).build()), true);
        return examId;
    }

    private Long publishedExam(String code, Instant[] window) {
        Long examId = loadedExam(code, window);
        examService.publish(hr, examId);
        return examId;
    }

    /** Open now, and wide enough that the entry cut-off is not in play. */
    private Instant[] openWindow() {
        Instant now = Instant.now();
        return new Instant[] {now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(3))};
    }

    private List<ExamRequest.AudienceRule> globalAudience() {
        return List.of(new ExamRequest.AudienceRule("GLOBAL", null));
    }

    private ExamRequest request(String code, Instant[] window,
                                List<ExamRequest.AudienceRule> audience) {
        return new ExamRequest(code, "Product knowledge " + code, null,
                "Twenty questions, twenty-five minutes.", window[0], window[1],
                25, 5, 5, 0, 40,
                true, true, false, ExamFeedbackVisibility.IMMEDIATE, audience);
    }

    private MockMultipartFile file(byte[] workbook) {
        return new MockMultipartFile("file", "questions.xlsx", XLSX, workbook);
    }

    private Employee employee(String code, String first, String last) {
        Employee person = new Employee();
        person.setEmployeeCode(code);
        person.setFirstName(first);
        person.setLastName(last);
        person.setOfficialEmail(code.toLowerCase() + "@company.com");
        person.setJoiningDate(LocalDate.now().minusYears(2));
        person.setOffice(office);
        person = employeeRepository.save(person);

        EmployeeShift assignment = new EmployeeShift();
        assignment.setEmployee(person);
        assignment.setShift(shift);
        assignment.setEffectiveFrom(person.getJoiningDate());
        employeeShiftRepository.save(assignment);
        return person;
    }

    private UserPrincipal principal(Employee employee, String username, Set<String> permissions) {
        User account = account(employee, username);
        return UserPrincipal.fromClaims(account.getId(), employee.getId(), username,
                Set.of("EMPLOYEE"), permissions);
    }

    /**
     * A principal whose permissions also exist as real role rows.
     *
     * <p>Needed because publish reads the people who hold EXAM_QUESTION_IMPORT
     * out of the database to mark them testers. A principal built from claims
     * alone would carry the permission without anybody being findable by it,
     * and the tester count would silently be zero.
     */
    private UserPrincipal principalWithRealRole(Employee employee, String username,
                                                Set<String> permissions) {
        Role role = new Role();
        role.setCode("HR");
        role.setName("HR");
        Set<Permission> granted = new HashSet<>();
        for (String code : permissions) {
            Permission permission = new Permission();
            permission.setCode(code);
            permission.setName(code);
            permission.setModule("EXAM");
            granted.add(permissionRepository.save(permission));
        }
        role.setPermissions(granted);
        role = roleRepository.save(role);

        User account = account(employee, username);
        account.setRoles(new HashSet<>(Set.of(role)));
        account = userRepository.saveAndFlush(account);

        return UserPrincipal.fromClaims(account.getId(), employee.getId(), username,
                Set.of("HR"), permissions);
    }

    private User account(Employee employee, String username) {
        User account = new User();
        account.setUsername(username);
        account.setEmail(employee.getOfficialEmail());
        account.setPasswordHash("{noop}irrelevant");
        account.setEmployee(employee);
        return userRepository.save(account);
    }
}
