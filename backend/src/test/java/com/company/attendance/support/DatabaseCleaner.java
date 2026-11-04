package com.company.attendance.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Empties the test database in foreign-key order.
 *
 * <p>The suite shares one in-memory database across test classes, so a class
 * that deletes only the tables it knows about trips over rows another class
 * left behind — an employee cannot be removed while a shift assignment, a user
 * or a leave request still points at it.
 *
 * <p>Each class was previously listing its own deletes, which meant every new
 * table broke an unrelated test the first time the suite ran in a different
 * order. One ordered list, in one place, removes that whole class of failure.
 */
@Component
public class DatabaseCleaner {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Child tables first, parents last. Anything not listed is untouched, so
     * reference data seeded by a migration survives.
     */
    private static final List<String> TABLES_IN_DELETE_ORDER = List.of(
            // Notifications
            "notifications",
            "notification_preferences",

            // Exams. Children before parents: answers before attempts,
            // attempts before participants, participants before the sets they
            // point at, and the bank last.
            "exam_answers",
            "exam_attempts",
            "exam_participants",
            "exam_uploads",
            "exam_audience",
            "exam_question_options",
            "exam_questions",
            "exam_question_sets",
            "exams",

            // Meetings
            "meeting_join_events",
            "meeting_participants",
            "meeting_audience",
            "meetings",

            // Requests
            "regularization_approvals",
            "attendance_regularizations",
            "wfh_approvals",
            "wfh_request_days",
            "work_from_home_requests",

            // Attendance
            "attendance_punch_events",
            "attendance_breaks",
            "overtime_records",
            "attendance",

            // Leave
            "leave_request_days",
            "leave_approvals",
            "leave_requests",
            "leave_balances",
            "leave_policies",
            "leave_types",

            // Identity
            "refresh_tokens",
            "password_reset_tokens",
            "login_attempts",
            "user_roles",
            "users",

            // Org
            "employee_shifts",
            "employee_managers",
            "employees",
            "shifts",
            "office_locations",
            "offices",
            "designations",
            "departments",

            // Policy and calendar
            "policy_assignments",
            "attendance_policies",
            "location_policies",
            "overtime_policies",
            "holiday_calendar_entries",
            "holiday_calendars",
            "holidays",
            "weekly_offs",
            "break_types",

            // RBAC
            "role_permissions",
            "roles",
            "permissions",

            // Platform
            "audit_logs");

    /**
     * Tables that must survive a clean.
     *
     * <p>Flyway's own history obviously, and company settings, which are seeded
     * by a migration and read as reference data rather than written by tests.
     */
    private static final Set<String> PRESERVED = Set.of(
            "FLYWAY_SCHEMA_HISTORY",
            "COMPANY_SETTINGS");

    /**
     * Empties every table above.
     *
     * <p>Referential integrity is disabled for the duration rather than the
     * order being trusted absolutely — a future circular reference would
     * otherwise turn into a confusing failure inside an unrelated test.
     */
    @Transactional
    public void clean() {
        entityManager.flush();
        entityManager.clear();

        Set<String> present = existingTables();
        assertNothingForgotten(present);

        entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        try {
            TABLES_IN_DELETE_ORDER.stream()
                    // The test schema is generated from the JPA entities, so
                    // tables that exist only in the MySQL migrations — the
                    // holiday calendars, for instance — are simply not here.
                    .filter(table -> present.contains(table.toUpperCase(Locale.ROOT)))
                    // DELETE rather than TRUNCATE: H2 refuses to truncate a
                    // table another foreign key references, even with integrity
                    // checking off, and does so without raising an error.
                    .forEach(table ->
                            entityManager.createNativeQuery("DELETE FROM " + table).executeUpdate());
        } finally {
            entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
        }
    }

    /**
     * Refuses to run if a table exists that is neither cleaned nor preserved.
     *
     * <p>This list has been silently out of date twice: the work-from-home and
     * regularization tables were never added when they landed, and neither were
     * notifications. The symptom both times was a test that passed alone and
     * failed in the suite, which is among the most expensive kinds of failure to
     * track down. Failing here turns that into a one-line fix with the table
     * name already in the message.
     */
    private void assertNothingForgotten(Set<String> present) {
        Set<String> cleaned = TABLES_IN_DELETE_ORDER.stream()
                .map(name -> name.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());

        List<String> forgotten = present.stream()
                .filter(table -> !cleaned.contains(table))
                .filter(table -> !PRESERVED.contains(table))
                .sorted()
                .toList();

        if (!forgotten.isEmpty()) {
            throw new IllegalStateException(
                    "DatabaseCleaner does not know about " + forgotten
                            + ". Add them to TABLES_IN_DELETE_ORDER (children before parents), "
                            + "or to PRESERVED if they hold seeded reference data. "
                            + "Until then tests will leak rows into each other.");
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> existingTables() {
        // The test datasource sets DATABASE_TO_LOWER, so the schema is
        // 'public', not 'PUBLIC'. Comparing case-sensitively matched nothing
        // and the cleaner quietly deleted no rows at all.
        List<Object> names = entityManager
                .createNativeQuery("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE UPPER(TABLE_SCHEMA) = 'PUBLIC'")
                .getResultList();

        return names.stream()
                .map(String::valueOf)
                .map(name -> name.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }
}
