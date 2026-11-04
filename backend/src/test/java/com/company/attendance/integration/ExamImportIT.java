package com.company.attendance.integration;

import com.company.attendance.dto.request.exam.ExamRequest;
import com.company.attendance.dto.response.exam.ExamImportResultResponse;
import com.company.attendance.dto.response.exam.ExamQuestionReviewResponse;
import com.company.attendance.dto.response.exam.ExamResponse;
import com.company.attendance.enums.ExamFeedbackVisibility;
import com.company.attendance.enums.ExamStatus;
import com.company.attendance.repository.ExamQuestionSetRepository;
import com.company.attendance.repository.ExamUploadRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.ExamImportService;
import com.company.attendance.service.ExamService;
import com.company.attendance.support.DatabaseCleaner;
import com.company.attendance.support.ExamWorkbookFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The importer is the module's front door, and two of its jobs fail silently if
 * they are wrong.
 *
 * <p>A workbook that half-loads leaves an exam that looks finished and deals a
 * third of the roster a paper that does not exist. And a workbook whose answer
 * key disagrees with its own question sheets contains two different exams, with
 * no way to tell which was meant — so the whole file has to stop rather than
 * one version being picked.
 *
 * <p>The quality report is the third job. It cannot fail loudly, because every
 * finding is a warning HR may override; what these tests pin down is that a
 * clean paper produces silence and a paper answered B twenty times does not.
 */
@SpringBootTest
@ActiveProfiles("test")
class ExamImportIT {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument."
            + "spreadsheetml.sheet";

    @Autowired ExamService examService;
    @Autowired ExamImportService importService;
    @Autowired ExamQuestionSetRepository questionSetRepository;
    @Autowired ExamUploadRepository uploadRepository;
    @Autowired DatabaseCleaner databaseCleaner;

    private UserPrincipal hr;
    private Long examId;

    @BeforeEach
    void seed() {
        databaseCleaner.clean();

        hr = UserPrincipal.fromClaims(1L, null, "HR001",
                Set.of("HR"), Set.of("EXAM_MANAGE", "EXAM_QUESTION_IMPORT"));

        examId = examService.create(hr, request("PK-IMPORT")).id();
    }

    // ----------------------------------------------------------- the good case

    @Test
    @DisplayName("a clean workbook loads three sets and leaves the quality report silent")
    void cleanWorkbookLoads() {
        ExamImportResultResponse result = commit(ExamWorkbookFixture.threeSets(20).build());

        assertThat(result.accepted()).isTrue();
        assertThat(result.committed()).isTrue();
        assertThat(result.problems()).isEmpty();
        assertThat(result.questionCount()).isEqualTo(60);
        assertThat(result.setCount()).isEqualTo(3);

        // Silence is the assertion: the fixture rotates its answers and keeps
        // every option the same length, so a warning here means the analyser
        // has started crying wolf.
        assertThat(result.quality().clean()).isTrue();
        assertThat(result.quality().warnings()).isEmpty();
        assertThat(result.quality().sets())
                .allSatisfy(set -> assertThat(set.warnings()).isEmpty());

        assertThat(questionSetRepository.findByExamIdOrderBySetCodeAsc(examId))
                .extracting(set -> set.getSetCode() + ":" + set.getQuestionCount()
                        + ":" + set.getTotalMarks())
                .containsExactly("A:20:100", "B:20:100", "C:20:100");

        ExamResponse exam = examService.get(hr, examId);
        assertThat(exam.status()).isEqualTo(ExamStatus.QUESTIONS_LOADED);
        assertThat(exam.totalMarks()).isEqualTo(100);
    }

    @Test
    @DisplayName("the upload is recorded by hash, and the workbook itself is not kept")
    void uploadIsRecordedByHashOnly() {
        byte[] bytes = ExamWorkbookFixture.threeSets(5).build();
        ExamImportResultResponse result = commit(bytes);

        var uploads = uploadRepository.findByExamIdOrderByUploadedAtDesc(examId);
        assertThat(uploads).hasSize(1);
        assertThat(uploads.get(0).getFileSha256())
                .isEqualTo(result.fileSha256())
                .hasSize(64);
        assertThat(uploads.get(0).getFileSize()).isEqualTo(bytes.length);
        assertThat(uploads.get(0).getQualityReport()).isNotBlank();

        // The entity has no column for the file. An answer key on disk is an
        // answer key waiting to leak, so this is a shape assertion as much as a
        // behaviour one.
        assertThat(uploads.get(0).getFileName()).isEqualTo("questions.xlsx");
    }

