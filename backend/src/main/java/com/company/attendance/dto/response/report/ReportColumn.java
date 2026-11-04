package com.company.attendance.dto.response.report;

/**
 * One column of a report.
 *
 * <p>{@code type} is what lets the exporter and the table format a value
 * correctly without knowing which report they are looking at: a DURATION
 * renders as "7h 45m" on screen and as a number of minutes in a spreadsheet,
 * where someone will want to sum it.
 */
public record ReportColumn(String key, String label, ColumnType type) {

    public enum ColumnType {
        TEXT,
        NUMBER,
        DATE,
        TIME,
        /** Minutes. Displayed as hours and minutes, exported as a number. */
        DURATION,
        STATUS
    }

    public static ReportColumn text(String key, String label) {
        return new ReportColumn(key, label, ColumnType.TEXT);
    }

    public static ReportColumn number(String key, String label) {
        return new ReportColumn(key, label, ColumnType.NUMBER);
    }

    public static ReportColumn date(String key, String label) {
        return new ReportColumn(key, label, ColumnType.DATE);
    }

    public static ReportColumn time(String key, String label) {
        return new ReportColumn(key, label, ColumnType.TIME);
    }

    public static ReportColumn duration(String key, String label) {
        return new ReportColumn(key, label, ColumnType.DURATION);
    }

    public static ReportColumn status(String key, String label) {
        return new ReportColumn(key, label, ColumnType.STATUS);
    }
}
