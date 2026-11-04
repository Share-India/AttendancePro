package com.company.attendance.enums;

/**
 * The reports this system can produce.
 *
 * <p>Every one resolves to the same shape — a list of typed columns and a list
 * of rows keyed by those columns. That is what makes one exporter serve all
 * twelve formats-by-report combinations, and one table component render any of
 * them. Twelve bespoke DTOs would mean twelve exporters and twelve tables, and
 * the thirteenth report would cost as much as the first.
 */
public enum ReportType {

    DAILY_ATTENDANCE("Daily Attendance", "One row per person per day."),
    MONTHLY_ATTENDANCE("Monthly Attendance", "One row per person, summarising the range."),
    EMPLOYEE_ATTENDANCE("Employee Attendance", "One person's days in detail."),
    LATE_ARRIVALS("Late Arrivals", "Days someone arrived after their grace period."),
    EARLY_EXITS("Early Exits", "Days someone left before their shift ended."),
    ABSENTEES("Absentees", "Working days with no punch and no approved reason."),
    LEAVES("Leave Register", "Leave requests raised in the range."),
    OVERTIME("Overtime", "Days with recorded overtime."),
    WORKING_HOURS("Working Hours", "Hours worked per person across the range."),
    DEPARTMENT_ATTENDANCE("Department Attendance", "Attendance rolled up by department."),
    LOCATION_ATTENDANCE("Location Attendance", "Where punches were made, and what was flagged."),
    REGULARIZATIONS("Regularizations", "Attendance corrections raised in the range."),

    MEETING_ATTENDANCE("Meeting Attendance",
            "One row per person per meeting. Joining is measured by the link being opened."),
    MEETING_PARTICIPATION("Meeting Participation",
            "One row per person: how many meetings they were invited to and how many they opened."),

    EXAM_RESULTS("Exam Results",
            "One row per person per exam: the set they sat, their score and whether they passed."),
    EXAM_PARTICIPATION("Exam Participation",
            "One row per person: exams assigned, sat, passed and missed."),
    EXAM_QUESTION_ANALYSIS("Exam Question Analysis",
            "One row per question: how many were asked it, how many got it right, "
                    + "and the wrong answer most of them gave.");

    private final String title;
    private final String description;

    ReportType(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Reports that describe one named person rather than a population.
     * The API insists on an employee id for these rather than quietly
     * returning everyone.
     */
    public boolean requiresEmployee() {
        return this == EMPLOYEE_ATTENDANCE;
    }
}