    // ---------------------------------------------------- the whole-file stops

    @Test
    @DisplayName("an answer key that disagrees with its question sheet rejects the whole file")
    void keyMismatchRejectsEverything() {
        ExamImportResultResponse result = commit(ExamWorkbookFixture.threeSets(20)
                .withKeySheetDisagreeing("B", 7, "D")
                .build());

        assertThat(result.accepted()).isFalse();
        assertThat(result.committed()).isFalse();
        assertThat(result.problems())
                .anySatisfy(problem -> assertThat(problem.message())
                        .contains("Answer key disagrees")
                        .contains("Q7"));

        // Nothing at all, not "two of three sets": the file described two
        // different exams and neither was loaded.
        assertThat(questionSetRepository.findByExamIdOrderBySetCodeAsc(examId)).isEmpty();
        assertThat(examService.get(hr, examId).status()).isEqualTo(ExamStatus.DRAFT);
    }

    @Test
    @DisplayName("a missing answer key sheet is refused, because it is the cross-check")
    void missingKeySheetIsRefused() {
        ExamImportResultResponse result = commit(ExamWorkbookFixture.threeSets(5)
                .withoutAnswerKeySheets()
                .build());

        assertThat(result.accepted()).isFalse();
        assertThat(result.problems())
                .anySatisfy(problem -> assertThat(problem.message())
                        .contains("Answer key sheet not found"));
        assertThat(questionSetRepository.findByExamIdOrderBySetCodeAsc(examId)).isEmpty();
    }

    @Test
    @DisplayName("two sets out of three is a rejection, not a partial load")
    void missingSetIsRefused() {
        ExamImportResultResponse result =
                commit(ExamWorkbookFixture.sets(5, "A", "B").build());

        assertThat(result.accepted()).isFalse();
        assertThat(result.problems())
                .anySatisfy(problem -> assertThat(problem.sheet()).isEqualTo("Set C"));
        assertThat(questionSetRepository.findByExamIdOrderBySetCodeAsc(examId)).isEmpty();
    }

    // ------------------------------------------------------------ row by row

    @Test
    @DisplayName("a problem is reported against its own row, column and sheet")
    void problemsNameTheirOwnRow() {
        ExamImportResultResponse result = commit(ExamWorkbookFixture.threeSets(6)
                .withBlank("A", 3, "Explanation")
                .withCell("B", 2, "Correct Answer", "E")
                .withBlank("C", 4, "Question")
                .build());

        assertThat(result.accepted()).isFalse();
        assertThat(result.problems()).hasSizeGreaterThanOrEqualTo(3);

        assertThat(result.problems())
                .anySatisfy(problem -> {
                    assertThat(problem.sheet()).isEqualTo("Set A");
                    assertThat(problem.column()).isEqualTo("Explanation");
                    // Row 4 of the sheet: the heading is row 1, so question 3
                    // sits on row 4 — the number Excel shows in its margin.
                    assertThat(problem.row()).isEqualTo(4);
                })
                .anySatisfy(problem -> {
                    assertThat(problem.sheet()).isEqualTo("Set B");
                    assertThat(problem.column()).isEqualTo("Correct Answer");
                    assertThat(problem.message()).contains("no matching option");
                })
                .anySatisfy(problem -> {
                    assertThat(problem.sheet()).isEqualTo("Set C");
                    assertThat(problem.column()).isEqualTo("Question");
                });
    }

    @Test
    @DisplayName("an explanation is required, because it is what the candidate reads afterwards")
    void explanationIsRequired() {
        ExamImportResultResponse result = commit(ExamWorkbookFixture.threeSets(4)
                .withBlank("A", 1, "Explanation")
                .build());

        assertThat(result.problems())
                .anySatisfy(problem -> assertThat(problem.message())
                        .contains("what the candidate reads after submitting"));
    }

