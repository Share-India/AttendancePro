-- =====================================================================
-- V2 : Organization masters
-- department_head_employee_id has no FK yet; employees is created in V3
-- and the constraint is added there.
-- =====================================================================

CREATE TABLE departments (
    id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code                        VARCHAR(30)     NOT NULL,
    name                        VARCHAR(120)    NOT NULL,
    parent_department_id        BIGINT UNSIGNED NULL,
    department_head_employee_id BIGINT UNSIGNED NULL,
    description                 VARCHAR(500)    NULL,
    is_active                   BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                  BIGINT UNSIGNED NULL,
    updated_by                  BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_departments_code (code),
    KEY idx_departments_parent (parent_department_id),
    KEY idx_departments_active (is_active),
    CONSTRAINT fk_departments_parent FOREIGN KEY (parent_department_id) REFERENCES departments (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE designations (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code          VARCHAR(30)     NOT NULL,
    name          VARCHAR(120)    NOT NULL,
    level         SMALLINT        NOT NULL DEFAULT 0,
    department_id BIGINT UNSIGNED NULL,
    description   VARCHAR(500)    NULL,
    is_active     BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by    BIGINT UNSIGNED NULL,
    updated_by    BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_designations_code (code),
    KEY idx_designations_department (department_id),
    CONSTRAINT fk_designations_department FOREIGN KEY (department_id) REFERENCES departments (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- offices.timezone is the anchor for every business-date calculation.
CREATE TABLE offices (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code          VARCHAR(30)     NOT NULL,
    name          VARCHAR(120)    NOT NULL,
    address_line1 VARCHAR(255)    NULL,
    address_line2 VARCHAR(255)    NULL,
    city          VARCHAR(100)    NULL,
    state         VARCHAR(100)    NULL,
    country       VARCHAR(100)    NULL,
    postal_code   VARCHAR(20)     NULL,
    timezone      VARCHAR(64)     NOT NULL DEFAULT 'Asia/Kolkata',
    is_active     BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by    BIGINT UNSIGNED NULL,
    updated_by    BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_offices_code (code),
    KEY idx_offices_active (is_active)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- One office holds many geofence circles. A campus with three gates is three
-- rows, not one oversized radius.
CREATE TABLE office_locations (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    office_id             BIGINT UNSIGNED NOT NULL,
    location_name         VARCHAR(150)    NOT NULL,
    address               VARCHAR(500)    NULL,
    latitude              DECIMAL(10, 7)  NOT NULL,
    longitude             DECIMAL(10, 7)  NOT NULL,
    allowed_radius_meters INT             NOT NULL DEFAULT 200,
    location_type         VARCHAR(30)     NOT NULL DEFAULT 'OFFICE',
    is_open_to_all        BOOLEAN         NOT NULL DEFAULT FALSE,
    is_active             BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by            BIGINT UNSIGNED NULL,
    updated_by            BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    KEY idx_office_locations_office (office_id, is_active),
    KEY idx_office_locations_coords (latitude, longitude),
    CONSTRAINT fk_office_locations_office FOREIGN KEY (office_id) REFERENCES offices (id) ON DELETE RESTRICT,
    CONSTRAINT ck_office_locations_lat CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_office_locations_lng CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_office_locations_radius CHECK (allowed_radius_meters > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
