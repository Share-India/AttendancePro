-- =====================================================================
-- V4 : Audit trail and company settings
-- audit_logs references entities loosely (entity_type + entity_id, no FK)
-- on purpose: the log must survive deletion of the row it describes.
-- =====================================================================

CREATE TABLE audit_logs (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    actor_user_id     BIGINT UNSIGNED NULL,
    actor_employee_id BIGINT UNSIGNED NULL,
    actor_username    VARCHAR(150)    NULL,
    action            VARCHAR(40)     NOT NULL,
    entity_type       VARCHAR(80)     NOT NULL,
    entity_id         VARCHAR(80)     NULL,
    old_value         JSON            NULL,
    new_value         JSON            NULL,
    description       VARCHAR(500)    NULL,
    ip_address        VARCHAR(45)     NULL,
    user_agent        VARCHAR(255)    NULL,
    trace_id          VARCHAR(64)     NULL,
    created_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_audit_entity (entity_type, entity_id),
    KEY idx_audit_actor (actor_user_id, created_at),
    KEY idx_audit_action (action, created_at),
    KEY idx_audit_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- Typed policy columns live in their own tables (Phase 4+). This is the
-- key/value long tail so HR can change behaviour without a deployment.
CREATE TABLE company_settings (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    setting_key   VARCHAR(120)    NOT NULL,
    setting_value VARCHAR(1000)   NULL,
    value_type    VARCHAR(20)     NOT NULL DEFAULT 'STRING',
    category      VARCHAR(60)     NOT NULL DEFAULT 'GENERAL',
    description   VARCHAR(500)    NULL,
    is_editable   BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by    BIGINT UNSIGNED NULL,
    updated_by    BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_company_settings_key (setting_key),
    KEY idx_company_settings_category (category)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
