-- =====================================================================
-- V12 : Leave
--
-- Two decisions carry this schema:
--
--   leave_request_days explodes each range into one row per date. That makes
--   overlap detection, holiday-aware counting and per-day attendance marking
--   exact rather than approximate.
--
--   leave_balances.pending_days is a reservation column. Without it an employee
--   with two days left can submit three two-day requests and have all three
--   approved, because each approval reads a balance the others have not yet
--   touched.
-- =====================================================================

CREATE TABLE leave_types (
    id                              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code                            VARCHAR(30)     NOT NULL,
    name                            VARCHAR(120)    NOT NULL,
    description                     VARCHAR(500)    NULL,

    is_paid                         BOOLEAN         NOT NULL DEFAULT TRUE,
    is_encashable                   BOOLEAN         NOT NULL DEFAULT FALSE,
    allow_half_day                  BOOLEAN         NOT NULL DEFAULT TRUE,

    requires_attachment             BOOLEAN         NOT NULL DEFAULT FALSE,
    -- Sick leave typically needs a certificate only past a few days.
    attachment_required_after_days  SMALLINT        NULL,

    min_notice_days                 SMALLINT        NOT NULL DEFAULT 0,
    max_consecutive_days            SMALLINT        NULL,
    -- Sick leave is exempt from notice; planned leave is not.
    exempt_from_notice              BOOLEAN         NOT NULL DEFAULT FALSE,
    allow_backdated                 BOOLEAN         NOT NULL DEFAULT FALSE,

    applicable_gender               VARCHAR(10)     NOT NULL DEFAULT 'ALL',
    min_service_days                SMALLINT        NOT NULL DEFAULT 0,

    -- Whether a Friday-to-Monday leave costs four days or two. Data, not code:
    -- getting this wrong is the single most common leave-system bug.
    counts_holidays_weekly_offs     BOOLEAN         NOT NULL DEFAULT FALSE,

    accrual_type                    VARCHAR(20)     NOT NULL DEFAULT 'ANNUAL',
    accrual_days                    DECIMAL(5, 2)   NOT NULL DEFAULT 0,
    allow_carry_forward             BOOLEAN         NOT NULL DEFAULT FALSE,
    max_carry_forward_days          DECIMAL(5, 2)   NULL,
    allow_negative_balance          BOOLEAN         NOT NULL DEFAULT FALSE,

    -- Above this many days the request also needs HR, not just the manager.
    hr_approval_threshold_days      DECIMAL(5, 2)   NULL,

    display_order                   SMALLINT        NOT NULL DEFAULT 0,
    is_active                       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at                      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                      BIGINT UNSIGNED NULL,
    updated_by                      BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_leave_types_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Quota by scope, resolved most-specific-first like the other policies.
CREATE TABLE leave_policies (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    leave_type_id        BIGINT UNSIGNED NOT NULL,
    scope_type           VARCHAR(30)     NOT NULL DEFAULT 'GLOBAL',
    scope_id             BIGINT UNSIGNED NULL,
    scope_value          VARCHAR(40)     NULL,
    annual_quota         DECIMAL(5, 2)   NOT NULL DEFAULT 0,
    -- A joiner in July gets half the year's entitlement, not all of it.
    pro_rata_on_joining  BOOLEAN         NOT NULL DEFAULT TRUE,
    priority             SMALLINT        NOT NULL DEFAULT 0,
    effective_from       DATE            NULL,
    effective_to         DATE            NULL,
    is_active            BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by           BIGINT UNSIGNED NULL,
    updated_by           BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    KEY idx_leave_policies_lookup (leave_type_id, scope_type, scope_id, is_active),
    CONSTRAINT fk_leave_policies_type FOREIGN KEY (leave_type_id) REFERENCES leave_types (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- A ledger. Available is derived in the service, never stored: a stored
-- derived column drifts from its inputs and nobody notices until it is wrong.
CREATE TABLE leave_balances (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    employee_id          BIGINT UNSIGNED NOT NULL,
    leave_type_id        BIGINT UNSIGNED NOT NULL,
    leave_year           SMALLINT        NOT NULL,

    opening_balance      DECIMAL(6, 2)   NOT NULL DEFAULT 0,
    credited_days        DECIMAL(6, 2)   NOT NULL DEFAULT 0,
    carried_forward_days DECIMAL(6, 2)   NOT NULL DEFAULT 0,
    used_days            DECIMAL(6, 2)   NOT NULL DEFAULT 0,
    -- Reserved by a submitted request, not yet approved.
    pending_days         DECIMAL(6, 2)   NOT NULL DEFAULT 0,
    encashed_days        DECIMAL(6, 2)   NOT NULL DEFAULT 0,
    lapsed_days          DECIMAL(6, 2)   NOT NULL DEFAULT 0,
    adjusted_days        DECIMAL(6, 2)   NOT NULL DEFAULT 0,

    created_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by           BIGINT UNSIGNED NULL,
    updated_by           BIGINT UNSIGNED NULL,
    version              INT             NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_leave_balance (employee_id, leave_type_id, leave_year),
    KEY idx_leave_balance_employee (employee_id, leave_year),
    CONSTRAINT fk_leave_balance_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE CASCADE,
    CONSTRAINT fk_leave_balance_type FOREIGN KEY (leave_type_id) REFERENCES leave_types (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE leave_requests (
    id                            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    request_number                VARCHAR(30)     NOT NULL,
    employee_id                   BIGINT UNSIGNED NOT NULL,
    leave_type_id                 BIGINT UNSIGNED NOT NULL,

    from_date                     DATE            NOT NULL,
    to_date                       DATE            NOT NULL,
    from_day_portion              VARCHAR(20)     NOT NULL DEFAULT 'FULL_DAY',
    to_day_portion                VARCHAR(20)     NOT NULL DEFAULT 'FULL_DAY',
    total_days                    DECIMAL(5, 2)   NOT NULL,

    reason                        VARCHAR(1000)   NOT NULL,
    attachment_url                VARCHAR(500)    NULL,
    emergency_contact             VARCHAR(50)     NULL,
    contact_during_leave          VARCHAR(50)     NULL,

    status                        VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    current_level                 SMALLINT        NOT NULL DEFAULT 1,
    current_approver_employee_id  BIGINT UNSIGNED NULL,

    applied_at                    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at                    TIMESTAMP       NULL,
    cancelled_at                  TIMESTAMP       NULL,
    cancellation_reason           VARCHAR(500)    NULL,

    created_at                    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                    BIGINT UNSIGNED NULL,
    updated_by                    BIGINT UNSIGNED NULL,
    version                       INT             NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_leave_request_number (request_number),
    KEY idx_leave_requests_employee (employee_id, status),
    KEY idx_leave_requests_approver (current_approver_employee_id, status),
    KEY idx_leave_requests_dates (from_date, to_date),
    KEY idx_leave_requests_type (leave_type_id, status),
    CONSTRAINT fk_leave_requests_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE RESTRICT,
    CONSTRAINT fk_leave_requests_type FOREIGN KEY (leave_type_id) REFERENCES leave_types (id) ON DELETE RESTRICT,
    CONSTRAINT ck_leave_request_range CHECK (to_date >= from_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- One row per calendar date in the range, including the non-deductible ones,
-- so the calendar can show why a day was or was not charged.
CREATE TABLE leave_request_days (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    leave_request_id BIGINT UNSIGNED NOT NULL,
    -- Denormalised so the overlap check is a single indexed lookup rather
    -- than a join on every apply.
    employee_id      BIGINT UNSIGNED NOT NULL,
    leave_date       DATE            NOT NULL,
    day_portion      VARCHAR(20)     NOT NULL DEFAULT 'FULL_DAY',
    is_deductible    BOOLEAN         NOT NULL DEFAULT TRUE,
    day_type         VARCHAR(20)     NOT NULL DEFAULT 'WORKING',
    created_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_leave_request_day (leave_request_id, leave_date),
    KEY idx_leave_days_employee_date (employee_id, leave_date),
    CONSTRAINT fk_leave_days_request FOREIGN KEY (leave_request_id) REFERENCES leave_requests (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE leave_approvals (
    id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    leave_request_id            BIGINT UNSIGNED NOT NULL,
    level                       SMALLINT        NOT NULL,
    approver_employee_id        BIGINT UNSIGNED NULL,
    approver_role               VARCHAR(30)     NULL,
    action                      VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    comments                    VARCHAR(1000)   NULL,
    acted_at                    TIMESTAMP       NULL,
    -- Records that someone acted in another approver's place.
    delegated_from_employee_id  BIGINT UNSIGNED NULL,
    created_at                  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_leave_approval_level (leave_request_id, level),
    KEY idx_leave_approvals_approver (approver_employee_id, action),
    CONSTRAINT fk_leave_approvals_request FOREIGN KEY (leave_request_id) REFERENCES leave_requests (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO leave_types
  (code, name, description, is_paid, allow_half_day, requires_attachment,
   attachment_required_after_days, min_notice_days, exempt_from_notice, allow_backdated,
   applicable_gender, counts_holidays_weekly_offs, accrual_type, accrual_days,
   allow_carry_forward, max_carry_forward_days, hr_approval_threshold_days, display_order)
VALUES
  ('CL',  'Casual Leave',    'Short personal absences',        TRUE, TRUE,  FALSE, NULL, 1, FALSE, FALSE, 'ALL',    FALSE, 'MONTHLY', 1.00, FALSE, NULL,  5,  1),
  ('SL',  'Sick Leave',      'Illness, with a certificate for longer absences', TRUE, TRUE, FALSE, 3, 0, TRUE, TRUE, 'ALL', FALSE, 'ANNUAL', 8.00, FALSE, NULL, 3, 2),
  ('EL',  'Earned Leave',    'Accrued paid leave',             TRUE, TRUE,  FALSE, NULL, 7, FALSE, FALSE, 'ALL',    TRUE,  'MONTHLY', 1.25, TRUE,  30.00, 5,  3),
  ('LWP', 'Leave Without Pay', 'Unpaid absence',               FALSE, TRUE, FALSE, NULL, 3, FALSE, FALSE, 'ALL',    TRUE,  'NONE',    0.00, FALSE, NULL,  1,  4),
  ('COMP','Comp Off',        'Time off in lieu of extra hours', TRUE, TRUE, FALSE, NULL, 1, FALSE, FALSE, 'ALL',    FALSE, 'NONE',    0.00, FALSE, NULL,  5,  5),
  ('MAT', 'Maternity Leave', 'Statutory maternity leave',      TRUE, FALSE, TRUE,  NULL, 30, FALSE, FALSE, 'FEMALE', TRUE,  'NONE',    0.00, FALSE, NULL,  1,  6),
  ('PAT', 'Paternity Leave', 'Statutory paternity leave',      TRUE, FALSE, FALSE, NULL, 15, FALSE, FALSE, 'MALE',   TRUE,  'NONE',    0.00, FALSE, NULL,  1,  7);

INSERT INTO leave_policies (leave_type_id, scope_type, annual_quota, pro_rata_on_joining)
SELECT id, 'GLOBAL',
       CASE code WHEN 'CL' THEN 12 WHEN 'SL' THEN 8 WHEN 'EL' THEN 15
                 WHEN 'LWP' THEN 365 WHEN 'COMP' THEN 0
                 WHEN 'MAT' THEN 182 WHEN 'PAT' THEN 15 ELSE 0 END,
       CASE WHEN code IN ('CL', 'EL') THEN TRUE ELSE FALSE END
FROM leave_types;
