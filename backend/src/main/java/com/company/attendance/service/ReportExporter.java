package com.company.attendance.service;

import com.company.attendance.dto.response.report.ReportColumn;
import com.company.attendance.dto.response.report.ReportResult;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.exception.BusinessException;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a report into a file.
 *
 * <p>One exporter for all twelve reports, because they all arrive as typed
 * columns plus rows keyed by them. The column type is what makes that work:
 * a DURATION is written as "7h 45m" in a PDF a person reads, and as a plain
 * number of minutes in a spreadsheet where someone will want to sum it.
 *
 * <p>Everything writes straight to the response stream. Nothing here builds a
 * whole document in memory first — that is the difference between a 50,000-row
 * export and an OutOfMemoryError.
 */
@Component
public class ReportExporter {

    /** Rows held in memory by SXSSF before it spills to disk. */
    private static final int EXCEL_WINDOW = 200;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    public enum Format {
        CSV("text/csv", "csv"),
        EXCEL("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
        PDF("application/pdf", "pdf");

        private final String contentType;
        private final String extension;

        Format(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }

        public String contentType() {
            return contentType;
        }

        public String extension() {
            return extension;
        }

        public static Format parse(String value) {
            if (value == null || value.isBlank()) {
                return CSV;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Unknown export format '" + value + "'. Use CSV, EXCEL or PDF.");
            }
        }
    }

    public void write(ReportResult report, Format format, ZoneId zone, OutputStream out) {
        try {
            switch (format) {
                case CSV -> writeCsv(report, zone, out);
                case EXCEL -> writeExcel(report, zone, out);
                case PDF -> writePdf(report, zone, out);
            }
            // Closed, not merely flushed. A flush commits the response and puts
            // it into chunked mode; only the close writes the terminating chunk.
            // Flushing alone reached the browser as ERR_INCOMPLETE_CHUNKED_ENCODING
            // and a failed download, while curl — which tolerates an unterminated
            // stream at EOF — read the same bytes quite happily.
            out.close();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "The export could not be written.");
        }
    }

    public String fileName(ReportResult report, Format format) {
        return report.type().toLowerCase(Locale.ROOT).replace('_', '-')
                + "_" + report.fromDate() + "_to_" + report.toDate()
                + "." + format.extension();
    }

    // -----------------------------------------------------------------
    // CSV
    // -----------------------------------------------------------------

    private void writeCsv(ReportResult report, ZoneId zone, OutputStream out) throws IOException {
        // Not closed here: write() closes the underlying stream once, for every
        // format, so the terminating chunk is written in exactly one place.
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);

        // Excel opens a UTF-8 CSV as the local codepage unless it sees a BOM,
        // which turns every accented name into mojibake on a Windows desktop.
        writer.write('﻿');

        writer.write(report.columns().stream()
                .map(column -> csvCell(column.label()))
                .reduce((a, b) -> a + "," + b).orElse(""));
        writer.write("\r\n");

