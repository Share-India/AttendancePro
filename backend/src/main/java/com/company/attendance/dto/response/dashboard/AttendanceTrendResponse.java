package com.company.attendance.dto.response.dashboard;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily attendance over a range, for the dashboard chart.
 *
 * <p>Non-working days are returned rather than dropped, flagged by
 * {@code working}. A chart that silently omits Sundays draws a continuous line
 * through a gap and invites the reader to see a slump that is just the weekend.
 */
public record AttendanceTrendResponse(LocalDate fromDate, LocalDate toDate, List<Point> points) {

    public record Point(
            LocalDate date,
            boolean working,
            int expected,
            int present,
            int workingFromHome,
            int onLeave,
            int absent,
            int late) {
    }
}
