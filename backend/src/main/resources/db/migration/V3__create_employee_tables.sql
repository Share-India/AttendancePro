-- =====================================================================
-- V3 : Employees and reporting hierarchy, then close the circular FKs
-- left open by V1 and V2.
-- =====================================================================

CREATE TABLE employees (
    id                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    employee_code            VARCHAR(30)     NOT NULL,
    first_name               VARCHAR(80)     NOT NULL,
    middle_name              VARCHAR(80)     NULL,
    last_name                VARCHAR(80)     NOT NULL,
    official_email           VARCHAR(150)    NOT NULL,
    personal_email           VARCHAR(150)    NULL,
    mobile                   VARCHAR(20)     NULL,
    alternate_mobile         VARCHAR(20)     NULL,
    date_of_birth            DATE            NULL,
    gender                   VARCHAR(20)     NULL,
    blood_group              VARCHAR(10)     NULL,
    joining_date             DATE            NOT NULL,
    confirmation_date        DATE            NULL,
    exit_date                DATE            NULL,
    department_id            BIGINT UNSIGNED NULL,
    designation_id           BIGINT UNSIGNED NULL,
    office_id                BIGINT UNSIGNED NULL,
    employment_type          VARCHAR(20)     NOT NULL DEFAULT 'PERMANENT',
    employment_status        VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    profile_photo_url        VARCHAR(500)    NULL,
    emergency_contact_name   VARCHAR(120)    NULL,
    emergency_contact_number VARCHAR(20)     NULL,
    is_active                BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by               BIGINT UNSIGNED NULL,
    updated_by               BIGINT UNSIGNED NULL,
    version                  INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_employees_code (employee_code),
    UNIQUE KEY uk_employees_official_email (official_email),
    KEY idx_employees_department (department_id, is_active),
    KEY idx_employees_office (office_id, is_active),
    KEY idx_employees_designation (designation_id),
    KEY idx_employees_status (employment_status),
    KEY idx_employees_name (last_name, first_name),
    CONSTRAINT fk_employees_department FOREIGN KEY (department_id) REFERENCES departments (id) ON DELETE RESTRICT,
    CONSTRAINT fk_employees_designation FOREIGN KEY (designation_id) REFERENCES designations (id) ON DELETE RESTRICT,
    CONSTRAINT fk_employees_office FOREIGN KEY (office_id) REFERENCES offices (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Reporting lives in its own effective-dated table so a re-org preserves
-- history: a leave approved in March still shows the March approver.
CREATE TABLE employee_managers (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    employee_id         BIGINT UNSIGNED NOT NULL,
    manager_employee_id BIGINT UNSIGNED NOT NULL,
    relationship_type   VARCHAR(30)     NOT NULL DEFAULT 'PRIMARY',
    effective_from      DATE            NOT NULL,
    effective_to        DATE            NULL,
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by          BIGINT UNSIGNED NULL,
    updated_by          BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    KEY idx_employee_managers_employee (employee_id, relationship_type, effective_to),
    KEY idx_employee_managers_manager (manager_employee_id, is_active),
    CONSTRAINT fk_employee_managers_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE CASCADE,
    CONSTRAINT fk_employee_managers_manager FOREIGN KEY (manager_employee_id) REFERENCES employees (id) ON DELETE RESTRICT,
    CONSTRAINT ck_employee_managers_not_self CHECK (employee_id <> manager_employee_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Close the circular references now that both sides exist.
ALTER TABLE users
    ADD CONSTRAINT fk_users_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE SET NULL;

ALTER TABLE departments
    ADD CONSTRAINT fk_departments_head FOREIGN KEY (department_head_employee_id) REFERENCES employees (id) ON DELETE SET NULL;