        for (Map<String, Object> row : report.rows()) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < report.columns().size(); i++) {
                if (i > 0) {
                    line.append(',');
                }
                ReportColumn column = report.columns().get(i);
                line.append(csvCell(exportValue(column, row.get(column.key()), zone)));
            }
            writer.write(line.toString());
            writer.write("\r\n");
        }
        writer.flush();
    }

    /**
     * Quotes a CSV field.
     *
     * <p>The leading apostrophe guard is deliberate. A value beginning with
     * {@code =}, {@code +}, {@code -} or {@code @} is executed as a formula when
     * the file is opened in a spreadsheet, so an employee whose "reason" field
     * begins with an equals sign could otherwise run a formula on the machine of
     * whoever opens the export.
     */
    private String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String safe = value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            safe = "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    // -----------------------------------------------------------------
    // Excel
    // -----------------------------------------------------------------

    private void writeExcel(ReportResult report, ZoneId zone, OutputStream out) throws IOException {
        // SXSSF keeps only a sliding window of rows in memory and flushes the
        // rest to a temp file, which is what makes a large export survivable.
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(EXCEL_WINDOW)) {
            Sheet sheet = workbook.createSheet(sheetName(report.title()));

            CellStyle header = headerStyle(workbook);
            CellStyle dateStyle = dateStyle(workbook, "yyyy-mm-dd");

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < report.columns().size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(report.columns().get(i).label());
                cell.setCellStyle(header);
            }

            int rowIndex = 1;
            for (Map<String, Object> row : report.rows()) {
                Row sheetRow = sheet.createRow(rowIndex++);
                for (int i = 0; i < report.columns().size(); i++) {
                    ReportColumn column = report.columns().get(i);
                    Object value = row.get(column.key());
                    Cell cell = sheetRow.createCell(i);
                    writeExcelCell(cell, column, value, zone, dateStyle);
                }
            }

            workbook.write(out);
            // The temp files SXSSF spilled to are not cleaned up by close().
            workbook.dispose();
        }
    }

    private void writeExcelCell(Cell cell, ReportColumn column, Object value,
                                ZoneId zone, CellStyle dateStyle) {
        if (value == null) {
            return;
        }
        switch (column.type()) {
            // Numbers stay numbers so the recipient can sum and sort them.
            // A duration written as "7h 45m" is a string, and a column of
            // strings is exactly what makes people re-key a report by hand.
            case NUMBER, DURATION -> {
                if (value instanceof Number number) {
                    cell.setCellValue(number.doubleValue());
                } else {
                    cell.setCellValue(String.valueOf(value));
                }
            }
            case DATE -> {
                if (value instanceof LocalDate date) {
                    cell.setCellValue(java.sql.Date.valueOf(date));
                    cell.setCellStyle(dateStyle);
                } else {
                    cell.setCellValue(String.valueOf(value));
                }
            }
            case TIME -> cell.setCellValue(exportValue(column, value, zone));
            default -> cell.setCellValue(exportValue(column, value, zone));
        }
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private CellStyle dateStyle(Workbook workbook, String pattern) {
        CreationHelper helper = workbook.getCreationHelper();
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(helper.createDataFormat().getFormat(pattern));
        return style;
    }

    /** Excel refuses these characters in a sheet name and caps it at 31. */
    private String sheetName(String title) {
        String safe = title.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        return safe.length() <= 31 ? safe : safe.substring(0, 31);
    }

    // -----------------------------------------------------------------
    // PDF
    // -----------------------------------------------------------------

    private void writePdf(ReportResult report, ZoneId zone, OutputStream out) {
        // Landscape: these are wide tables, and a portrait page turns every
        // column into two lines of wrapped text.
        Document document = new Document(PageSize.A4.rotate(), 24, 24, 28, 28);
        PdfWriter.getInstance(document, out);
        document.open();

        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);
        Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 8, new Color(90, 90, 90));
        Font headFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        Font cellFont = FontFactory.getFont(FontFactory.HELVETICA, 8);

        document.add(new Paragraph(report.title(), titleFont));
        document.add(new Paragraph(
                report.fromDate() + " to " + report.toDate()
                        + "  ·  " + report.totalRows() + " rows"
                        + "  ·  generated " + DATE.format(LocalDate.now()),
                metaFont));
        document.add(new Paragraph(" ", metaFont));

        PdfPTable table = new PdfPTable(report.columns().size());
        table.setWidthPercentage(100);
        table.setHeaderRows(1);

        for (ReportColumn column : report.columns()) {
            PdfPCell cell = new PdfPCell(new Phrase(column.label(), headFont));
            cell.setBackgroundColor(new Color(238, 240, 244));
            cell.setPadding(4);
            table.addCell(cell);
        }

        for (Map<String, Object> row : report.rows()) {
            for (ReportColumn column : report.columns()) {
                String text = exportValue(column, row.get(column.key()), zone);
                PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, cellFont));
                cell.setPadding(3);
                if (column.type() == ReportColumn.ColumnType.NUMBER
                        || column.type() == ReportColumn.ColumnType.DURATION) {
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                }
                table.addCell(cell);
            }
        }

        document.add(table);
        document.close();
    }

    // -----------------------------------------------------------------

    /** One value, rendered for a human-readable format. */
    private String exportValue(ReportColumn column, Object value, ZoneId zone) {
        if (value == null) {
            return "";
        }
        return switch (column.type()) {
            case DURATION -> value instanceof Number number
                    ? formatMinutes(number.intValue())
                    : String.valueOf(value);
            case TIME -> value instanceof Instant instant
                    ? TIME.format(instant.atZone(zone))
                    : String.valueOf(value);
            case DATE -> value instanceof LocalDate date
                    ? DATE.format(date)
                    : String.valueOf(value);
            default -> String.valueOf(value);
        };
    }

    private String formatMinutes(int minutes) {
        if (minutes <= 0) {
            return "0m";
        }
        int hours = minutes / 60;
        int rest = minutes % 60;
        return hours > 0 ? hours + "h " + rest + "m" : rest + "m";
    }

    /** Columns whose values a caller might reasonably want to total. */
    public List<ReportColumn> summableColumns(ReportResult report) {
        return report.columns().stream()
                .filter(column -> column.type() == ReportColumn.ColumnType.NUMBER
                        || column.type() == ReportColumn.ColumnType.DURATION)
                .toList();
    }
}
