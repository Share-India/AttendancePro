-- =====================================================================
-- V11 : Attendance
--
-- `attendance` is the derived daily summary, one row per employee per business
-- day. `attendance_punch_events` is the append-only evidence log: nothing ever
-- updates or deletes a row there. Regularization rewrites the summary and adds
-- a SYSTEM-source event, so the original device evidence always survives.
-- =====================================================================

CREATE TABLE attendance (
    id                            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    employee_id                   BIGINT UNSIGNED NOT NULL,
    -- Business date in the OFFICE's timezone, not the server's. For a night
    -- shift this is the date the shift started.
    attendance_date               DATE            NOT NULL,
    shift_id                      BIGINT UNSIGNED NULL,

    punch_in_time                 TIMESTAMP       NULL,
    punch_out_time                TIMESTAMP       NULL,

    punch_in_latitude             DECIMAL(10, 7)  NULL,
    punch_in_longitude            DECIMAL(10, 7)  NULL,
    punch_out_latitude            DECIMAL(10, 7)  NULL,
    punch_out_longitude           DECIMAL(10, 7)  NULL,
    punch_in_accuracy             DECIMAL(7, 2)   NULL,
    punch_out_accuracy            DECIMAL(7, 2)   NULL,
    punch_in_location             VARCHAR(255)    NULL,
    punch_out_location            VARCHAR(255)    NULL,
    punch_in_office_location_id   BIGINT UNSIGNED NULL,
    punch_out_office_location_id  BIGINT UNSIGNED NULL,
    punch_in_distance_meters      DECIMAL(10, 2)  NULL,
    punch_out_distance_meters     DECIMAL(10, 2)  NULL,
    punch_in_ip                   VARCHAR(45)     NULL,
    punch_out_ip                  VARCHAR(45)     NULL,
    punch_in_device               VARCHAR(255)    NULL,
    punch_out_device              VARCHAR(255)    NULL,

    -- Durations are whole minutes. Never floating-point hours.
    working_minutes               INT             NOT NULL DEFAULT 0,
    break_minutes                 INT             NOT NULL DEFAULT 0,
    overtime_minutes              INT             NOT NULL DEFAULT 0,
    late_minutes                  INT             NOT NULL DEFAULT 0,
    early_exit_minutes            INT             NOT NULL DEFAULT 0,

    attendance_status             VARCHAR(30)     NOT NULL DEFAULT 'PUNCHED_IN',
    work_mode                     VARCHAR(20)     NOT NULL DEFAULT 'OFFICE',
    source                        VARCHAR(20)     NOT NULL DEFAULT 'WEB',

    is_regularized                BOOLEAN         NOT NULL DEFAULT FALSE,
    is_suspicious                 BOOLEAN         NOT NULL DEFAULT FALSE,
    suspicion_reasons             JSON            NULL,

    is_locked                     BOOLEAN         NOT NULL DEFAULT FALSE,
    locked_at                     TIMESTAMP       NULL,
    locked_by                     BIGINT UNSIGNED NULL,

    remarks                       VARCHAR(500)    NULL,

    created_at                    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                    BIGINT UNSIGNED NULL,
    updated_by                    BIGINT UNSIGNED NULL,
    version                       INT             NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    -- The concurrency backbone. Two simultaneous punch-ins (double click, two
    -- tabs) cannot both create a row; the loser gets a constraint violation
    -- that the service translates to ALREADY_PUNCHED_IN. More reliable than
    -- check-then-insert, which races.
    UNIQUE KEY uk_attendance_employee_date (employee_id, attendance_date),
    KEY idx_attendance_date_status (attendance_date, attendance_status),
    KEY idx_attendance_emp_date (employee_id, attendance_date DESC),
    KEY idx_attendance_shift (shift_id),
    KEY idx_attendance_suspicious (is_suspicious, attendance_date),
    CONSTRAINT fk_attendance_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE RESTRICT,
    CONSTRAINT fk_attendance_shift FOREIGN KEY (shift_id) REFERENCES shifts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_attendance_in_location FOREIGN KEY (punch_in_office_location_id) REFERENCES office_locations (id) ON DELETE SET NULL,
    CONSTRAINT fk_attendance_out_location FOREIGN KEY (punch_out_office_location_id) REFERENCES office_locations (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Append-only. No UPDATE, no DELETE, ever.
CREATE TABLE attendance_punch_events (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    attendance_id        BIGINT UNSIGNED NOT NULL,
    employee_id          BIGINT UNSIGNED NOT NULL,
    event_type           VARCHAR(20)     NOT NULL,
    event_time           TIMESTAMP       NOT NULL,
    -- What the browser claimed, kept alongside the server's own timestamp so a
    -- skewed client clock is visible rather than silently trusted.
    client_reported_time TIMESTAMP       NULL,
    latitude             DECIMAL(10, 7)  NULL,
    longitude            DECIMAL(10, 7)  NULL,
    accuracy_meters      DECIMAL(7, 2)   NULL,
    office_location_id   BIGINT UNSIGNED NULL,
    distance_meters      DECIMAL(10, 2)  NULL,
    within_geofence      BOOLEAN         NULL,
    work_mode            VARCHAR(20)     NOT NULL DEFAULT 'OFFICE',
    ip_address           VARCHAR(45)     NULL,
    user_agent           VARCHAR(255)    NULL,
    device_info          VARCHAR(255)    NULL,
    source               VARCHAR(20)     NOT NULL DEFAULT 'WEB',
    is_suspicious        BOOLEAN         NOT NULL DEFAULT FALSE,
    suspicion_reasons    JSON            NULL,
    created_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_punch_events_attendance (attendance_id, event_time),
    KEY idx_punch_events_employee (employee_id, event_time),
    CONSTRAINT fk_punch_events_attendance FOREIGN KEY (attendance_id) REFERENCES attendance (id) ON DELETE CASCADE,
    CONSTRAINT fk_punch_events_location FOREIGN KEY (office_location_id) REFERENCES office_locations (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE break_types (
    id                              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code                            VARCHAR(30)     NOT NULL,
    name                            VARCHAR(120)    NOT NULL,
    max_minutes                     SMALLINT        NOT NULL DEFAULT 60,
    is_paid                         BOOLEAN         NOT NULL DEFAULT TRUE,
    -- A paid break still counts as working time; an unpaid one is deducted.
    is_deducted_from_working_hours  BOOLEAN         NOT NULL DEFAULT TRUE,
    display_order                   SMALLINT        NOT NULL DEFAULT 0,
    is_active                       BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at                      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                      BIGINT UNSIGNED NULL,
    updated_by                      BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_break_types_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE attendance_breaks (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    attendance_id    BIGINT UNSIGNED NOT NULL,
    break_type_id    BIGINT UNSIGNED NOT NULL,
    break_start_time TIMESTAMP       NOT NULL,
    -- NULL means the break is still open; the employee is on break right now.
    break_end_time   TIMESTAMP       NULL,
    duration_minutes INT             NOT NULL DEFAULT 0,
    start_event_id   BIGINT UNSIGNED NULL,
    end_event_id     BIGINT UNSIGNED NULL,
    exceeded_limit   BOOLEAN         NOT NULL DEFAULT FALSE,
    auto_closed      BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by       BIGINT UNSIGNED NULL,
    updated_by       BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    KEY idx_attendance_breaks_attendance (attendance_id),
    CONSTRAINT fk_attendance_breaks_attendance FOREIGN KEY (attendance_id) REFERENCES attendance (id) ON DELETE CASCADE,
    CONSTRAINT fk_attendance_breaks_type FOREIGN KEY (break_type_id) REFERENCES break_types (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE overtime_records (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    attendance_id         BIGINT UNSIGNED NOT NULL,
    employee_id           BIGINT UNSIGNED NOT NULL,
    overtime_date         DATE            NOT NULL,
    overtime_minutes      INT             NOT NULL DEFAULT 0,
    approved_minutes      INT             NULL,
    status                VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    approver_employee_id  BIGINT UNSIGNED NULL,
    acted_at              TIMESTAMP       NULL,
    comments              VARCHAR(500)    NULL,
    created_at            TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by            BIGINT UNSIGNED NULL,
    updated_by            BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_overtime_attendance (attendance_id),
    KEY idx_overtime_employee_date (employee_id, overtime_date),
    KEY idx_overtime_status (status),
    CONSTRAINT fk_overtime_attendance FOREIGN KEY (attendance_id) REFERENCES attendance (id) ON DELETE CASCADE,
    CONSTRAINT fk_overtime_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

INSERT INTO break_types (code, name, max_minutes, is_paid, is_deducted_from_working_hours, display_order) VALUES
  ('LUNCH',    'Lunch Break',    45, FALSE, TRUE,  1),
  ('TEA',      'Tea Break',      15, TRUE,  FALSE, 2),
  ('PERSONAL', 'Personal Break', 30, FALSE, TRUE,  3);
