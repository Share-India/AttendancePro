-- =====================================================================
-- V10 : Holidays and weekly offs
--
-- Punch-in needs to know what kind of day it is before it can decide whether
-- a punch is allowed and what status the day should carry.
-- =====================================================================

CREATE TABLE holidays (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name         VARCHAR(150)    NOT NULL,
    holiday_date DATE            NOT NULL,
    holiday_type VARCHAR(30)     NOT NULL DEFAULT 'COMPANY',
    description  VARCHAR(500)    NULL,
    holiday_year SMALLINT        NOT NULL,
    is_active    BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by   BIGINT UNSIGNED NULL,
    updated_by   BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    KEY idx_holidays_date (holiday_date, is_active),
    KEY idx_holidays_year (holiday_year)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- A calendar per location: Mumbai and Pune share Independence Day but differ on
-- regional holidays, without duplicating the holiday rows themselves.
CREATE TABLE holiday_calendars (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    name         VARCHAR(120)    NOT NULL,
    calendar_year SMALLINT       NOT NULL,
    office_id    BIGINT UNSIGNED NULL,
    is_default   BOOLEAN         NOT NULL DEFAULT FALSE,
    is_active    BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by   BIGINT UNSIGNED NULL,
    updated_by   BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    KEY idx_holiday_calendars_office (office_id, calendar_year),
    CONSTRAINT fk_holiday_calendars_office FOREIGN KEY (office_id) REFERENCES offices (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE holiday_calendar_entries (
    holiday_calendar_id BIGINT UNSIGNED NOT NULL,
    holiday_id          BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (holiday_calendar_id, holiday_id),
    KEY idx_holiday_entries_holiday (holiday_id),
    CONSTRAINT fk_holiday_entries_calendar FOREIGN KEY (holiday_calendar_id) REFERENCES holiday_calendars (id) ON DELETE CASCADE,
    CONSTRAINT fk_holiday_entries_holiday FOREIGN KEY (holiday_id) REFERENCES holidays (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- week_of_month NULL means every week. Values like 2 and 4 express alternate
-- Saturdays, which is the common case this column exists for.
CREATE TABLE weekly_offs (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    scope_type     VARCHAR(30)     NOT NULL DEFAULT 'GLOBAL',
    scope_id       BIGINT UNSIGNED NULL,
    day_of_week    TINYINT         NOT NULL,
    week_of_month  TINYINT         NULL,
    is_half_day    BOOLEAN         NOT NULL DEFAULT FALSE,
    effective_from DATE            NULL,
    effective_to   DATE            NULL,
    is_active      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by     BIGINT UNSIGNED NULL,
    updated_by     BIGINT UNSIGNED NULL,
    PRIMARY KEY (id),
    KEY idx_weekly_offs_lookup (scope_type, scope_id, day_of_week, is_active),
    CONSTRAINT ck_weekly_offs_dow CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_weekly_offs_wom CHECK (week_of_month IS NULL OR week_of_month BETWEEN 1 AND 5)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- ISO day numbering, matching java.time.DayOfWeek: Saturday = 6, Sunday = 7.
INSERT INTO weekly_offs (scope_type, day_of_week, is_active) VALUES
  ('GLOBAL', 6, TRUE),
  ('GLOBAL', 7, TRUE);
