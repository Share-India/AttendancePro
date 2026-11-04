package com.company.attendance.dto.request.report;

import java.time.LocalDate;
import java.util.List;

/**
 * What to include in a report.
 *
 * <p>Every field narrows; none widens. A manager passing a department id still
 * sees only their own team within it — the scope resolver decides the
 * population first and these filters apply inside it.
 */
public record ReportFilter(
        LocalDate fromDate,
        LocalDate toDate,
        List<Long> employeeIds,
        Long departmentId,
        Long officeId,
        String status,
        String search
) {
}
