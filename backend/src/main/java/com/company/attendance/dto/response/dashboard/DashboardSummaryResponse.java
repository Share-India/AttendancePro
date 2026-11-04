package com.company.attendance.dto.response.dashboard;

import java.time.LocalDate;
import java.util.List;

/**
 * One day, rolled up for everyone the caller may see.
 *
 * <p>The counts are deliberately not reducible to "present and absent". A
 * headline absence figure that silently includes approved leave and weekly offs
 * is the most common way a dashboard misleads the person reading it, so the
 * legitimate reasons for being away are each named and only {@code absent}
 * counts people who owe an explanation.
 *
 * <p>{@code expected} is the honest denominator: people whose day was supposed
 * to be a working one. Reporting attendance against total headcount makes every
 * Sunday look like a crisis.
 */
public record DashboardSummaryResponse(
        LocalDate date,
        /** ORG or TEAM — which population these numbers describe. */
        String scope,

        int headcount,
        int expected,

        int present,
        int workingFromHome,
        int onLeave,
        int weeklyOff,
        int holiday,
        int notYetIn,
        int absent,

        /** Of those present, how many arrived after their grace period. */
        int late,
        /** Of those present, how many have already punched out. */
        int punchedOut,
        /** Still punched in right now. */
        int currentlyIn,

        /** Percentage of {@code expected} who are present or working from home. */
        double attendanceRate,

        PendingApprovals pendingApprovals,
        List<GroupCount> byDepartment
) {

    /** What is waiting on the caller, so the dashboard can link straight to it. */
    public record PendingApprovals(int leave, int regularization, int workFromHome, int total) {
    }

    /** A slice of the same population — used for the department breakdown. */
    public record GroupCount(
            Long id,
            String name,
            int expected,
            int present,
            int workingFromHome,
            int onLeave,
            int absent) {
    }
}
