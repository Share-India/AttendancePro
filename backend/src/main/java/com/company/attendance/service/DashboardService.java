package com.company.attendance.service;

import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.dashboard.AttendanceTrendResponse;
import com.company.attendance.dto.response.dashboard.DashboardSummaryResponse;
import com.company.attendance.dto.response.dashboard.TeamMemberDayResponse;
import com.company.attendance.security.UserPrincipal;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

/**
 * The team and organisation views.
 *
 * <p>Every method answers for the population the caller may see and no wider.
 * A manager and an HR user call the same endpoints; the scope resolver decides
 * whose rows come back, exactly as it does everywhere else.
 */
public interface DashboardService {

    /** One day rolled up: who is working, who is legitimately off, who is not. */
    DashboardSummaryResponse getSummary(UserPrincipal principal, LocalDate date, Long departmentId, Long officeId);

    /** The same day, person by person. */
    PageResponse<TeamMemberDayResponse> getRoster(UserPrincipal principal,
                                                  LocalDate date,
                                                  String dayState,
                                                  Long departmentId,
                                                  Long officeId,
                                                  String search,
                                                  Pageable pageable);

    /** Daily counts across a range, for the chart. */
    AttendanceTrendResponse getTrend(UserPrincipal principal, LocalDate fromDate, LocalDate toDate);
}
