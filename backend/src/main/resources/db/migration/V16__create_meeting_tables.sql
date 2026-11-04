-- =====================================================================
-- V16 : Morning meetings
--
-- A meeting held on an external platform — Google Meet, Zoom, Teams,
-- whatever the host pastes in. The application cannot see inside that
-- call, so what it records is deliberately narrow and honest:
--
--   measured : who opened our Join link, and when
--   measured : when the host started and ended the meeting
--   derived  : how long someone was present (end minus their join time)
--
-- Nothing here should ever be presented as proof that a person sat
-- through a call. It is proof that they opened the link.
--
-- Two shapes are worth reading before changing them:
--
--  1. meeting_join_events is append-only evidence; the summary columns on
--     meeting_participants are derived from it. Same relationship as
--     attendance_punch_events to attendance. If the join logic turns out
--     to be wrong, the roster can be rebuilt from the events.
--
--  2. meeting_participants is snapshotted when the meeting is published,
--     not computed on read. Someone hired on Wednesday must not appear in
--     Monday's absentee list, and last week's attendance rate must not
--     move every time the org chart does.
-- =====================================================================

CREATE TABLE meetings (
    id                      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    meeting_code            VARCHAR(30)     NOT NULL,
    title                   VARCHAR(200)    NOT NULL,
    agenda                  TEXT            NULL,

    -- Operator-supplied and redirected to, so it is validated on the way in:
    -- https only, parseable host, no embedded credentials.
    meeting_url             VARCHAR(1000)   NOT NULL,
    -- Derived from the URL host at save time, for display and grouping only.
    provider                VARCHAR(20)     NOT NULL DEFAULT 'OTHER',

    host_employee_id        BIGINT UNSIGNED NOT NULL,

    -- Stored UTC. Entered and displayed in the office zone — a 09:30 meeting
    -- is 09:30 where the people are, and the JVM runs on UTC.
    scheduled_start_at      TIMESTAMP       NOT NULL,
    scheduled_end_at        TIMESTAMP       NOT NULL,
    actual_started_at       TIMESTAMP       NULL,
    actual_ended_at         TIMESTAMP       NULL,

    status                  VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',

    -- Null means "use the company default". Lateness is policy, not a constant.
    late_after_minutes      SMALLINT        NULL,

    -- Set once the roster has terminal statuses. Guards re-finalisation.
    finalised_at            TIMESTAMP       NULL,
    cancellation_reason     VARCHAR(500)    NULL,

    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by              BIGINT UNSIGNED NULL,
    updated_by              BIGINT UNSIGNED NULL,
    version                 INT             NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_meeting_code (meeting_code),
    KEY idx_meeting_window (status, scheduled_start_at),
    KEY idx_meeting_host (host_employee_id, scheduled_start_at),
    CONSTRAINT fk_meeting_host FOREIGN KEY (host_employee_id) REFERENCES employees (id) ON DELETE RESTRICT,
    CONSTRAINT ck_meeting_window CHECK (scheduled_end_at > scheduled_start_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- Who is expected, expressed as scopes. Read once, at publish, to build the
-- roster; never consulted again, so a department reorganisation afterwards
-- cannot change who was supposed to be at a meeting that already happened.
CREATE TABLE meeting_audience (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    meeting_id  BIGINT UNSIGNED NOT NULL,
    scope_type  VARCHAR(20)     NOT NULL,
    scope_id    BIGINT UNSIGNED NULL,

    created_at  TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_meeting_audience (meeting_id, scope_type, scope_id),
    CONSTRAINT fk_meeting_audience_meeting FOREIGN KEY (meeting_id) REFERENCES meetings (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- The roster and the outcome in one row: attendance is one-to-one with being
-- invited, so a separate attendance table would only ever be a parallel key.
CREATE TABLE meeting_participants (
    id                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    meeting_id                BIGINT UNSIGNED NOT NULL,
    employee_id               BIGINT UNSIGNED NOT NULL,

    -- First click only. A reconnect must never make someone look later than
    -- they actually arrived.
    first_joined_at           TIMESTAMP       NULL,
    last_clicked_at           TIMESTAMP       NULL,
    click_count               INT             NOT NULL DEFAULT 0,

    -- Reserved. Click-through cannot tell us when somebody left; this stays
    -- null until a source that knows (a provider export) is added. Present
    -- now so adding that source later is not a migration.
    left_at                   TIMESTAMP       NULL,

    -- end-of-meeting minus join time. An estimate, and labelled as one
    -- everywhere it is shown.
    attended_minutes_estimate INT             NULL,

    status                    VARCHAR(20)     NOT NULL DEFAULT 'INVITED',
    -- LINK_CLICK today. HOST_MARKED and PROVIDER_IMPORT are the upgrade paths.
    attendance_source         VARCHAR(20)     NULL,
    excuse_reason             VARCHAR(100)    NULL,

    created_at                TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                BIGINT UNSIGNED NULL,
    updated_by                BIGINT UNSIGNED NULL,
    version                   INT             NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_meeting_participant (meeting_id, employee_id),
    KEY idx_meeting_participant_employee (employee_id, status),
    KEY idx_meeting_participant_status (meeting_id, status),
    CONSTRAINT fk_meeting_participant_meeting FOREIGN KEY (meeting_id) REFERENCES meetings (id) ON DELETE CASCADE,
    CONSTRAINT fk_meeting_participant_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- Every click, append-only. No unique constraint: a second click is a fact,
-- not a duplicate. This is what the summary above is derived from.
CREATE TABLE meeting_join_events (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    meeting_id   BIGINT UNSIGNED NOT NULL,
    employee_id  BIGINT UNSIGNED NOT NULL,
    clicked_at   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address   VARCHAR(45)     NULL,
    user_agent   VARCHAR(300)    NULL,

    PRIMARY KEY (id),
    KEY idx_meeting_join_event (meeting_id, employee_id, clicked_at),
    CONSTRAINT fk_meeting_join_event_meeting FOREIGN KEY (meeting_id) REFERENCES meetings (id) ON DELETE CASCADE,
    CONSTRAINT fk_meeting_join_event_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- Lateness and the auto-close grace period are policy, not constants.
INSERT INTO company_settings (setting_key, setting_value, value_type, category, description) VALUES
  ('meeting.late_after_minutes',      '5',  'INT', 'MEETING',
   'Minutes after the scheduled start beyond which a join is recorded as late'),
  ('meeting.join_grace_minutes',      '15', 'INT', 'MEETING',
   'Minutes either side of the scheduled window during which the join link still works'),
  ('meeting.auto_close_grace_minutes','30', 'INT', 'MEETING',
   'Minutes after the scheduled end before an unclosed meeting finalises itself on next read');
