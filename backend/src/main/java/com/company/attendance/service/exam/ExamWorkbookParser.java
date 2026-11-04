package com.company.attendance.service.exam;

import com.company.attendance.dto.response.exam.ImportProblem;
import com.company.attendance.enums.ExamDifficulty;
import com.company.attendance.util.SheetReader;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns an uploaded workbook into questions, or into a list of reasons it is
 * not one.
 *
 * <p>Two rules shape everything here. Columns are found by heading, never by
 * position, because the real files put Product and Difficulty in a different
 * order from the template and neither is wrong. And a problem is reported
 * against its own row rather than failing the file with one message, because
 * "the upload failed" sends somebody to read sixty rows.
 *
 * <p>The one thing that does fail the whole file is a set sheet disagreeing
 * with its own answer key. That is the day somebody corrected an answer in one
 * place and not the other, and importing either version would be a guess.
 */
@Component
public class ExamWorkbookParser {

    public static final List<String> SET_CODES = List.of("A", "B", "C");
    private static final List<String> OPTION_LABELS = List.of("A", "B", "C", "D", "E", "F");
    private static final int MIN_OPTIONS = 2;

    /** Everything found, plus everything wrong with it. */
    public record Result(List<ParsedQuestion> questions, List<ImportProblem> problems) {

        public boolean hasProblems() {
            return !problems.isEmpty();
        }

        public List<ParsedQuestion> questionsInSet(String setCode) {
            return questions.stream().filter(q -> q.setCode().equals(setCode)).toList();
        }
    }

