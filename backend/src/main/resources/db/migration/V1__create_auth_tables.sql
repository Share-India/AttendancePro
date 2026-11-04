-- =====================================================================
-- V1 : Identity & access
-- Audit columns (created_by / updated_by) are plain BIGINT without an FK
-- to users on purpose: an FK on every table to a single hot row would make
-- users a lock hotspot, and the audit reference must survive user removal.
-- =====================================================================

CREATE TABLE users (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    employee_id             BIGINT UNSIGNED NULL,
    username                VARCHAR(64)     NOT NULL,
    email                   VARCHAR(150)    NOT NULL,
    password_hash           VARCHAR(100)    NOT NULL,
    is_active               BOOLEAN         NOT NULL DEFAULT TRUE,
    is_locked               BOOLEAN         NOT NULL DEFAULT FALSE,
    locked_until            TIMESTAMP       NULL,
    failed_login_attempts   SMALLINT        NOT NULL DEFAULT 0,
    must_change_password    BOOLEAN         NOT NULL DEFAULT FALSE,
    password_changed_at     TIMESTAMP       NULL,
    last_login_at           TIMESTAMP       NULL,
    last_login_ip           VARCHAR(45)     NULL,
    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by              BIGINT UNSIGNED NULL,
    updated_by              BIGINT UNSIGNED NULL,
    version                 INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email),
    UNIQUE KEY uk_users_employee (employee_id),
    KEY idx_users_active (is_active)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE roles (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(50)     NOT NULL,
    name        VARCHAR(100)    NOT NULL,
    description VARCHAR(255)    NULL,
    is_system   BOOLEAN         NOT NULL DEFAULT FALSE,
    is_active   BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by  BIGINT UNSIGNED NULL,
    updated_by  BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_roles_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE permissions (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code        VARCHAR(80)     NOT NULL,
    name        VARCHAR(120)    NOT NULL,
    module      VARCHAR(50)     NOT NULL,
    description VARCHAR(255)    NULL,
    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by  BIGINT UNSIGNED NULL,
    updated_by  BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_permissions_code (code),
    KEY idx_permissions_module (module)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE user_roles (
    user_id     BIGINT UNSIGNED NOT NULL,
    role_id     BIGINT UNSIGNED NOT NULL,
    assigned_at TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    assigned_by BIGINT UNSIGNED NULL,
    PRIMARY KEY (user_id, role_id),
    KEY idx_user_roles_role (role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE role_permissions (
    role_id       BIGINT UNSIGNED NOT NULL,
    permission_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    KEY idx_role_permissions_permission (permission_id),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Raw refresh tokens are never stored. token_hash is SHA-256 of the opaque token.
-- family_id ties a rotation chain together so reuse of a rotated token can revoke all of it.
CREATE TABLE refresh_tokens (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id              BIGINT UNSIGNED NOT NULL,
    token_hash           CHAR(64)        NOT NULL,
    family_id            CHAR(36)        NOT NULL,
    issued_at            TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at           TIMESTAMP       NOT NULL,
    revoked_at           TIMESTAMP       NULL,
    revoked_reason       VARCHAR(60)     NULL,
    replaced_by_token_id BIGINT UNSIGNED NULL,
    device_info          VARCHAR(255)    NULL,
    ip_address           VARCHAR(45)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_hash (token_hash),
    KEY idx_refresh_tokens_user (user_id, expires_at),
    KEY idx_refresh_tokens_family (family_id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE password_reset_tokens (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id      BIGINT UNSIGNED NOT NULL,
    token_hash   CHAR(64)        NOT NULL,
    expires_at   TIMESTAMP       NOT NULL,
    used_at      TIMESTAMP       NULL,
    requested_ip VARCHAR(45)     NULL,
    created_at   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_password_reset_hash (token_hash),
    KEY idx_password_reset_user (user_id, expires_at),
    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE login_attempts (
    id                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    username_attempted VARCHAR(150)    NOT NULL,
    user_id            BIGINT UNSIGNED NULL,
    successful         BOOLEAN         NOT NULL,
    failure_reason     VARCHAR(60)     NULL,
    ip_address         VARCHAR(45)     NULL,
    user_agent         VARCHAR(255)    NULL,
    attempted_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_login_attempts_username (username_attempted, attempted_at),
    KEY idx_login_attempts_ip (ip_address, attempted_at),
    KEY idx_login_attempts_user (user_id, attempted_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
