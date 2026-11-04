package com.company.attendance.support;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Builds question workbooks for the import tests.
 *
 * <p>Generates a deliberately <em>clean</em> paper by default: the answer
 * rotates A, B, C, D so no letter is over-represented, every option is the same
 * length so "pick the longest" does not work, and no option contains an
 * absolute word. The quality report on this file should therefore be silent,
 * which is what makes a test that expects a warning meaningful.
 *
 * <p>The knobs then break exactly one thing each, so a failing assertion names
 * the defect rather than the fixture.
 */
public final class ExamWorkbookFixture {

    private static final List<String> SET_CODES = List.of("A", "B", "C");
    private static final List<String> ANSWER_ROTATION = List.of("A", "B", "C", "D");
    private static final List<String> TOPICS =
            List.of("Health Insurance", "Motor Insurance", "Travel Insurance");

    private static final List<String> QUESTION_COLUMNS = List.of(
            "Q. No.", "Product", "Difficulty", "Question",
            "Option A", "Option B", "Option C", "Option D",
            "Correct Answer", "Explanation", "Source", "Marks");

    private static final List<String> KEY_COLUMNS =
            List.of("Q. No.", "Product", "Correct Answer", "Explanation");

    /** One question row, as cell values keyed by heading. */
    private record QuestionRow(String setCode, int number, Map<String, String> cells,
                               String keySheetAnswer) {
    }

    private final List<QuestionRow> rows = new ArrayList<>();
    private UnaryOperator<String> sheetNamer = name -> name;
    private boolean writeAnswerKeySheets = true;
    private UnaryOperator<String> headingRenamer = heading -> heading;
    private boolean questionNumbersAsText;

    private ExamWorkbookFixture() {
    }

    /** Three sets of {@code perSet} well-formed questions. */
    public static ExamWorkbookFixture threeSets(int perSet) {
        ExamWorkbookFixture fixture = new ExamWorkbookFixture();
        for (String setCode : SET_CODES) {
            for (int number = 1; number <= perSet; number++) {
                fixture.rows.add(fixture.question(setCode, number));
            }
        }
        return fixture;
    }

    /** Only the sets named, for the "two of three loaded" case. */
    public static ExamWorkbookFixture sets(int perSet, String... setCodes) {
        ExamWorkbookFixture fixture = new ExamWorkbookFixture();
        for (String setCode : setCodes) {
            for (int number = 1; number <= perSet; number++) {
                fixture.rows.add(fixture.question(setCode, number));
            }
        }
        return fixture;
    }

    private QuestionRow question(String setCode, int number) {
        String answer = ANSWER_ROTATION.get((number - 1) % ANSWER_ROTATION.size());
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("Q. No.", String.valueOf(number));
        cells.put("Product", TOPICS.get((number - 1) % TOPICS.size()));
        cells.put("Difficulty", "Moderate");
        cells.put("Question", "Set " + setCode + " question " + number
                + ": which statement describes the cover correctly?");
        // Same length each, so the correct option is never the longest one.
        for (String label : ANSWER_ROTATION) {
            cells.put("Option " + label, "Statement " + label + " about clause " + number
                    + " of the policy document");
        }
        cells.put("Correct Answer", answer);
        cells.put("Explanation", "Option " + answer + " matches clause " + number
                + " as written in the policy document.");
        cells.put("Source", "Policy document, clause " + number);
        cells.put("Marks", "");
        return new QuestionRow(setCode, number, cells, answer);
    }

    // ----------------------------------------------------------------- knobs

    /** Writes a different answer on the key sheet than on the question sheet. */
    public ExamWorkbookFixture withKeySheetDisagreeing(String setCode, int number,
                                                       String keySheetAnswer) {
        return replace(setCode, number, row ->
                new QuestionRow(row.setCode(), row.number(), row.cells(), keySheetAnswer));
    }

    /** Blanks a required cell. */
    public ExamWorkbookFixture withBlank(String setCode, int number, String heading) {
        return replace(setCode, number, row -> {
            Map<String, String> cells = new LinkedHashMap<>(row.cells());
            cells.put(heading, "");
            return new QuestionRow(row.setCode(), row.number(), cells, row.keySheetAnswer());
        });
    }

    /** Overwrites one cell, for answers pointing at options that do not exist. */
    public ExamWorkbookFixture withCell(String setCode, int number, String heading, String value) {
        return replace(setCode, number, row -> {
            Map<String, String> cells = new LinkedHashMap<>(row.cells());
            cells.put(heading, value);
            return new QuestionRow(row.setCode(), row.number(), cells,
                    "Correct Answer".equals(heading) ? value : row.keySheetAnswer());
        });
    }

    /** Makes every answer in a set the same letter — the 92%-by-ticking-B paper. */
    public ExamWorkbookFixture withEverySingleAnswer(String setCode, String label) {
        List<QuestionRow> updated = new ArrayList<>();
        for (QuestionRow row : rows) {
            if (!row.setCode().equals(setCode)) {
                updated.add(row);
                continue;
            }
            Map<String, String> cells = new LinkedHashMap<>(row.cells());
            cells.put("Correct Answer", label);
            cells.put("Explanation", "Option " + label + " matches clause " + row.number() + ".");
            updated.add(new QuestionRow(row.setCode(), row.number(), cells, label));
        }
        rows.clear();
        rows.addAll(updated);
        return this;
    }