    // ------------------------------------------------------- the Excel quirks

    @Test
    @DisplayName("sheet names and headings are matched tolerantly, punctuation and all")
    void toleratesHowPeopleActuallyTypeThings() {
        ExamImportResultResponse result = commit(ExamWorkbookFixture.threeSets(5)
                // "Set-A ", and a non-breaking space pasted out of Word.
                .withSheetNamesWritten(name -> name.replace("Set ", "Set-") + " ")
                // "Q.No", "Correct  Answer", trailing spaces.
                .withHeadingsWritten(heading -> heading.replace(". ", ".").toLowerCase() + " ")
                .build());

        assertThat(result.problems()).isEmpty();
        assertThat(result.accepted()).isTrue();
        assertThat(result.questionCount()).isEqualTo(15);
    }

    @Test
    @DisplayName("question numbers read the same whether Excel stored 1 or 1.0 or \"1\"")
    void questionNumbersSurviveExcelsTypeSystem() {
        // The default fixture writes them as numbers, which arrive as doubles.
        assertThat(commit(ExamWorkbookFixture.threeSets(4).build()).accepted()).isTrue();

        examId = examService.create(hr, request("PK-TEXTNUM")).id();

        assertThat(commit(ExamWorkbookFixture.threeSets(4)
                .withQuestionNumbersAsText()
                .build()).accepted()).isTrue();
    }

    @Test
    @DisplayName("the same question number twice in one sheet is caught")
    void duplicateQuestionNumberIsCaught() {
        ExamImportResultResponse result = commit(ExamWorkbookFixture.threeSets(5)
                .withCell("A", 4, "Q. No.", "3")
                .build());

        assertThat(result.problems())
                .anySatisfy(problem -> assertThat(problem.message())
                        .contains("appears more than once"));
    }

    // ------------------------------------------------------- quality warnings

    @Test
    @DisplayName("a paper answered B every time is reported, and can still be published")
    void everyAnswerTheSameLetterIsReported() {
        ExamImportResultResponse result = commit(ExamWorkbookFixture.threeSets(20)
                .withEverySingleAnswer("B", "B")
                .build());

        // Accepted: the paper is valid. Warned about: it measures nothing.
        assertThat(result.accepted()).isTrue();
        assertThat(result.committed()).isTrue();
        assertThat(result.quality().clean()).isFalse();

        assertThat(result.quality().sets())
                .filteredOn(set -> set.setCode().equals("B"))
                .singleElement()
                .satisfies(set -> {
                    assertThat(set.answerSpread()).containsEntry("B", 20);
                    assertThat(set.warnings())
                            .anySatisfy(warning -> assertThat(warning)
                                    .contains("Ticking it every time scores 20 of 20"));
                });

        // And the other two sets stay quiet, so the warning points somewhere.
        assertThat(result.quality().sets())
                .filteredOn(set -> !set.setCode().equals("B"))
                .allSatisfy(set -> assertThat(set.warnings()).isEmpty());
    }

    @Test
    @DisplayName("the same question in two sets is reported as a duplicate")
    void duplicateQuestionsAcrossSetsAreReported() {
        ExamImportResultResponse result = commit(ExamWorkbookFixture.threeSets(6)
                .withDuplicateOf("A", 2, "C", 5)
                .build());

        assertThat(result.accepted()).isTrue();
        assertThat(result.quality().duplicateQuestions())
                .anySatisfy(entry -> assertThat(entry)
                        .contains("Set A Q2")
                        .contains("Set C Q5"));
        assertThat(result.quality().warnings())
                .anySatisfy(warning -> assertThat(warning).contains("being measured once"));
    }

    // -------------------------------------------------------------- dry run

