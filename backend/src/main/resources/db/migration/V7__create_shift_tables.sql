-- =====================================================================
-- V7 : Shifts and effective-dated shift assignment
--
-- Thresholds live on the shift rather than in code, and assignment is
-- effective-dated so rotation is just future-dated rows and recomputing an
-- old day picks the shift that was actually in force then.
-- =====================================================================

CREATE TABLE shifts (
    id                              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code                            VARCHAR(30)     NOT NULL,
    name                            VARCHAR(120)    NOT NULL,
    start_time                      TIME            NOT NULL,
    end_time                        TIME            NOT NULL,
    -- Derived from start/end at write time, stored so queries can filter on it.
    is_night_shift                  BOOLEAN         NOT NULL DEFAULT FALSE,
    grace_period_minutes            SMALLINT        NOT NULL DEFAULT 15,
    early_exit_grace_minutes        SMALLINT        NOT NULL DEFAULT 15,
    standard_work_minutes           SMALLINT        NOT NULL DEFAULT 480,
    full_day_minutes                SMALLINT        NOT NULL DEFAULT 480,
    half_day_minutes                SMALLINT        NOT NULL DEFAULT 240,
    min_attendance_minutes          SMALLINT        NOT NULL DEFAULT 120,
    max_break_minutes               SMALLINT        NOT NULL DEFAULT 60,
    punch_in_window_before_minutes  SMALLINT        NOT NULL DEFAULT 120,
    punch_out_window_after_minutes  SMALLINT        NOT NULL DEFAULT 240,
    office_id                       BIGINT UNSIGNED NULL,
    description                     VARCHAR(500)    NULL,
    is_default                      BOOLEAN         NOT NULL DEFAULT FALSE,
    is_active                       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at                      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                      BIGINT UNSIGNED NULL,
    updated_by                      BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shifts_code (code),
    KEY idx_shifts_office (office_id, is_active),
    KEY idx_shifts_active (is_active),
    CONSTRAINT fk_shifts_office FOREIGN KEY (office_id) REFERENCES offices (id) ON DELETE RESTRICT,
    CONSTRAINT ck_shifts_half_day CHECK (half_day_minutes <= full_day_minutes),
    CONSTRAINT ck_shifts_min_attendance CHECK (min_attendance_minutes <= half_day_minutes)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE employee_shifts (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    employee_id    BIGINT UNSIGNED NOT NULL,
    shift_id       BIGINT UNSIGNED NOT NULL,
    effective_from DATE            NOT NULL,
    effective_to   DATE            NULL,
    notes          VARCHAR(255)    NULL,
    created_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by     BIGINT UNSIGNED NULL,
    updated_by     BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    KEY idx_employee_shifts_lookup (employee_id, effective_from, effective_to),
    KEY idx_employee_shifts_shift (shift_id),
    CONSTRAINT fk_employee_shifts_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE CASCADE,
    CONSTRAINT fk_employee_shifts_shift FOREIGN KEY (shift_id) REFERENCES shifts (id) ON DELETE RESTRICT,
    CONSTRAINT ck_employee_shifts_range CHECK (effective_to IS NULL OR effective_to >= effective_from)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO shifts (code, name, start_time, end_time, is_night_shift, grace_period_minutes,
                    standard_work_minutes, full_day_minutes, half_day_minutes, is_default, is_active)
VALUES
  ('GEN',     'General Shift',  '09:30:00', '18:30:00', FALSE, 15, 480, 480, 240, TRUE,  TRUE),
  ('MORNING', 'Morning Shift',  '06:00:00', '15:00:00', FALSE, 10, 480, 480, 240, FALSE, TRUE),
  ('EVENING', 'Evening Shift',  '14:00:00', '23:00:00', FALSE, 10, 480, 480, 240, FALSE, TRUE),
  ('NIGHT',   'Night Shift',    '22:00:00', '07:00:00', TRUE,  10, 480, 480, 240, FALSE, TRUE);

-- Put the bootstrap administrator on the general shift from their joining date.
INSERT INTO employee_shifts (employee_id, shift_id, effective_from)
SELECT e.id, s.id, e.joining_date
FROM employees e
CROSS JOIN shifts s
WHERE e.employee_code = 'EMP0001' AND s.code = 'GEN';
