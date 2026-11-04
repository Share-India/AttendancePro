package com.company.attendance.service.exam;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Generates the exact workbook the importer accepts.
 *
 * <p>Most import pain is people guessing at column names from prose — the
 * employee CSV import taught that. Handing them the file removes the guessing,
 * and because the headings here come from the same list the parser reads, the
 * template cannot drift away from what is actually accepted.
 */
@Component
public class ExamTemplateWriter {

    private static final List<String> QUESTION_COLUMNS = List.of(
            "Q. No.", "Product", "Difficulty", "Question",
            "Option A", "Option B", "Option C", "Option D",
            "Correct Answer", "Explanation", "Source", "Marks");

    private static final List<String> KEY_COLUMNS = List.of(
            "Q. No.", "Product", "Correct Answer", "Explanation", "Source");

    private static final List<String[]> GUIDANCE = List.of(
            new String[] {"Sheets",
                "One 'Set X' sheet and one 'Set X Answer Key' sheet for each of A, B and C."},
            new String[] {"Column order",
                "Does not matter. Columns are found by their heading, not their position."},
            new String[] {"Extra sheets",
                "Ignored, so you can keep working notes in the same workbook."},
            new String[] {"Q. No.", "A whole number, unique within its own sheet."},
            new String[] {"Product",
                "What the question is about. This drives the per-topic analysis, which is the "
                        + "main output of the exam, so keep the wording consistent."},
            new String[] {"Difficulty", "Optional: Easy, Moderate or Hard."},
            new String[] {"Options", "A and B are required. C and D are optional."},
            new String[] {"Correct Answer",
                "A letter, or several for a multi-answer question: A,C"},
            new String[] {"Multi-answer",
                "Marked all or nothing — every correct option and no wrong ones."},
            new String[] {"Explanation",
                "Required. The candidate reads it after submitting, and it is the point of the "
                        + "whole exercise."},
            new String[] {"Source", "Optional citation, shown beside the explanation."},
            new String[] {"Marks", "Optional. Falls back to the exam's marks per question."},
            new String[] {"Answer key sheets",
                "Required, and they must agree with the question sheets."},
            new String[] {"If they disagree",
                "The whole file is rejected. Two versions of an answer is not something to "
                        + "guess at."},
            new String[] {"Spread the answers",
                "Before uploading, check you have not written B fifteen times. The import "
                        + "reports it, and a paper that can be passed by ticking one letter "
                        + "measures nothing."});

    public byte[] build() {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle header = headerStyle(workbook);
            CellStyle wrapped = wrappedStyle(workbook);

            writeGuidance(workbook, header, wrapped);
            for (String setCode : ExamWorkbookParser.SET_CODES) {
                writeHeadings(workbook.createSheet("Set " + setCode), QUESTION_COLUMNS, header);
                writeHeadings(workbook.createSheet("Set " + setCode + " Answer Key"),
                        KEY_COLUMNS, header);
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException failed) {
            throw new UncheckedIOException("The question template could not be generated", failed);
        }
    }

    private void writeGuidance(Workbook workbook, CellStyle header, CellStyle wrapped) {
        Sheet sheet = workbook.createSheet("How to fill this in");
        Row heading = sheet.createRow(0);
        write(heading, 0, "Field", header);
        write(heading, 1, "What it expects", header);

        int rowIndex = 1;
        for (String[] line : GUIDANCE) {
            Row row = sheet.createRow(rowIndex++);
            write(row, 0, line[0], null);
            write(row, 1, line[1], wrapped);
        }
        sheet.setColumnWidth(0, 22 * 256);
        sheet.setColumnWidth(1, 90 * 256);
    }

    private void writeHeadings(Sheet sheet, List<String> columns, CellStyle style) {
        Row row = sheet.createRow(0);
        for (int i = 0; i < columns.size(); i++) {
            write(row, i, columns.get(i), style);
            sheet.setColumnWidth(i, widthFor(columns.get(i)));
        }
        sheet.createFreezePane(0, 1);
    }

    private int widthFor(String column) {
        return switch (column) {
            case "Question", "Explanation" -> 60 * 256;
            case "Option A", "Option B", "Option C", "Option D", "Source" -> 30 * 256;
            default -> 14 * 256;
        };
    }

    private void write(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value);
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle wrappedStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setWrapText(true);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        return style;
    }
}