    public Result parse(InputStream workbookStream) {
        List<ParsedQuestion> questions = new ArrayList<>();
        List<ImportProblem> problems = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(workbookStream)) {
            for (String setCode : SET_CODES) {
                parseSet(workbook, setCode, questions, problems);
            }
            if (questions.isEmpty() && problems.isEmpty()) {
                problems.add(ImportProblem.atFile(
                        "No questions were found. Expected sheets named 'Set A', 'Set B' and "
                                + "'Set C'; this file has: "
                                + String.join(", ", SheetReader.sheetNames(workbook))));
            }
        } catch (IOException | RuntimeException unreadable) {
            problems.add(ImportProblem.atFile(
                    "This file could not be read as an Excel workbook ("
                            + unreadable.getMessage() + ")"));
        }
        return new Result(List.copyOf(questions), List.copyOf(problems));
    }

    private void parseSet(Workbook workbook, String setCode,
                          List<ParsedQuestion> questions, List<ImportProblem> problems) {
        Optional<Sheet> sheet = SheetReader.findSheet(workbook,
                "Set " + setCode, "Set-" + setCode, "Set" + setCode);

        if (sheet.isEmpty()) {
            // Naming what was found as well as what was wanted: importing two
            // sets of three and saying nothing is the worst available outcome,
            // so this is loud and specific.
            problems.add(ImportProblem.atSheet("Set " + setCode,
                    "Sheet not found. The workbook contains: "
                            + String.join(", ", SheetReader.sheetNames(workbook))));
            return;
        }

        SheetReader reader = SheetReader.of(sheet.get());
        List<ImportProblem> missingHeadings = checkRequiredHeadings(reader);
        if (!missingHeadings.isEmpty()) {
            problems.addAll(missingHeadings);
            return;
        }

        Set<Integer> seenNumbers = new LinkedHashSet<>();
        List<ParsedQuestion> parsed = new ArrayList<>();

        for (int row : reader.dataRows()) {
            parseRow(reader, setCode, row, seenNumbers, problems).ifPresent(parsed::add);
        }

        if (parsed.isEmpty() && problems.stream().noneMatch(p -> setCode.equals(sheetSetCode(p)))) {
            problems.add(ImportProblem.atSheet(reader.sheetName(), "No question rows were found"));
        }

        crossCheckAnswerKey(workbook, setCode, parsed, problems);
        questions.addAll(parsed);
    }

    /** Which set a problem belongs to, so "no rows found" is not reported twice. */
    private String sheetSetCode(ImportProblem problem) {
        String sheet = problem.sheet();
        return sheet == null ? null
                : sheet.replaceAll("(?i)[^a-z]", "")
                        .replaceAll("(?i)^set", "")
                        .toUpperCase(Locale.ENGLISH);
    }

    private List<ImportProblem> checkRequiredHeadings(SheetReader reader) {
        List<ImportProblem> missing = new ArrayList<>();
        record Required(String label, String[] aliases) { }
        List<Required> required = List.of(
                new Required("Q. No.", new String[] {"Q. No.", "Q No", "QNo", "Question No", "S. No."}),
                new Required("Question", new String[] {"Question", "Question Text"}),
                new Required("Option A", new String[] {"Option A", "A"}),
                new Required("Option B", new String[] {"Option B", "B"}),
                new Required("Correct Answer", new String[] {"Correct Answer", "Answer", "Key"}));

        for (Required column : required) {
            if (!reader.has(column.aliases())) {
                missing.add(ImportProblem.atSheet(reader.sheetName(),
                        "Required column '" + column.label() + "' is missing. Columns found: "
                                + String.join(", ", reader.headings())));
            }
        }
        return missing;
    }

    private Optional<ParsedQuestion> parseRow(SheetReader reader, String setCode, int row,
                                              Set<Integer> seenNumbers,
                                              List<ImportProblem> problems) {
        String sheet = reader.sheetName();

        Optional<Integer> number = reader.integer(row, "Q. No.", "Q No", "QNo", "Question No", "S. No.");
        if (number.isEmpty()) {
            problems.add(ImportProblem.atRow(sheet, row, "Q. No.", "Not a whole number, or blank"));
            return Optional.empty();
        }
        if (!seenNumbers.add(number.get())) {
            problems.add(ImportProblem.atRow(sheet, row, "Q. No.",
                    "Question number " + number.get() + " appears more than once in this sheet"));
            return Optional.empty();
        }

        String questionText = reader.text(row, "Question", "Question Text");
        if (questionText == null) {
            problems.add(ImportProblem.atRow(sheet, row, "Question", "The question text is blank"));
            return Optional.empty();
        }

        Map<String, String> options = new LinkedHashMap<>();
        for (String label : OPTION_LABELS) {
            String text = reader.text(row, "Option " + label, label);
            if (text != null) {
                options.put(label, text);
            }
        }
        if (options.size() < MIN_OPTIONS) {
            problems.add(ImportProblem.atRow(sheet, row, "Option A/B",
                    "A question needs at least " + MIN_OPTIONS + " options; found " + options.size()));
            return Optional.empty();
        }

        List<String> correct = parseCorrectAnswer(reader.text(row, "Correct Answer", "Answer", "Key"));
        if (correct.isEmpty()) {
            problems.add(ImportProblem.atRow(sheet, row, "Correct Answer",
                    "No answer given. Expected a letter such as A, or A,C for a multi-answer question"));
            return Optional.empty();
        }
        List<String> unknown = correct.stream().filter(label -> !options.containsKey(label)).toList();
        if (!unknown.isEmpty()) {
            problems.add(ImportProblem.atRow(sheet, row, "Correct Answer",
                    "Answer '" + String.join(",", unknown) + "' has no matching option. "
                            + "This question has options " + String.join(", ", options.keySet())));
            return Optional.empty();
        }

        String explanation = reader.text(row, "Explanation", "Rationale");
        if (explanation == null) {
            // A warning rather than a rejection would mean a candidate sees a
            // blank where the learning is, which is the whole point of the exam.
            problems.add(ImportProblem.atRow(sheet, row, "Explanation",
                    "An explanation is required; it is what the candidate reads after submitting"));
            return Optional.empty();
        }

        return Optional.of(new ParsedQuestion(
                setCode,
                row,
                number.get(),
                normaliseTopic(reader.text(row, "Topic", "Product", "Subject")),
                parseDifficulty(reader.text(row, "Difficulty", "Level")),
                questionText,
                options,
                correct,
                explanation,
                reader.text(row, "Source", "Source Reference", "Reference"),
                reader.integer(row, "Marks", "Mark").orElse(null)));
    }

    /** "a", " A ", "A,C", "A, C", "A;C" and "AC" all mean the same thing. */
    private List<String> parseCorrectAnswer(String raw) {
        String cleaned = SheetReader.upperTrimmed(raw);
        if (cleaned == null || cleaned.isBlank()) {
            return List.of();
        }
        Set<String> labels = new LinkedHashSet<>();
        for (char c : cleaned.toCharArray()) {
            if (Character.isLetter(c)) {
                labels.add(String.valueOf(c));
            }
        }
        return labels.stream().sorted().toList();
    }

    /**
     * Case and spelling vary — real files say "Easy", the template says "EASY"
     * and somebody will write "Medium". An unrecognised value is left null
     * rather than guessed at: difficulty is optional, and a wrong tag is worse
     * than none because it is read as evidence.
     */
    private ExamDifficulty parseDifficulty(String raw) {
        String cleaned = SheetReader.upperTrimmed(raw);
        if (cleaned == null) {
            return null;
        }
        return switch (cleaned) {
            case "EASY", "E", "SIMPLE" -> ExamDifficulty.EASY;
            case "MODERATE", "MEDIUM", "M", "AVERAGE" -> ExamDifficulty.MODERATE;
            case "HARD", "H", "DIFFICULT", "TOUGH" -> ExamDifficulty.HARD;
            default -> null;
        };
    }

    /**
     * Collapses spacing and title-cases, so "health insurance" and
     * "Health  Insurance " become one topic rather than two. This is what the
     * per-question analytics group by, and a split topic is a split training
     * need that nobody notices.
     */
    static String normaliseTopic(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String collapsed = raw.replace(' ', ' ').trim().replaceAll("\\s+", " ");
        StringBuilder out = new StringBuilder(collapsed.length());
        boolean startOfWord = true;
        for (char c : collapsed.toCharArray()) {
            out.append(startOfWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
            startOfWord = !Character.isLetterOrDigit(c);
        }
        return out.toString();
    }

    /**
     * Compares each set sheet against its own answer key sheet.
     *
     * <p>The duplication looks redundant until the day somebody corrects an
     * answer in one place and not the other. At that point the file contains
     * two different exams and there is no way to tell which was meant, so the
     * import stops rather than picking one.
     */
    private void crossCheckAnswerKey(Workbook workbook, String setCode,
                                     List<ParsedQuestion> parsed, List<ImportProblem> problems) {
        Optional<Sheet> keySheet = SheetReader.findSheet(workbook,
                "Set " + setCode + " Answer Key", "Set-" + setCode + " Answer Key",
                "Set " + setCode + " Key", "Answer Key " + setCode);

        if (keySheet.isEmpty()) {
            problems.add(ImportProblem.atSheet("Set " + setCode + " Answer Key",
                    "Answer key sheet not found. It is the cross-check that catches an answer "
                            + "corrected in one place and not the other, so it is required."));
            return;
        }

        SheetReader reader = SheetReader.of(keySheet.get());
        Map<Integer, String> key = new LinkedHashMap<>();
        for (int row : reader.dataRows()) {
            Optional<Integer> number = reader.integer(row, "Q. No.", "Q No", "QNo", "Question No");
            String answer = String.join(",",
                    parseCorrectAnswer(reader.text(row, "Correct Answer", "Answer", "Key")));
            if (number.isPresent() && !answer.isBlank()) {
                key.put(number.get(), answer);
            }
        }

        for (ParsedQuestion question : parsed) {
            String fromKeySheet = key.get(question.questionNumber());
            if (fromKeySheet == null) {
                problems.add(ImportProblem.atSheet(reader.sheetName(),
                        "Question " + question.questionNumber()
                                + " is missing from the answer key"));
            } else if (!fromKeySheet.equals(question.correctKeyAsText())) {
                problems.add(ImportProblem.atRow(reader.sheetName(), question.rowNumber(),
                        "Correct Answer",
                        "Answer key disagrees with the question sheet for Q"
                                + question.questionNumber() + ": sheet says '"
                                + question.correctKeyAsText() + "', key says '"
                                + fromKeySheet + "'"));
            }
        }
    }
}
