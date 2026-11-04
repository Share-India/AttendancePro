package com.company.attendance.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a spreadsheet by column heading rather than by position.
 *
 * <p>Every method here exists because of something a real workbook does. The
 * headings arrive with trailing spaces and non-breaking spaces pasted out of
 * Word; {@code Q. No.} is sometimes written {@code Q.No} and arrives as the
 * double 1.0 rather than the integer 1; formula cells need their cached value;
 * and Excel keeps blank rows below the data that must not be read as questions
 * with nothing in them. The employee CSV import taught all of this the
 * expensive way.
 *
 * <p>Headings are matched on their letters and digits alone — {@code "Q. No. "}
 * and {@code "q no"} both reduce to {@code qno} — so punctuation and spacing
 * stop being something the person filling in the template has to get right.
 *
 * <p>Nothing here knows what an exam is. It is a general reader, kept in
 * {@code util} so the next import to arrive does not write its own.
 */
public final class SheetReader {

    /** The non-breaking space that survives a paste from Word and looks identical. */
    private static final char NBSP = ' ';

    private final Sheet sheet;
    private final Map<String, Integer> columns = new LinkedHashMap<>();
    private final List<String> headingsAsWritten = new ArrayList<>();

    private SheetReader(Sheet sheet) {
        this.sheet = sheet;
        Row header = sheet.getRow(sheet.getFirstRowNum());
        if (header == null) {
            return;
        }
        for (int i = header.getFirstCellNum(); i >= 0 && i < header.getLastCellNum(); i++) {
            String written = stringValue(header.getCell(i));
            if (written == null || written.isBlank()) {
                continue;
            }
            headingsAsWritten.add(written);
            // First wins: a duplicated heading is a mistake, and silently
            // taking the last one would hide it.
            columns.putIfAbsent(normaliseHeading(written), i);
        }
    }

    public static SheetReader of(Sheet sheet) {
        return new SheetReader(sheet);
    }

    /** Letters and digits only, lower-cased. "Q. No. " and "q no" both become "qno". */
    public static String normaliseHeading(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.replace(NBSP, ' ').toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    public String sheetName() {
        return sheet.getSheetName();
    }

    public List<String> headings() {
        return List.copyOf(headingsAsWritten);
    }

    /** True when any of the given headings is present, so aliases cost nothing. */
    public boolean has(String... headingAliases) {
        return columnIndex(headingAliases).isPresent();
    }

    private Optional<Integer> columnIndex(String... headingAliases) {
        for (String alias : headingAliases) {
            Integer index = columns.get(normaliseHeading(alias));
            if (index != null) {
                return Optional.of(index);
            }
        }
        return Optional.empty();
    }

    /** One-based row numbers of everything below the header that has content. */
    public List<Integer> dataRows() {
        List<Integer> rows = new ArrayList<>();
        for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row != null && !isEntirelyBlank(row)) {
                rows.add(r + 1);
            }
        }
        return rows;
    }

    /**
     * Excel keeps rows that once held something and now hold nothing. Reading
     * those as questions produces twenty-three questions from a sheet of
     * twenty, all of them unanswerable.
     */
    private boolean isEntirelyBlank(Row row) {
        for (int i = row.getFirstCellNum(); i >= 0 && i < row.getLastCellNum(); i++) {
            String value = stringValue(row.getCell(i));
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    /** Trimmed text, or null when the cell is empty. Row numbers are one-based. */
    public String text(int rowNumber, String... headingAliases) {
        return columnIndex(headingAliases)
                .map(index -> {
                    Row row = sheet.getRow(rowNumber - 1);
                    return row == null ? null : stringValue(row.getCell(index));
                })
                .filter(value -> value != null && !value.isBlank())
                .orElse(null);
    }

    /**
     * A whole number, however Excel chose to store it. {@code 1}, {@code 1.0}
     * and the text {@code "1"} are the same question number to everybody except
     * a naive parser.
     */
    public Optional<Integer> integer(int rowNumber, String... headingAliases) {
        String raw = text(rowNumber, headingAliases);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of((int) Math.round(Double.parseDouble(raw)));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    /**
     * The displayed value of a cell, whatever its type.
     *
     * <p>Formula cells are read through their cached result rather than
     * evaluated: the workbook was saved by Excel, the answer is already in it,
     * and evaluating would need POI's formula engine to agree with Excel about
     * functions it may not implement.
     */
    private String stringValue(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();

        String value = switch (type) {
            case STRING -> cell.getStringCellValue();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> numericText(cell);
            case BLANK, _NONE, ERROR, FORMULA -> null;
        };
        return value == null ? null : value.replace(NBSP, ' ').trim();
    }

    private String numericText(Cell cell) {
        if (DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        double number = cell.getNumericCellValue();
        // 1.0 is the integer 1 everywhere a human is concerned.
        return number == Math.rint(number) && !Double.isInfinite(number)
                ? String.valueOf((long) number)
                : String.valueOf(number);
    }

    /** Sheet names get the same tolerance as headings: "Set-A", "SET A " and "seta" agree. */
    public static Optional<Sheet> findSheet(Workbook workbook, String... nameAliases) {
        for (String alias : nameAliases) {
            String wanted = normaliseHeading(alias);
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet candidate = workbook.getSheetAt(i);
                if (normaliseHeading(candidate.getSheetName()).equals(wanted)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    public static List<String> sheetNames(Workbook workbook) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            names.add(workbook.getSheetAt(i).getSheetName());
        }
        return names;
    }

    public static String upperTrimmed(String raw) {
        return raw == null ? null : raw.replace(NBSP, ' ').trim().toUpperCase(Locale.ENGLISH);
    }
}
