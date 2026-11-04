package com.company.attendance.dto.response.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A generated report: what it is, what it covers, and its rows.
 *
 * <p>{@code totals} carries the column sums that make sense for this report, so
 * a reader does not have to add up a paged table by hand and get a different
 * answer from the export.
 */
public record ReportResult(
        String type,
        String title,
        String description,
        LocalDate fromDate,
        LocalDate toDate,
        /** ORG or TEAM — how wide the population behind these numbers is. */
        String scope,

        List<ReportColumn> columns,
        List<Map<String, Object>> rows,

        int page,
        int size,
        long totalRows,
        int totalPages,

        Map<String, Object> totals,
        Instant generatedAt
) {
}
