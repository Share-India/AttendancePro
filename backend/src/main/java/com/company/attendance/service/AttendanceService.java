package com.company.attendance.service;

import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.attendance.AttendanceCalendarResponse;
import com.company.attendance.dto.response.attendance.AttendanceResponse;
import com.company.attendance.dto.response.attendance.AttendanceStatusResponse;
import com.company.attendance.dto.response.attendance.AttendanceSummaryResponse;
import com.company.attendance.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

/** Reads over attendance. All of them narrow to the caller's data scope. */
public interface AttendanceService {

    /** Live state for the dashboard: what the employee can do right now. */
    AttendanceStatusResponse getCurrentStatus(UserPrincipal principal);

    AttendanceResponse getToday(UserPrincipal principal);

    PageResponse<AttendanceResponse> getHistory(UserPrincipal principal,
                                                Long employeeId,
                                                LocalDate from,
                                                LocalDate to,
                                                Pageable pageable);

    AttendanceCalendarResponse getCalendar(UserPrincipal principal, Long employeeId, int year, int month);

    AttendanceSummaryResponse getSummary(UserPrincipal principal, Long employeeId, int year, int month);
}