    /** The same question text in two places, within or across sets. */
    public ExamWorkbookFixture withDuplicateOf(String fromSet, int fromNumber,
                                               String intoSet, int intoNumber) {
        String text = rows.stream()
                .filter(r -> r.setCode().equals(fromSet) && r.number() == fromNumber)
                .findFirst().orElseThrow().cells().get("Question");
        return withCell(intoSet, intoNumber, "Question", text);
    }

    /** "Set A" becomes "Set-A ", and so on: the tolerant-matching case. */
    public ExamWorkbookFixture withSheetNamesWritten(UnaryOperator<String> namer) {
        this.sheetNamer = namer;
        return this;
    }

    /** Headings as somebody actually typed them: "Q.No", trailing spaces, NBSP. */
    public ExamWorkbookFixture withHeadingsWritten(UnaryOperator<String> renamer) {
        this.headingRenamer = renamer;
        return this;
    }

    /** Leaves the answer key sheets out entirely. */
    public ExamWorkbookFixture withoutAnswerKeySheets() {
        this.writeAnswerKeySheets = false;
        return this;
    }

    /**
     * Drops one question, which makes that set worth less than the others.
     *
     * <p>The case publish has to refuse: a pass mark of 40 is a different bar
     * against a paper worth 95, and nothing in the results would ever show it.
     */
    public ExamWorkbookFixture withoutQuestion(String setCode, int number) {
        boolean removed = rows.removeIf(
                row -> row.setCode().equals(setCode) && row.number() == number);
        if (!removed) {
            throw new IllegalArgumentException("No question " + number + " in set " + setCode);
        }
        return this;
    }

    /** Question numbers as text cells rather than numbers. */
    public ExamWorkbookFixture withQuestionNumbersAsText() {
        this.questionNumbersAsText = true;
        return this;
    }

    private ExamWorkbookFixture replace(String setCode, int number,
                                        UnaryOperator<QuestionRow> change) {
        for (int i = 0; i < rows.size(); i++) {
            QuestionRow row = rows.get(i);
            if (row.setCode().equals(setCode) && row.number() == number) {
                rows.set(i, change.apply(row));
                return this;
            }
        }
        throw new IllegalArgumentException("No question " + number + " in set " + setCode);
    }

    // ----------------------------------------------------------------- build

    public byte[] build() {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            for (String setCode : setsPresent()) {
                writeQuestionSheet(workbook, setCode);
                if (writeAnswerKeySheets) {
                    writeKeySheet(workbook, setCode);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException failed) {
            throw new UncheckedIOException("The fixture workbook could not be built", failed);
        }
    }

    private List<String> setsPresent() {
        return rows.stream().map(QuestionRow::setCode).distinct().toList();
    }

    private void writeQuestionSheet(Workbook workbook, String setCode) {
        Sheet sheet = workbook.createSheet(sheetNamer.apply("Set " + setCode));
        writeHeadings(sheet, QUESTION_COLUMNS);

        int rowIndex = 1;
        for (QuestionRow row : rows) {
            if (!row.setCode().equals(setCode)) {
                continue;
            }
            Row sheetRow = sheet.createRow(rowIndex++);
            for (int i = 0; i < QUESTION_COLUMNS.size(); i++) {
                write(sheetRow, i, QUESTION_COLUMNS.get(i), row.cells());
            }
        }
    }

    private void writeKeySheet(Workbook workbook, String setCode) {
        Sheet sheet = workbook.createSheet(sheetNamer.apply("Set " + setCode + " Answer Key"));
        writeHeadings(sheet, KEY_COLUMNS);

        int rowIndex = 1;
        for (QuestionRow row : rows) {
            if (!row.setCode().equals(setCode)) {
                continue;
            }
            Row sheetRow = sheet.createRow(rowIndex++);
            Map<String, String> cells = new LinkedHashMap<>(row.cells());
            cells.put("Correct Answer", row.keySheetAnswer());
            for (int i = 0; i < KEY_COLUMNS.size(); i++) {
                write(sheetRow, i, KEY_COLUMNS.get(i), cells);
            }
        }
    }

    private void writeHeadings(Sheet sheet, List<String> columns) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < columns.size(); i++) {
            header.createCell(i).setCellValue(headingRenamer.apply(columns.get(i)));
        }
    }

    private void write(Row sheetRow, int column, String heading, Map<String, String> cells) {
        String value = cells.getOrDefault(heading, "");
        if (value == null || value.isEmpty()) {
            // A genuinely empty cell, not a blank string: that is what Excel
            // produces and what the reader has to cope with.
            return;
        }
        if ("Q. No.".equals(heading) && !questionNumbersAsText) {
            // Numeric, so the value arrives as the double 1.0 — exactly how a
            // real workbook stores it, and the case a naive parser fails on.
            sheetRow.createCell(column).setCellValue(Double.parseDouble(value));
            return;
        }
        sheetRow.createCell(column).setCellValue(value);
    }
}