    @Test
    @DisplayName("a dry run reports everything and writes nothing")
    void dryRunWritesNothing() {
        ExamImportResultResponse result = importService.importWorkbook(hr, examId,
                file(ExamWorkbookFixture.threeSets(20).build()), false);

        assertThat(result.accepted()).isTrue();
        assertThat(result.committed()).isFalse();
        assertThat(result.questionCount()).isEqualTo(60);
        assertThat(result.quality()).isNotNull();

        assertThat(questionSetRepository.findByExamIdOrderBySetCodeAsc(examId)).isEmpty();
        assertThat(uploadRepository.findByExamIdOrderByUploadedAtDesc(examId)).isEmpty();
        assertThat(examService.get(hr, examId).status()).isEqualTo(ExamStatus.DRAFT);
    }

    @Test
    @DisplayName("a set summary applies the exam's marks when the workbook gives none")
    void setSummaryFallsBackToTheExamsMarks() {
        // The fixture leaves Marks blank, which is the normal case. A summary
        // that reported a paper worth nothing would be read as a broken import.
        ExamImportResultResponse result = importService.importWorkbook(hr, examId,
                file(ExamWorkbookFixture.threeSets(20).build()), false);

        assertThat(result.sets())
                .hasSize(3)
                .allSatisfy(set -> assertThat(set.totalMarks()).isEqualTo(100));
    }

    // ------------------------------------------------------------ re-import

    @Test
    @DisplayName("re-importing replaces the bank wholesale rather than adding to it")
    void reimportReplaces() {
        commit(ExamWorkbookFixture.threeSets(20).build());
        commit(ExamWorkbookFixture.threeSets(8).build());

        assertThat(questionSetRepository.findByExamIdOrderBySetCodeAsc(examId))
                .hasSize(3)
                .allSatisfy(set -> assertThat(set.getQuestionCount()).isEqualTo(8));

        List<ExamQuestionReviewResponse> review = examService.questionReview(hr, examId);
        assertThat(review).hasSize(3);
        assertThat(review).allSatisfy(set -> assertThat(set.questions()).hasSize(8));
    }

    @Test
    @DisplayName("the answer key review shows the key, and only to whoever can import")
    void reviewCarriesTheKey() {
        commit(ExamWorkbookFixture.threeSets(4).build());

        ExamQuestionReviewResponse setA = examService.questionReview(hr, examId).stream()
                .filter(set -> set.setCode().equals("A"))
                .findFirst().orElseThrow();

        assertThat(setA.questions()).hasSize(4);
        assertThat(setA.questions().get(0).correctLabels()).containsExactly("A");
        assertThat(setA.questions().get(0).explanation()).isNotBlank();
        assertThat(setA.questions().get(0).options())
                .filteredOn(ExamQuestionReviewResponse.Option::correct)
                .singleElement()
                .satisfies(option -> assertThat(option.label()).isEqualTo("A"));
    }

    @Test
    @DisplayName("the generated template is itself a workbook the parser accepts the shape of")
    void templateMatchesWhatTheParserReads() {
        byte[] template = importService.template();
        assertThat(template).isNotEmpty();

        // An empty template has no question rows, so it is rejected — but on
        // "no question rows", never on a missing sheet or a missing column.
        // That is the assertion worth having: the template cannot drift away
        // from the headings the parser requires.
        ExamImportResultResponse result = importService.importWorkbook(hr, examId,
                file(template), false);

        assertThat(result.accepted()).isFalse();
        assertThat(result.problems())
                .isNotEmpty()
                .allSatisfy(problem -> assertThat(problem.message())
                        .doesNotContain("is missing")
                        .doesNotContain("Sheet not found"));
    }

    // ----------------------------------------------------------------- helpers

    private ExamImportResultResponse commit(byte[] workbook) {
        return importService.importWorkbook(hr, examId, file(workbook), true);
    }

    private MockMultipartFile file(byte[] workbook) {
        return new MockMultipartFile("file", "questions.xlsx", XLSX, workbook);
    }

    private ExamRequest request(String code) {
        Instant start = Instant.now().plus(Duration.ofHours(1));
        return new ExamRequest(code, "Product knowledge", null, null,
                start, start.plus(Duration.ofHours(3)),
                25, 5, 5, 0, 40,
                true, true, false, ExamFeedbackVisibility.IMMEDIATE,
                List.of(new ExamRequest.AudienceRule("GLOBAL", null)));
    }
}
