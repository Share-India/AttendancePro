package com.company.attendance.service;

import com.company.attendance.audit.AuditLogService;
import com.company.attendance.dto.response.exam.ExamImportResultResponse;
import com.company.attendance.dto.response.exam.ExamQualityReport;
import com.company.attendance.entity.Exam;
import com.company.attendance.entity.ExamQuestion;
import com.company.attendance.entity.ExamQuestionOption;
import com.company.attendance.entity.ExamQuestionSet;
import com.company.attendance.entity.ExamUpload;
import com.company.attendance.enums.AuditAction;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.ExamQuestionType;
import com.company.attendance.enums.ExamStatus;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.ExamQuestionSetRepository;
import com.company.attendance.repository.ExamRepository;
import com.company.attendance.repository.ExamUploadRepository;
import com.company.attendance.security.UserPrincipal;
import com.company.attendance.service.exam.ExamTemplateWriter;
import com.company.attendance.service.exam.ExamWorkbookParser;
import com.company.attendance.service.exam.ParsedQuestion;
import com.company.attendance.service.exam.QuestionQualityAnalyser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Loads a question workbook into an exam, or explains why it will not.
 *
 * <p>One path serves both the dry run and the real thing. A commit that finds
 * any problem writes nothing, so there is no state in which two of three sets
 * are loaded — an exam with two sets looks finished and deals a third of the
 * roster a paper that does not exist.
 *
 * <p>The workbook itself is never stored. It is an answer key, and an answer
 * key on disk is an answer key waiting to leak; the hash is enough to prove
 * later which file produced this question bank.
 */
@Service
public class ExamImportService {

    private static final Logger log = LoggerFactory.getLogger(ExamImportService.class);

    private final ExamRepository examRepository;
    private final ExamQuestionSetRepository questionSetRepository;
    private final ExamUploadRepository uploadRepository;
    private final ExamWorkbookParser parser;
    private final QuestionQualityAnalyser qualityAnalyser;
    private final ExamTemplateWriter templateWriter;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public ExamImportService(ExamRepository examRepository,
                             ExamQuestionSetRepository questionSetRepository,
                             ExamUploadRepository uploadRepository,
                             ExamWorkbookParser parser,
                             QuestionQualityAnalyser qualityAnalyser,
                             ExamTemplateWriter templateWriter,
                             AuditLogService auditLogService,
                             ObjectMapper objectMapper) {
        this.examRepository = examRepository;
        this.questionSetRepository = questionSetRepository;
        this.uploadRepository = uploadRepository;
        this.parser = parser;
        this.qualityAnalyser = qualityAnalyser;
        this.templateWriter = templateWriter;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    /** The blank workbook, generated from the same columns the parser reads. */
    public byte[] template() {
        return templateWriter.build();
    }

    @Transactional
    public ExamImportResultResponse importWorkbook(UserPrincipal principal, Long examId,
                                                   MultipartFile file, boolean commit) {
        Exam exam = examRepository.findById(examId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "That exam does not exist."));

        if (commit && !exam.getStatus().acceptsQuestions()) {
            // Replacing the paper after people have been assigned one would
            // invalidate every attempt already sat against it.
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "Questions can only be loaded while the exam is a draft. This one is "
                            + exam.getStatus().name().toLowerCase().replace('_', ' ') + ".");
        }
        if (exam.isRetake()) {
            throw new BusinessException(ErrorCode.POLICY_VIOLATION,
                    "A retake is sat from the original's question bank. Load the questions "
                            + "against the exam it retakes.");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Choose a workbook to upload.");
        }

        byte[] bytes = readFully(file);
        String sha256 = sha256(bytes);

