package com.company.attendance.integration;

import com.company.attendance.dto.request.exam.AnswerSaveRequest;
import com.company.attendance.dto.request.exam.ExamRequest;
import com.company.attendance.dto.request.exam.RetakeRequest;
import com.company.attendance.dto.response.exam.ExamAttemptResponse;
import com.company.attendance.dto.response.exam.ExamResponse;
import com.company.attendance.dto.response.exam.ExamResultResponse;
import com.company.attendance.dto.response.exam.ExamRetakeResponse;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeShift;
import com.company.attendance.entity.Exam;
import com.company.attendance.entity.ExamAttempt;
import com.company.attendance.entity.ExamParticipant;
import com.company.attendance.entity.ExamQuestion;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.User;
import com.company.attendance.enums.ExamAudienceScope;
import com.company.attendance.enums.ExamFeedbackVisibility;
import com.company.attendance.enums.ExamParticipantStatus;
import com.company.attendance.enums.ExamStatus;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.ExamAttemptRepository;
import com.company.attendance.repository.ExamAudienceRepository;
import com.company.attendance.repository.ExamParticipantRepository;
import com.company.attendance.repository.ExamQuestionRepository;
import com.company.attendance.repository.ExamRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.ExamAttemptService;
import com.company.attendance.service.ExamImportService;
import com.company.attendance.service.ExamRetakeService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retake rule, which is the one piece of this module that would be
 * cheerfully wrong and completely invisible.
 *
 * <p>Feedback is immediate. So a retake on the paper somebody has already sat
 * hands them the answer key and then re-tests them on it: everybody passes, the
 * figures look like a training success, and nothing was measured. The default
 * is therefore a set the candidate has not seen, enforced by the system rather
 * than left to HR to remember.
 *
 * <p>That is only enforceable because a retake is a <em>child exam over the
 * parent's question bank</em> rather than a copy of it. Copying would give two
 * set ids for the same twenty questions, and the unseen check would pass while
 * being meaningless — so these tests also pin the shape down.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExamRetakeIT {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument."
            + "spreadsheetml.sheet";

    @Autowired ExamService examService;
    @Autowired ExamAttemptService attemptService;
    @Autowired ExamImportService importService;
    @Autowired ExamRetakeService retakeService;
    @Autowired ExamRepository examRepository;
    @Autowired ExamParticipantRepository participantRepository;
    @Autowired ExamAttemptRepository attemptRepository;
    @Autowired ExamAudienceRepository audienceRepository;
    @Autowired ExamQuestionRepository questionRepository;
    @Autowired EmployeeRepository employeeRepository;
    @Autowired EmployeeShiftRepository employeeShiftRepository;
    @Autowired UserRepository userRepository;
    @Autowired OfficeRepository officeRepository;
    @Autowired ShiftRepository shiftRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired DatabaseCleaner databaseCleaner;

    private Office office;
    private Shift shift;
    private final List<Employee> staff = new ArrayList<>();
    private final List<UserPrincipal> candidates = new ArrayList<>();
    private UserPrincipal hr;

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

        Employee hrPerson = employee("EMP6000", "Priya", "Sharma");
        hr = principal(hrPerson, "HR6000",
                Set.of("EXAM_MANAGE", "EXAM_QUESTION_IMPORT", "EXAM_RESULT_VIEW_ALL"));

        for (int i = 1; i <= 6; i++) {
            Employee person = employee("EMP60" + String.format("%02d", i), "Candidate", "No" + i);
            staff.add(person);
            candidates.add(principal(person, "USR60" + String.format("%02d", i),
                    Set.of("EXAM_VIEW_SELF", "EXAM_ATTEMPT")));
        }
    }

    // ------------------------------------------------------------- the rule

    @Test
    @DisplayName("a retake never deals somebody the paper they have already seen")
    void retakeDealsAnUnseenSet() {
        Long originalId = publishedExam("PK-ORIG");

        // Everybody fails, so everybody is owed a second chance and the cohort
        // is the whole roster.
        for (UserPrincipal candidate : candidates) {
            sit(candidate, originalId, 2);
        }
        Map<Long, String> firstSets = setsDealtBy(originalId);
        close(originalId);

        ExamRetakeResponse retake = retakeService.open(hr, originalId, retakeRequest(originalId));
        examService.publish(hr, retake.examId());

        Map<Long, String> secondSets = setsDealtBy(retake.examId());

        assertThat(secondSets).hasSize(6);
        secondSets.forEach((employeeId, set) ->
                assertThat(set)
                        .as("employee " + employeeId + " must not be re-dealt their own paper")
                        .isNotEqualTo(firstSets.get(employeeId)));
    }

    @Test
    @DisplayName("the retake is a child exam over the parent's own question bank, not a copy")
    void retakeSharesTheParentsBank() {
        Long originalId = publishedExam("PK-SHARED");
        sit(candidates.get(0), originalId, 1);
        close(originalId);

        ExamRetakeResponse retake = retakeService.open(hr, originalId, retakeRequest(originalId));
        ExamResponse child = examService.get(hr, retake.examId());

        assertThat(child.retake()).isTrue();
        assertThat(child.parentExamId()).isEqualTo(originalId);
        assertThat(child.status()).isEqualTo(ExamStatus.QUESTIONS_LOADED);

        // The sets on the child are the parent's rows, by id. If they were
        // copies, the unseen-set check would compare different ids for the same
        // twenty questions and pass while meaning nothing.
        List<Long> parentSetIds = examService.get(hr, originalId).sets().stream()
                .map(ExamResponse.SetSummary::id).toList();
        assertThat(child.sets()).extracting(ExamResponse.SetSummary::id)
                .containsExactlyInAnyOrderElementsOf(parentSetIds);

        assertThat(child.canImportQuestions())
                .as("a retake has no bank of its own to load")
                .isFalse();
    }

    @Test
    @DisplayName("loading questions against a retake is refused")
    void cannotImportIntoARetake() {
        Long originalId = publishedExam("PK-NOIMPORT");
        sit(candidates.get(0), originalId, 1);
        close(originalId);

        ExamRetakeResponse retake = retakeService.open(hr, originalId, retakeRequest(originalId));

        assertThatThrownBy(() -> importService.importWorkbook(hr, retake.examId(), file(), true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sat from the original");
    }

    @Test
    @DisplayName("the marking is copied, because a retake scored differently is not a retake")
    void retakeCopiesTheMarking() {
        Long originalId = publishedExam("PK-COPY");
        sit(candidates.get(0), originalId, 1);
        close(originalId);

        ExamResponse original = examService.get(hr, originalId);
        ExamRetakeResponse retake = retakeService.open(hr, originalId, retakeRequest(originalId));
        ExamResponse child = examService.get(hr, retake.examId());

        assertThat(child.passMarks()).isEqualTo(original.passMarks());
        assertThat(child.marksPerQuestion()).isEqualTo(original.marksPerQuestion());
        assertThat(child.negativeMarks()).isEqualTo(original.negativeMarks());
        assertThat(child.feedbackVisibility()).isEqualTo(original.feedbackVisibility());
        assertThat(child.shuffleOptions()).isEqualTo(original.shuffleOptions());
        assertThat(child.retakeRequiresUnseenSet()).isTrue();
        assertThat(child.examCode()).isEqualTo(original.examCode() + "-R");
    }

    @Test
    @DisplayName("the cohort is written down as named people, not as a rule that re-resolves")
    void cohortIsFrozenAsNamedPeople() {
        Long originalId = publishedExam("PK-FROZEN");
        sit(candidates.get(0), originalId, 1);
        sit(candidates.get(1), originalId, 20);
        close(originalId);

        ExamRetakeResponse retake = retakeService.open(hr, originalId, retakeRequest(originalId));

        // Snapshotting at publish is right for a fresh exam, but "the people
        // who failed last Tuesday" is a decision already taken — it must not
        // re-resolve into a different list on Friday.
        assertThat(audienceRepository.findByExamId(retake.examId()))
                .isNotEmpty()
                .allSatisfy(rule -> {
                    assertThat(rule.getScopeType()).isEqualTo(ExamAudienceScope.EMPLOYEE);
                    assertThat(rule.getScopeId()).isNotNull();
                });

        assertThat(audienceRepository.findByExamId(retake.examId()))
                .extracting(rule -> rule.getScopeId())
                .doesNotContain(staff.get(1).getId());
    }

    // ------------------------------------------------------------ the cohort

    @Test
    @DisplayName("whoever passed is left off, and the reason says so")
    void passersAreLeftOff() {
        Long originalId = publishedExam("PK-COHORT");

        sit(candidates.get(0), originalId, 8);   // 40 marks: a pass
        sit(candidates.get(1), originalId, 7);   // 35 marks: a fail
        // The rest never turn up at all.
        close(originalId);

        ExamRetakeResponse retake = retakeService.open(hr, originalId, retakeRequest(originalId));

        assertThat(retake.failedCount()).isEqualTo(1);
        assertThat(retake.neverSatCount()).isEqualTo(4);
        assertThat(retake.cohortSize()).isEqualTo(5);

        assertThat(retake.excluded())
                .anySatisfy(excluded -> {
                    assertThat(excluded.employeeId()).isEqualTo(staff.get(0).getId());
                    assertThat(excluded.reason()).contains("passed with 40 marks");
                });

        // Absentees are in: they have seen no feedback, so any unused set works
        // for them, and they are the ones the exam has not reached at all.
        assertThat(audienceRepository.findByExamId(retake.examId()))
                .extracting(rule -> rule.getScopeId())
                .contains(staff.get(1).getId(), staff.get(2).getId())
                .doesNotContain(staff.get(0).getId());
    }

    @Test
    @DisplayName("whoever set the paper is left off, because re-testing them measures nothing")
    void theAuthorIsLeftOff() {
        // The HR account here has no employee on the roster by audience, so
        // this asserts the narrower thing the service decides: a participant
        // marked TESTER is excluded with a reason naming why.
        Long originalId = publishedExam("PK-TESTER");
        sit(candidates.get(0), originalId, 1);
        close(originalId);

        makeTester(originalId, staff.get(1).getId());

        ExamRetakeResponse retake = retakeService.open(hr, originalId, retakeRequest(originalId));

        assertThat(retake.excluded())
                .anySatisfy(excluded -> {
                    assertThat(excluded.employeeId()).isEqualTo(staff.get(1).getId());
                    assertThat(excluded.reason()).contains("wrote the paper");
                });
        assertThat(audienceRepository.findByExamId(retake.examId()))
                .extracting(rule -> rule.getScopeId())
                .doesNotContain(staff.get(1).getId());
    }

    @Test
    @DisplayName("if everybody passed there is nothing to retake, and it says so plainly")
    void nobodyToRetake() {
        Long originalId = publishedExam("PK-ALLPASS");
        for (UserPrincipal candidate : candidates) {
            sit(candidate, originalId, 20);
        }
        close(originalId);

        assertThatThrownBy(() -> retakeService.open(hr, originalId, retakeRequest(originalId)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("There is nobody to retake it");
    }

    @Test
    @DisplayName("somebody mid-paper is not yet owed a retake")
    void inProgressIsNotEligible() {
        // A state the closing sweep normally removes — every attempt expires
        // at or before the window's end — so this pins the decision rather than
        // the path: who needs a retake is not settled until they have finished.
        assertThat(ExamParticipantStatus.IN_PROGRESS.eligibleForRetake(false)).isFalse();
        assertThat(ExamParticipantStatus.SUBMITTED.eligibleForRetake(false)).isTrue();
        assertThat(ExamParticipantStatus.SUBMITTED.eligibleForRetake(true)).isFalse();
        assertThat(ExamParticipantStatus.NOT_ATTEMPTED.eligibleForRetake(false)).isTrue();
    }

    // --------------------------------------------------------------- limits

    @Test
    @DisplayName("a retake of a retake is refused — three sittings would need a fourth set")
    void noRetakeOfARetake() {
        Long originalId = publishedExam("PK-ONCE");
        sit(candidates.get(0), originalId, 1);
        close(originalId);

        ExamRetakeResponse retake = retakeService.open(hr, originalId, retakeRequest(originalId));
        examService.publish(hr, retake.examId());
        close(retake.examId());

        assertThatThrownBy(() -> retakeService.open(hr, retake.examId(),
                retakeRequest(retake.examId())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already a retake");
    }

    @Test
    @DisplayName("an exam gets one retake, refused before a second can even be published")
    void onlyOneRetakePerExam() {
        Long originalId = publishedExam("PK-TWICE");
        sit(candidates.get(0), originalId, 1);
        close(originalId);

        retakeService.open(hr, originalId, retakeRequest(originalId));

        // The per-person guard reads exam_participants, which do not exist
        // until a retake is published. Two unpublished retakes would therefore
        // both pass it, and the second would have no unseen set left to deal.
        assertThatThrownBy(() -> retakeService.open(hr, originalId, retakeRequest(originalId)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already has a retake");
    }

    @Test
    @DisplayName("nobody who has had their retake is put on another one")
    void nobodyGetsASecondRetake() {
        Long originalId = publishedExam("PK-SECOND");
        sit(candidates.get(0), originalId, 1);
        close(originalId);

        ExamRetakeResponse first = retakeService.open(hr, originalId, retakeRequest(originalId));
        examService.publish(hr, first.examId());

        // Cancelling the retake clears the exam-level guard, so the per-person
        // one has to hold on its own: everybody on that cohort has now had
        // their second sitting, and a third would need a fourth question set.
        examService.cancel(hr, first.examId(),
                new com.company.attendance.dto.request.exam.ExamCancelRequest("Wrong window"));

        assertThatThrownBy(() -> retakeService.open(hr, originalId, retakeRequest(originalId)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("There is nobody to retake it");
    }

    @Test
    @DisplayName("a retake cannot be opened while the original is still running")
    void notUntilTheOriginalHasClosed() {
        Long originalId = publishedExam("PK-EARLY");
        sit(candidates.get(0), originalId, 1);

        assertThatThrownBy(() -> retakeService.open(hr, originalId, retakeRequest(originalId)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("has closed");
    }

    @Test
    @DisplayName("a retake window has to open after the original closed")
    void retakeWindowFollowsTheOriginal() {
        Long originalId = publishedExam("PK-OVERLAP");
        sit(candidates.get(0), originalId, 1);
        close(originalId);

        Exam original = examRepository.findById(originalId).orElseThrow();

        assertThatThrownBy(() -> retakeService.open(hr, originalId, new RetakeRequest(null,
                original.getWindowEnd().minus(Duration.ofHours(1)),
                original.getWindowEnd().plus(Duration.ofHours(1)), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("after the original closed");
    }

    // ----------------------------------------------------------------- helpers

    /** Who was dealt which set on one exam. */
    private Map<Long, String> setsDealtBy(Long examId) {
        return participantRepository.findRoster(examId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        participant -> participant.getEmployee().getId(),
                        participant -> participant.getAssignedSet().getSetCode()));
    }

    private ExamResultResponse sit(UserPrincipal candidate, Long examId, int correctCount) {
        ExamAttemptResponse paper = attemptService.start(candidate, examId);
        PaperLayout layout = layoutOf(paper.attemptId());

        for (int i = 0; i < correctCount && i < paper.questions().size(); i++) {
            Long questionId = paper.questions().get(i).questionId();
            attemptService.saveAnswer(candidate, paper.attemptId(), new AnswerSaveRequest(
                    questionId, correctDisplayedLabels(layout, questionId)));
        }
        return attemptService.submit(candidate, paper.attemptId());
    }

    private List<String> correctDisplayedLabels(PaperLayout layout, Long questionId) {
        Long setId = questionRepository.findById(questionId).orElseThrow()
                .getQuestionSet().getId();
        ExamQuestion question = questionRepository.findPaper(setId).stream()
                .filter(candidate -> candidate.getId().equals(questionId))
                .findFirst().orElseThrow();
        return question.correctLabels().stream()
                .map(canonical -> layout.displayedFor(questionId, canonical))
                .toList();
    }

    private PaperLayout layoutOf(Long attemptId) {
        ExamAttempt attempt = attemptRepository.findById(attemptId).orElseThrow();
        return PaperLayout.from(objectMapper, attempt.getQuestionOrder(), attempt.getOptionOrder());
    }

    /** Ends the exam by moving its window into the past, then reading it. */
    private void close(Long examId) {
        Exam exam = examRepository.findById(examId).orElseThrow();
        exam.setWindowStart(Instant.now().minus(Duration.ofHours(2)));
        exam.setWindowEnd(Instant.now().minus(Duration.ofMinutes(1)));
        examRepository.saveAndFlush(exam);

        // The read is what settles it: there is no scheduler.
        assertThat(examService.get(hr, examId).status()).isEqualTo(ExamStatus.CLOSED);
    }

    /**
     * Marks somebody a tester after the fact.
     *
     * <p>Publish decides this from who holds EXAM_QUESTION_IMPORT. Setting it
     * directly keeps this test about the retake rule rather than about the
     * role tables.
     */
    private void makeTester(Long examId, Long employeeId) {
        ExamParticipant participant = participantRepository
                .findByExamIdAndEmployeeId(examId, employeeId).orElseThrow();
        participant.setParticipantKind(com.company.attendance.enums.ExamParticipantKind.TESTER);
        participantRepository.saveAndFlush(participant);
    }

    private RetakeRequest retakeRequest(Long originalId) {
        Instant originalEnd = examRepository.findById(originalId).orElseThrow().getWindowEnd();
        Instant start = originalEnd.plus(Duration.ofMinutes(2));
        return new RetakeRequest(null, start, start.plus(Duration.ofHours(3)), null);
    }

    /**
     * An exam for the six candidates by name.
     *
     * <p>Named rather than GLOBAL so the HR account setting the paper is not
     * also on the roster. These tests count the cohort precisely, and an extra
     * participant who never sat it would be a real member of every retake
     * cohort — correct behaviour, but it would obscure what is being asserted.
     */
    private Long publishedExam(String code) {
        Instant now = Instant.now();
        List<ExamRequest.AudienceRule> audience = staff.stream()
                .map(person -> new ExamRequest.AudienceRule("EMPLOYEE", person.getId()))
                .toList();

        Long examId = examService.create(hr, new ExamRequest(code, "Product knowledge", null, null,
                now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(3)),
                25, 5, 5, 0, 40, true, true, false, ExamFeedbackVisibility.IMMEDIATE,
                audience)).id();

        importService.importWorkbook(hr, examId, file(), true);
        examService.publish(hr, examId);
        return examId;
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "questions.xlsx", XLSX,
                ExamWorkbookFixture.threeSets(20).build());
    }

    private Employee employee(String code, String first, String last) {
        Employee person = new Employee();
        person.setEmployeeCode(code);
        person.setFirstName(first);
        person.setLastName(last);
        person.setOfficialEmail(code.toLowerCase(Locale.ROOT) + "@company.com");
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
        User account = new User();
        account.setUsername(username);
        account.setEmail(employee.getOfficialEmail());
        account.setPasswordHash("{noop}irrelevant");
        account.setEmployee(employee);
        account = userRepository.save(account);

        return UserPrincipal.fromClaims(account.getId(), employee.getId(), username,
                Set.of("EMPLOYEE"), permissions);
    }
}
