-- =====================================================================
-- V14 : Attendance regularization and work from home
--
-- Regularization is the one path that rewrites an attendance record, so it
-- carries a frozen snapshot of what the day looked like beforehand. Combined
-- with the append-only punch-event log, that means an auditor can always see
-- both what the device reported and what was approved instead.
-- =====================================================================

CREATE TABLE attendance_regularizations (
    id                            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    request_number                VARCHAR(30)     NOT NULL,
    employee_id                   BIGINT UNSIGNED NOT NULL,
    attendance_date               DATE            NOT NULL,
    -- Null when the employee never punched at all, which is the most common
    -- reason for asking in the first place.
    attendance_id                 BIGINT UNSIGNED NULL,

    reason_type                   VARCHAR(30)     NOT NULL,
    requested_punch_in_time       TIMESTAMP       NULL,
    requested_punch_out_time      TIMESTAMP       NULL,
    requested_work_mode           VARCHAR(20)     NULL,

    -- Frozen at submission. Not derived later from the attendance row, because
    -- by then it has already been overwritten by the approval.
    original_punch_in_time        TIMESTAMP       NULL,
    original_punch_out_time       TIMESTAMP       NULL,
    original_working_minutes      INT             NULL,
    original_status               VARCHAR(30)     NULL,

    reason                        VARCHAR(1000)   NOT NULL,
    attachment_url                VARCHAR(500)    NULL,

    status                        VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    current_level                 SMALLINT        NOT NULL DEFAULT 1,
    current_approver_employee_id  BIGINT UNSIGNED NULL,

    applied_at                    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at                    TIMESTAMP       NULL,
    cancellation_reason           VARCHAR(500)    NULL,

    created_at                    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                    BIGINT UNSIGNED NULL,
    updated_by                    BIGINT UNSIGNED NULL,
    version                       INT             NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_regularization_number (request_number),
    KEY idx_regularization_employee (employee_id, status),
    KEY idx_regularization_approver (current_approver_employee_id, status),
    KEY idx_regularization_date (employee_id, attendance_date),
    CONSTRAINT fk_regularization_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE RESTRICT,
    CONSTRAINT fk_regularization_attendance FOREIGN KEY (attendance_id) REFERENCES attendance (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE regularization_approvals (
    id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    regularization_id           BIGINT UNSIGNED NOT NULL,
    level                       SMALLINT        NOT NULL,
    approver_employee_id        BIGINT UNSIGNED NULL,
    approver_role               VARCHAR(30)     NULL,
    action                      VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    comments                    VARCHAR(1000)   NULL,
    acted_at                    TIMESTAMP       NULL,
    delegated_from_employee_id  BIGINT UNSIGNED NULL,
    created_at                  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                  BIGINT UNSIGNED NULL,
    updated_by                  BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_regularization_approval_level (regularization_id, level),
    KEY idx_regularization_approvals_approver (approver_employee_id, action),
    CONSTRAINT fk_regularization_approvals_request FOREIGN KEY (regularization_id)
        REFERENCES attendance_regularizations (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE work_from_home_requests (
    id                            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    request_number                VARCHAR(30)     NOT NULL,
    employee_id                   BIGINT UNSIGNED NOT NULL,

    from_date                     DATE            NOT NULL,
    to_date                       DATE            NOT NULL,
    total_days                    SMALLINT        NOT NULL,

    reason                        VARCHAR(1000)   NOT NULL,
    work_location                 VARCHAR(20)     NOT NULL DEFAULT 'HOME',
    contact_number                VARCHAR(50)     NULL,

    status                        VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    current_level                 SMALLINT        NOT NULL DEFAULT 1,
    current_approver_employee_id  BIGINT UNSIGNED NULL,

    applied_at                    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at                    TIMESTAMP       NULL,
    cancellation_reason           VARCHAR(500)    NULL,

    created_at                    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                    BIGINT UNSIGNED NULL,
    updated_by                    BIGINT UNSIGNED NULL,
    version                       INT             NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_wfh_number (request_number),
    KEY idx_wfh_employee (employee_id, status),
    KEY idx_wfh_approver (current_approver_employee_id, status),
    CONSTRAINT fk_wfh_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE RESTRICT,
    CONSTRAINT ck_wfh_range CHECK (to_date >= from_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Exploded days, for the same reason leave has them: punch-in looks this up by
-- (employee_id, date) on every punch, and a range scan would not hold up.
CREATE TABLE wfh_request_days (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    wfh_request_id BIGINT UNSIGNED NOT NULL,
    employee_id    BIGINT UNSIGNED NOT NULL,
    wfh_date       DATE            NOT NULL,
    day_type       VARCHAR(20)     NOT NULL DEFAULT 'WORKING',
    created_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wfh_day (wfh_request_id, wfh_date),
    KEY idx_wfh_days_employee_date (employee_id, wfh_date),
    CONSTRAINT fk_wfh_days_request FOREIGN KEY (wfh_request_id)
        REFERENCES work_from_home_requests (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE wfh_approvals (
    id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    wfh_request_id              BIGINT UNSIGNED NOT NULL,
    level                       SMALLINT        NOT NULL,
    approver_employee_id        BIGINT UNSIGNED NULL,
    approver_role               VARCHAR(30)     NULL,
    action                      VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    comments                    VARCHAR(1000)   NULL,
    acted_at                    TIMESTAMP       NULL,
    delegated_from_employee_id  BIGINT UNSIGNED NULL,
    created_at                  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                  BIGINT UNSIGNED NULL,
    updated_by                  BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wfh_approval_level (wfh_request_id, level),
    KEY idx_wfh_approvals_approver (approver_employee_id, action),
    CONSTRAINT fk_wfh_approvals_request FOREIGN KEY (wfh_request_id)
        REFERENCES work_from_home_requests (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Work-from-home limits belong with the other attendance rules.
ALTER TABLE attendance_policies
    ADD COLUMN max_wfh_days_per_month SMALLINT NOT NULL DEFAULT 8 AFTER max_regularizations_per_month,
    ADD COLUMN wfh_min_notice_days    SMALLINT NOT NULL DEFAULT 0 AFTER max_wfh_days_per_month;
