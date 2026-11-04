package com.company.attendance.service;

import com.company.attendance.dto.request.report.ReportFilter;
import com.company.attendance.dto.response.report.ReportResult;
import com.company.attendance.enums.ReportType;
import com.company.attendance.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

public interface ReportService {

    /**
     * Runs a report for the population the caller may see.
     *
     * <p>Paged for the screen. Exports call {@link #generateAll} instead, so a
     * download is never silently truncated to the first page — the single most
     * damaging thing a report can do quietly.
     */
    ReportResult generate(UserPrincipal principal, ReportType type, ReportFilter filter, Pageable pageable);

    /** The whole result set, for export. Bounded by a configured row cap. */
    ReportResult generateAll(UserPrincipal principal, ReportType type, ReportFilter filter);
}
