package com.company.attendance.integration;

import com.company.attendance.dto.request.exam.AnswerSaveRequest;
import com.company.attendance.dto.request.exam.ExamRequest;
import com.company.attendance.dto.response.exam.ExamAttemptOption;
import com.company.attendance.dto.response.exam.ExamAttemptQuestion;
import com.company.attendance.dto.response.exam.ExamAttemptResponse;
import com.company.attendance.dto.response.exam.ExamResultResponse;
import com.company.attendance.entity.Employee;
import com.company.attendance.entity.EmployeeShift;
import com.company.attendance.entity.ExamQuestion;
import com.company.attendance.entity.Office;
import com.company.attendance.entity.Shift;
import com.company.attendance.entity.User;
import com.company.attendance.enums.ExamFeedbackVisibility;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.EmployeeRepository;
import com.company.attendance.repository.EmployeeShiftRepository;
import com.company.attendance.repository.ExamAttemptRepository;
import com.company.attendance.repository.ExamQuestionRepository;
import com.company.attendance.repository.ExamRepository;
import com.company.attendance.repository.OfficeRepository;
import com.company.attendance.repository.ShiftRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.ExamAttemptService;
import com.company.attendance.service.ExamImportService;
import com.company.attendance.service.ExamService;
import com.company.attendance.support.DatabaseCleaner;
import com.company.attendance.support.ExamWorkbookFixture;
import com.company.attendance.util.PaperLayout;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rule the whole module is built around: <strong>the answer key must never
 * reach the browser during an attempt.</strong>
 *
 * <p>Even though these results are not consequential — this is a study exam,
 * not appraisal input — the rule still matters, because a candidate who can
 * read the key before answering learns nothing and the figures measure nothing.
 *
 * <p>It is not satisfied by care. It is satisfied structurally, by two disjoint
 * DTO families: the sitting one has nowhere to put a correctness flag, an
 * explanation or a source. These tests hold that shape in place, and the
 * regression they guard against would be both catastrophic and invisible — the
 * exam would carry on looking as though it worked.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExamSecurityIT {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument."
            + "spreadsheetml.sheet";

    /**
     * Anything whose name hints at the key. Matched against every field name in
     * the serialised attempt, at every depth.
     */
    private static final Pattern KEY_SHAPED =
            Pattern.compile("correct|answer|explanation|source", Pattern.CASE_INSENSITIVE);

    @Autowired ExamService examService;
    @Autowired ExamAttemptService attemptService;
    @Autowired ExamImportService importService;
    @Autowired ExamRepository examRepository;
    @Autowired ExamAttemptRepository attemptRepository;
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
    private Employee alice;
    private Employee bob;
    private Employee outsider;

    private UserPrincipal hr;
    private UserPrincipal alicePrincipal;
    private UserPrincipal bobPrincipal;
    private UserPrincipal outsiderPrincipal;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

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

        Employee hrPerson = employee("EMP7000", "Priya", "Sharma");
        alice = employee("EMP7001", "Alice", "Rao");
        bob = employee("EMP7002", "Bob", "Nair");
        outsider = employee("EMP7003", "Zane", "Kumar");

        hr = principal(hrPerson, "HR7000",
                Set.of("EXAM_MANAGE", "EXAM_QUESTION_IMPORT", "EXAM_RESULT_VIEW_ALL"));
        alicePrincipal = principal(alice, "USR7001", Set.of("EXAM_VIEW_SELF", "EXAM_ATTEMPT"));
        bobPrincipal = principal(bob, "USR7002", Set.of("EXAM_VIEW_SELF", "EXAM_ATTEMPT"));
        outsiderPrincipal = principal(outsider, "USR7003",
                Set.of("EXAM_VIEW_SELF", "EXAM_ATTEMPT"));
    }

    // -------------------------------------------------------- the key itself

    @Test
    @DisplayName("nothing in a live attempt's JSON is shaped like the answer key")
    void attemptPayloadCarriesNoKey() {
        Long examId = publishedExam("PK-LEAK", ExamFeedbackVisibility.IMMEDIATE);
        ExamAttemptResponse paper = attemptService.start(alicePrincipal, examId);

        // Answer one, so the payload is as full as it ever gets.
        attemptService.saveAnswer(alicePrincipal, paper.attemptId(), new AnswerSaveRequest(
                paper.questions().get(0).questionId(), List.of("B")));

        JsonNode tree = objectMapper.valueToTree(
                attemptService.resume(alicePrincipal, paper.attemptId()));

        List<String> offending = new ArrayList<>();
        walk(tree, "", offending);

        assertThat(offending)
                .as("the sitting payload must not contain a field whose name hints at the key")
                .isEmpty();

        // And the sitting payload does carry what a candidate needs, so the
        // assertion above is not passing because the payload is empty.
        assertThat(tree.get("questions")).hasSize(20);
        assertThat(tree.get("questions").get(0).get("options")).hasSize(4);
        assertThat(tree.get("questions").get(0).get("questionText").asText()).isNotBlank();
        assertThat(tree.get("expiresAt")).isNotNull();
    }

    @Test
    @DisplayName("the sitting DTOs have no field the key could be put in, by construction")
    void sittingDtosHaveNowhereToPutTheKey() {
        for (Class<?> type : List.of(ExamAttemptResponse.class, ExamAttemptQuestion.class,
                ExamAttemptOption.class)) {
            assertThat(type.isRecord())
                    .as(type.getSimpleName() + " should be a record")
                    .isTrue();

            for (RecordComponent component : type.getRecordComponents()) {
                assertThat(KEY_SHAPED.matcher(component.getName()).find())
                        .as(type.getSimpleName() + "." + component.getName()
                                + " is shaped like the answer key. The sitting DTOs must have "
                                + "nowhere to put it — do not add the field and null it out.")
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("the result payload does carry the key, so the leak test can tell the difference")
    void resultPayloadCarriesTheKeyOnPurpose() {
        Long examId = publishedExam("PK-AFTER", ExamFeedbackVisibility.IMMEDIATE);
        ExamAttemptResponse paper = attemptService.start(alicePrincipal, examId);
        ExamResultResponse result = attemptService.submit(alicePrincipal, paper.attemptId());

        JsonNode tree = objectMapper.valueToTree(result);
        List<String> found = new ArrayList<>();
        walk(tree, "", found);

        // The complement of the test above. Without this, a change that emptied
        // the result payload would make the leak test pass for the wrong reason.
        assertThat(found)
                .as("the result is where the key belongs")
                .isNotEmpty();
        assertThat(result.questions()).isNotEmpty();
        assertThat(result.questions().get(0).explanation()).isNotBlank();
        assertThat(result.questions().get(0).correctLabels()).isNotEmpty();
    }

    @Test
    @DisplayName("feedback held back until the window closes is not in the result either")
    void feedbackCanBeHeldBack() {
        Long examId = publishedExam("PK-HOLD", ExamFeedbackVisibility.AFTER_CLOSE);
        ExamAttemptResponse paper = attemptService.start(alicePrincipal, examId);
        ExamResultResponse result = attemptService.submit(alicePrincipal, paper.attemptId());

        // The score is theirs the moment they hand in. The worked answers are
        // withheld so the first person out of the room cannot brief the queue.
        assertThat(result.feedbackVisible()).isFalse();
        assertThat(result.questions()).isEmpty();
        assertThat(result.score()).isNotNull();
        assertThat(result.questionCount()).isEqualTo(20);
    }

    @Test
    @DisplayName("a question from another set cannot be answered, or used to probe the other papers")
    void cannotReachAnotherPapersQuestions() {
        Long examId = publishedExam("PK-PROBE", ExamFeedbackVisibility.IMMEDIATE);
        ExamAttemptResponse paper = attemptService.start(alicePrincipal, examId);

        ExamQuestion elsewhere = questionRepository.findBank(examId).stream()
                .filter(question -> !question.getQuestionSet().getSetCode().equals(paper.setCode()))
                .findFirst().orElseThrow();

        assertThatThrownBy(() -> attemptService.saveAnswer(alicePrincipal, paper.attemptId(),
                new AnswerSaveRequest(elsewhere.getId(), List.of("A"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not on your paper");
    }

    // ------------------------------------------------------- whose paper is it

    @Test
    @DisplayName("one candidate cannot read another's paper, whatever id is in the URL")
    void cannotReadSomebodyElsesAttempt() {
        Long examId = publishedExam("PK-MINE", ExamFeedbackVisibility.IMMEDIATE);
        ExamAttemptResponse alicePaper = attemptService.start(alicePrincipal, examId);

        assertThatThrownBy(() -> attemptService.resume(bobPrincipal, alicePaper.attemptId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not your attempt");

        assertThatThrownBy(() -> attemptService.saveAnswer(bobPrincipal, alicePaper.attemptId(),
                new AnswerSaveRequest(alicePaper.questions().get(0).questionId(), List.of("A"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not your attempt");

        assertThatThrownBy(() -> attemptService.submit(bobPrincipal, alicePaper.attemptId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not your attempt");
    }

    @Test
    @DisplayName("one candidate cannot read another's result")
    void cannotReadSomebodyElsesResult() {
        Long examId = publishedExam("PK-RESULT", ExamFeedbackVisibility.IMMEDIATE);
        ExamAttemptResponse alicePaper = attemptService.start(alicePrincipal, examId);
        attemptService.submit(alicePrincipal, alicePaper.attemptId());

        assertThatThrownBy(() -> attemptService.result(bobPrincipal, alicePaper.attemptId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not your attempt");
    }

    @Test
    @DisplayName("the result is refused until the paper is actually in")
    void resultRefusedBeforeSubmit() {
        Long examId = publishedExam("PK-EARLY", ExamFeedbackVisibility.IMMEDIATE);
        ExamAttemptResponse paper = attemptService.start(alicePrincipal, examId);

        // The endpoint that carries the key refuses outright while the paper is
        // open. It is a separate endpoint from the paper for exactly this reason.
        assertThatThrownBy(() -> attemptService.result(alicePrincipal, paper.attemptId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("once you submit");
    }

    @Test
    @DisplayName("somebody who is not on the roster cannot start the exam")
    void nonParticipantCannotStart() {
        Long examId = examService.create(hr, request("PK-CLOSED",
                ExamFeedbackVisibility.IMMEDIATE,
                List.of(new ExamRequest.AudienceRule("EMPLOYEE", alice.getId()),
                        new ExamRequest.AudienceRule("EMPLOYEE", bob.getId())))).id();
        importService.importWorkbook(hr, examId, file(), true);
        examService.publish(hr, examId);

        assertThatThrownBy(() -> attemptService.start(outsiderPrincipal, examId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not on the list");

        // And they see nothing at all, rather than an exam they cannot enter.
        assertThat(examService.myExams(outsiderPrincipal)).isEmpty();
        assertThat(examService.myExams(alicePrincipal)).hasSize(1);
    }

    @Test
    @DisplayName("an account with no employee record cannot sit anything")
    void systemAccountCannotSit() {
        Long examId = publishedExam("PK-SYSTEM", ExamFeedbackVisibility.IMMEDIATE);

        UserPrincipal integration = UserPrincipal.fromClaims(999L, null, "integration",
                Set.of("EMPLOYEE"), Set.of("EXAM_VIEW_SELF", "EXAM_ATTEMPT"));

        assertThatThrownBy(() -> attemptService.start(integration, examId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not linked to an employee record");

        assertThat(examService.myExams(integration)).isEmpty();
    }

    @Test
    @DisplayName("the shuffled labels a candidate clicks are translated, not trusted")
    void clicksAreTranslatedThroughTheStoredLayout() {
        Long examId = publishedExam("PK-SHUFFLE", ExamFeedbackVisibility.IMMEDIATE);
        ExamAttemptResponse paper = attemptService.start(alicePrincipal, examId);

        var attempt = attemptRepository.findById(paper.attemptId()).orElseThrow();
        PaperLayout layout = PaperLayout.from(objectMapper,
                attempt.getQuestionOrder(), attempt.getOptionOrder());

        Long questionId = paper.questions().get(0).questionId();
        String canonical = questionRepository
                .findPaper(questionRepository.findById(questionId).orElseThrow()
                        .getQuestionSet().getId())
                .stream()
                .filter(question -> question.getId().equals(questionId))
                .findFirst().orElseThrow()
                .correctLabels().get(0);

        String displayed = layout.displayedFor(questionId, canonical);

        // Click the label the candidate actually sees, and the answer stored is
        // the workbook's label — which is what keeps the shuffle out of scoring
        // and out of the analytics.
        attemptService.saveAnswer(alicePrincipal, paper.attemptId(),
                new AnswerSaveRequest(questionId, List.of(displayed)));

        ExamResultResponse result = attemptService.submit(alicePrincipal, paper.attemptId());

        assertThat(result.score()).isEqualTo(5);
        assertThat(result.questions())
                .filteredOn(question -> question.questionId().equals(questionId))
                .singleElement()
                .satisfies(question -> {
                    assertThat(question.correct()).isTrue();
                    assertThat(question.chosenLabels()).containsExactly(displayed);
                    assertThat(question.correctLabels()).containsExactly(displayed);
                });
    }

    // ----------------------------------------------------------------- helpers

    /** Collects every field name, at every depth, whose name hints at the key. */
    private void walk(JsonNode node, String path, List<String> offending) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String here = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
                if (KEY_SHAPED.matcher(entry.getKey()).find()) {
                    offending.add(here);
                }
                walk(entry.getValue(), here, offending);
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", offending);
            }
        }
    }

    private Long publishedExam(String code, ExamFeedbackVisibility feedback) {
        Long examId = examService.create(hr, request(code, feedback,
                List.of(new ExamRequest.AudienceRule("GLOBAL", null)))).id();
        importService.importWorkbook(hr, examId, file(), true);
        examService.publish(hr, examId);
        return examId;
    }

    private ExamRequest request(String code, ExamFeedbackVisibility feedback,
                                List<ExamRequest.AudienceRule> audience) {
        Instant now = Instant.now();
        return new ExamRequest(code, "Product knowledge", null, null,
                now.minus(Duration.ofMinutes(5)), now.plus(Duration.ofHours(3)),
                25, 5, 5, 0, 40, true, true, false, feedback, audience);
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
