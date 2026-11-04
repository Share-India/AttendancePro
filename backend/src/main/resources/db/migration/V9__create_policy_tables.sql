-- =====================================================================
-- V9 : Policy tables
--
-- Every threshold the attendance engine reads comes from here. Nothing in the
-- punch or calculation path may use a constant.
--
-- Typed columns rather than key/value: these rules are finite, hot, and need to
-- be queryable ("which employees have a grace period over 15 minutes?"), which
-- pure EAV cannot answer in SQL. The long tail lives in company_settings.
-- =====================================================================

CREATE TABLE attendance_policies (
    id                            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code                          VARCHAR(30)     NOT NULL,
    name                          VARCHAR(120)    NOT NULL,
    description                   VARCHAR(500)    NULL,

    -- Hour thresholds. A shift may override these; the resolver prefers the
    -- shift's own values when present, and falls back to the policy.
    full_day_minutes              SMALLINT        NOT NULL DEFAULT 480,
    half_day_minutes              SMALLINT        NOT NULL DEFAULT 240,
    min_attendance_minutes        SMALLINT        NOT NULL DEFAULT 120,

    grace_period_minutes          SMALLINT        NOT NULL DEFAULT 15,
    early_exit_grace_minutes      SMALLINT        NOT NULL DEFAULT 15,

    late_marks_for_half_day       SMALLINT        NOT NULL DEFAULT 3,
    late_mark_reset_period        VARCHAR(20)     NOT NULL DEFAULT 'MONTHLY',

    max_break_minutes             SMALLINT        NOT NULL DEFAULT 60,
    deduct_break_from_working     BOOLEAN         NOT NULL DEFAULT TRUE,

    allow_punch_on_holiday        BOOLEAN         NOT NULL DEFAULT TRUE,
    allow_punch_on_weekly_off     BOOLEAN         NOT NULL DEFAULT TRUE,
    enforce_punch_window          BOOLEAN         NOT NULL DEFAULT TRUE,

    auto_absent_enabled           BOOLEAN         NOT NULL DEFAULT TRUE,
    auto_punch_out_enabled        BOOLEAN         NOT NULL DEFAULT FALSE,

    regularization_window_days    SMALLINT        NOT NULL DEFAULT 7,
    max_regularizations_per_month SMALLINT        NOT NULL DEFAULT 3,

    is_active                     BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at                    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                    BIGINT UNSIGNED NULL,
    updated_by                    BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_policies_code (code),
    CONSTRAINT ck_attendance_policy_half CHECK (half_day_minutes <= full_day_minutes),
    CONSTRAINT ck_attendance_policy_min CHECK (min_attendance_minutes <= half_day_minutes)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE location_policies (
    id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code                        VARCHAR(30)     NOT NULL,
    name                        VARCHAR(120)    NOT NULL,
    description                 VARCHAR(500)    NULL,

    enforce_geofence            BOOLEAN         NOT NULL DEFAULT TRUE,
    -- Rejects a punch whose GPS fix is too vague to place inside a radius.
    max_accuracy_meters         SMALLINT        NOT NULL DEFAULT 100,
    allow_wfh_without_geofence  BOOLEAN         NOT NULL DEFAULT TRUE,
    allow_field_punch           BOOLEAN         NOT NULL DEFAULT FALSE,
    -- Off by default. Continuous location history is a privacy decision, not a
    -- convenience, and must be turned on deliberately.
    track_field_movement        BOOLEAN         NOT NULL DEFAULT FALSE,
    -- Whether a suspicion flag rejects the punch or merely marks it.
    block_on_suspicious         BOOLEAN         NOT NULL DEFAULT FALSE,
    allowed_countries           VARCHAR(255)    NULL,

    is_active                   BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                  BIGINT UNSIGNED NULL,
    updated_by                  BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_location_policies_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE overtime_policies (
    id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code                        VARCHAR(30)     NOT NULL,
    name                        VARCHAR(120)    NOT NULL,
    min_overtime_minutes        SMALLINT        NOT NULL DEFAULT 30,
    max_daily_overtime_minutes  SMALLINT        NOT NULL DEFAULT 240,
    requires_approval           BOOLEAN         NOT NULL DEFAULT TRUE,
    applicable_on_weekly_off    BOOLEAN         NOT NULL DEFAULT TRUE,
    applicable_on_holiday       BOOLEAN         NOT NULL DEFAULT TRUE,
    is_active                   BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                  BIGINT UNSIGNED NULL,
    updated_by                  BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_overtime_policies_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Binds a policy to a scope. Resolution is most-specific-wins:
-- EMPLOYEE > DEPARTMENT > EMPLOYMENT_TYPE > OFFICE > GLOBAL.
CREATE TABLE policy_assignments (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    policy_type    VARCHAR(30)     NOT NULL,
    policy_id      BIGINT UNSIGNED NOT NULL,
    scope_type     VARCHAR(30)     NOT NULL,
    scope_id       BIGINT UNSIGNED NULL,
    scope_value    VARCHAR(40)     NULL,
    priority       SMALLINT        NOT NULL DEFAULT 0,
    effective_from DATE            NULL,
    effective_to   DATE            NULL,
    is_active      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by     BIGINT UNSIGNED NULL,
    updated_by     BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    KEY idx_policy_assignments_lookup (policy_type, scope_type, scope_id, is_active),
    KEY idx_policy_assignments_policy (policy_type, policy_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Defaults, bound at GLOBAL scope so every employee resolves something.
INSERT INTO attendance_policies (code, name, description) VALUES
  ('DEFAULT', 'Default Attendance Policy', 'Applies wherever nothing more specific is assigned');

INSERT INTO location_policies (code, name, description) VALUES
  ('DEFAULT', 'Default Location Policy', 'Geofence enforced; work-from-home exempt once approved');

INSERT INTO overtime_policies (code, name) VALUES
  ('DEFAULT', 'Default Overtime Policy');

INSERT INTO policy_assignments (policy_type, policy_id, scope_type, priority)
SELECT 'ATTENDANCE', id, 'GLOBAL', 0 FROM attendance_policies WHERE code = 'DEFAULT';

INSERT INTO policy_assignments (policy_type, policy_id, scope_type, priority)
SELECT 'LOCATION', id, 'GLOBAL', 0 FROM location_policies WHERE code = 'DEFAULT';

INSERT INTO policy_assignments (policy_type, policy_id, scope_type, priority)
SELECT 'OVERTIME', id, 'GLOBAL', 0 FROM overtime_policies WHERE code = 'DEFAULT';
