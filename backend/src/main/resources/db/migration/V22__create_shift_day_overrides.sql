-- =====================================================================
-- V22 : Shift overrides for a particular weekday
--
-- Share India works a short Saturday: 10:00 to 14:30 for everyone, on the
-- Saturdays each person is rostered to work. Nothing in the schema could
-- express that. A shift carries the window and every threshold derived
-- from it, but employee_shifts assigns one by DATE RANGE only, so there
-- was no way to say "the general shift Monday to Friday, a shorter one on
-- Saturday".
--
-- Why not reuse weekly_offs.is_half_day, which already exists: a short
-- Saturday is a WORKING day, and any weekly-off rule short-circuits the
-- day to WEEKLY_OFF in AttendanceCalculationService before hours are
-- looked at. Marking it there would excuse the day rather than shorten
-- it, and would leave late and early-exit with no window to measure
-- against. Hours belong to the shift.
--
-- Scoped the same way weekly_offs is, so a department or an office can
-- differ later without another schema change. Today there is one row.
-- =====================================================================

CREATE TABLE shift_day_overrides (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type     VARCHAR(30)     NOT NULL DEFAULT 'GLOBAL',
    scope_id       BIGINT UNSIGNED NULL,
    -- ISO day-of-week, matching java.time.DayOfWeek: Monday = 1, Sunday = 7.
    day_of_week    TINYINT         NOT NULL,
    shift_id       BIGINT UNSIGNED NOT NULL,
    effective_from DATE            NULL,
    effective_to   DATE            NULL,
    is_active      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by     BIGINT UNSIGNED NULL,
    updated_by     BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    KEY idx_shift_day_overrides_lookup (day_of_week, is_active, scope_type, scope_id),
    CONSTRAINT ck_shift_day_overrides_dow CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT fk_shift_day_overrides_shift FOREIGN KEY (shift_id) REFERENCES shifts (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- The Saturday shift. Thresholds are scaled to the 270-minute window rather
-- than left at the 480-minute defaults: against a 480 full day, somebody who
-- worked the whole of their Saturday would be recorded as a half day.
INSERT INTO shifts (code, name, start_time, end_time, is_night_shift,
                    grace_period_minutes, early_exit_grace_minutes,
                    standard_work_minutes, full_day_minutes, half_day_minutes,
                    min_attendance_minutes, max_break_minutes,
                    punch_in_window_before_minutes, punch_out_window_after_minutes,
                    description, is_default, is_active)
SELECT 'SAT-HALF', 'Saturday Half Day', '10:00:00', '14:30:00', FALSE,
       15, 15,
       270, 270, 135,
       60, 30,
       60, 120,
       'Short Saturday worked by everyone rostered on that Saturday', FALSE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE code = 'SAT-HALF');

-- Everyone, every Saturday. On a Saturday somebody is rostered off, the
-- weekly-off rule decides the day first and this is never consulted.
INSERT INTO shift_day_overrides (scope_type, scope_id, day_of_week, shift_id, is_active)
SELECT 'GLOBAL', NULL, 6, s.id, TRUE
FROM shifts s
WHERE s.code = 'SAT-HALF'
  AND NOT EXISTS (
      SELECT 1 FROM shift_day_overrides o
       WHERE o.scope_type = 'GLOBAL' AND o.day_of_week = 6
  );
