package com.company.attendance.dto.response.exam;

/**
 * One thing wrong with one row of an uploaded workbook.
 *
 * <p>Named by sheet, row and column rather than reported as a count, because
 * "3 rows were invalid" sends somebody to read sixty rows looking for them. Row
 * numbers are the ones Excel shows in its own margin, not zero-based indexes.
 *
 * <p>Serialised straight to the client, which is why it lives here rather than
 * beside the parser: the import screen shows the same list the parser produced,
 * with no translation step that could lose a field.
 */
public record ImportProblem(String sheet, Integer row, String column, String message) {

    public static ImportProblem atRow(String sheet, int row, String column, String message) {
        return new ImportProblem(sheet, row, column, message);
    }

    public static ImportProblem atSheet(String sheet, String message) {
        return new ImportProblem(sheet, null, null, message);
    }

    public static ImportProblem atFile(String message) {
        return new ImportProblem(null, null, null, message);
    }

    /** Reads as a line somebody can act on without opening the code. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        if (sheet != null) {
            text.append('\'').append(sheet).append('\'');
        }
        if (row != null) {
            text.append(text.isEmpty() ? "Row " : " row ").append(row);
        }
        if (column != null) {
            text.append(", column '").append(column).append('\'');
        }
        return text.isEmpty() ? message : text + ": " + message;
    }
}