        ExamWorkbookParser.Result parsed;
        try (InputStream stream = new ByteArrayInputStream(bytes)) {
            parsed = parser.parse(stream);
        } catch (IOException unreadable) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The uploaded file could not be read.");
        }

        ExamQualityReport quality = qualityAnalyser.analyse(parsed.questions());
        boolean accepted = !parsed.hasProblems() && !parsed.questions().isEmpty();

        if (!commit || !accepted) {
            return result(false, accepted, parsed, quality, file.getOriginalFilename(), sha256,
                    message(commit, accepted, parsed), exam.getMarksPerQuestion());
        }

        List<ExamQuestionSet> saved = writeQuestions(exam, parsed);
        recordUpload(exam, file.getOriginalFilename(), sha256, bytes.length, parsed, quality,
                principal);

        exam.setStatus(ExamStatus.QUESTIONS_LOADED);
        exam.setTotalMarks(saved.stream().mapToInt(ExamQuestionSet::getTotalMarks).max().orElse(0));
        examRepository.save(exam);

        auditLogService.record(AuditAction.EXAM_IMPORT, "Exam", exam.getId(),
                "Imported " + parsed.questions().size() + " questions into " + exam.getExamCode(),
                null,
                Map.of("questions", parsed.questions().size(),
                        "sets", saved.size(),
                        "sha256", sha256,
                        "qualityClean", quality.clean()));

        log.info("Exam {} loaded with {} questions across {} sets", exam.getExamCode(),
                parsed.questions().size(), saved.size());

        return result(true, true, parsed, quality, file.getOriginalFilename(), sha256,
                parsed.questions().size() + " questions loaded across " + saved.size() + " sets.",
                exam.getMarksPerQuestion());
    }

    // -----------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------

    /**
     * Replaces the question bank wholesale.
     *
     * <p>A partial load is the one outcome worth preventing outright, which is
     * why the caller has already established that the whole file parsed.
     */
    private List<ExamQuestionSet> writeQuestions(Exam exam, ExamWorkbookParser.Result parsed) {
        List<ExamQuestionSet> existing =
                questionSetRepository.findByExamIdOrderBySetCodeAsc(exam.getId());
        if (!existing.isEmpty()) {
            // Deleted through the entities, so the cascade to questions and
            // options is the mapping's rather than the schema's. A bulk JPQL
            // delete here relied on the database's own ON DELETE CASCADE —
            // which V18 declares and the entity-generated test schema does not,
            // so it passed against MySQL and failed against H2.
            //
            // It costs a lazy load per question. That is the right trade: this
            // runs once per upload, over at most a few hundred rows, on a path
            // nobody is waiting on.
            questionSetRepository.deleteAll(existing);
            questionSetRepository.flush();
        }

        List<ExamQuestionSet> sets = new ArrayList<>();
        for (String setCode : ExamWorkbookParser.SET_CODES) {
            List<ParsedQuestion> inSet = parsed.questionsInSet(setCode);
            if (inSet.isEmpty()) {
                continue;
            }
            ExamQuestionSet set = new ExamQuestionSet();
            set.setExam(exam);
            set.setSetCode(setCode);

            int total = 0;
            for (ParsedQuestion question : inSet) {
                int marks = question.marks() == null ? exam.getMarksPerQuestion() : question.marks();
                total += marks;
                set.addQuestion(buildQuestion(question, marks));
            }
            set.setQuestionCount(inSet.size());
            set.setTotalMarks(total);
            sets.add(questionSetRepository.save(set));
        }
        return sets;
    }

    private ExamQuestion buildQuestion(ParsedQuestion parsed, int marks) {
        ExamQuestion question = new ExamQuestion();
        question.setQuestionNumber(parsed.questionNumber());
        question.setTopic(parsed.topic());
        question.setDifficulty(parsed.difficulty());
        question.setQuestionType(parsed.isMultiChoice()
                ? ExamQuestionType.MULTI_CHOICE : ExamQuestionType.SINGLE_CHOICE);
        question.setQuestionText(parsed.questionText());
        question.setMarks(marks);
        question.setExplanation(parsed.explanation());
        question.setSourceReference(parsed.source());

        int order = 0;
        for (Map.Entry<String, String> entry : parsed.options().entrySet()) {
            ExamQuestionOption option = new ExamQuestionOption();
            option.setOptionLabel(entry.getKey());
            option.setOptionText(entry.getValue());
            option.setCorrect(parsed.correctLabels().contains(entry.getKey()));
            option.setDisplayOrder(order++);
            question.addOption(option);
        }
        return question;
    }

    private void recordUpload(Exam exam, String fileName, String sha256, long size,
                              ExamWorkbookParser.Result parsed, ExamQualityReport quality,
                              UserPrincipal principal) {
        ExamUpload upload = new ExamUpload();
        upload.setExam(exam);
        upload.setFileName(fileName == null ? "workbook.xlsx" : fileName);
        upload.setFileSha256(sha256);
        upload.setFileSize(size);
        upload.setQuestionCount(parsed.questions().size());
        upload.setSetCount((int) parsed.questions().stream()
                .map(ParsedQuestion::setCode).distinct().count());
        upload.setRejectedCount(parsed.problems().size());
        upload.setQualityReport(toJson(quality));
        upload.setUploadedBy(principal == null ? null : principal.getEmployeeId());
        upload.setUploadedAt(Instant.now());
        uploadRepository.save(upload);
    }

    // -----------------------------------------------------------------
    // Assembling the answer
    // -----------------------------------------------------------------

    private String message(boolean commit, boolean accepted, ExamWorkbookParser.Result parsed) {
        if (accepted) {
            return commit
                    ? "Imported."
                    : parsed.questions().size() + " questions read, and nothing was written. "
                            + "Upload again to load them.";
        }
        return parsed.problems().size() + " problem(s) found. Nothing was written — fix the file "
                + "and upload it again.";
    }

    private ExamImportResultResponse result(boolean committed, boolean accepted,
                                            ExamWorkbookParser.Result parsed,
                                            ExamQualityReport quality,
                                            String fileName, String sha256, String message,
                                            int defaultMarksPerQuestion) {
        // A workbook with no Marks column is the normal case, so the summary
        // applies the same fallback the questions themselves will get —
        // otherwise it reports a paper worth nothing.
        List<ExamImportResultResponse.SetSummary> sets = ExamWorkbookParser.SET_CODES.stream()
                .map(code -> new ExamImportResultResponse.SetSummary(code,
                        parsed.questionsInSet(code).size(),
                        parsed.questionsInSet(code).stream()
                                .mapToInt(q -> q.marks() == null ? defaultMarksPerQuestion : q.marks())
                                .sum()))
                .filter(summary -> summary.questionCount() > 0)
                .toList();

        return new ExamImportResultResponse(committed, accepted, parsed.questions().size(),
                sets.size(), sets, parsed.problems(), quality, fileName, sha256, message);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failed) {
            // The report is evidence, not the operation. Losing it must not
            // lose the import.
            log.warn("Quality report could not be serialised", failed);
            return null;
        }
    }

    private byte[] readFully(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException failed) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "The upload could not be read.");
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available", impossible);
        }
    }
}
