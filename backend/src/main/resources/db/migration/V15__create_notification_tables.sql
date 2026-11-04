-- =====================================================================
-- V15 : In-app notifications
--
-- A notification is addressed to a user, not an employee: it is something a
-- person sees when they sign in, and a system account with no employee record
-- can still be told that a nightly job failed.
--
-- Deliberately denormalised. The title and body are written at the moment the
-- event happens and never regenerated, so a message that said "Priya Sharma
-- approved your leave" still says that after Priya leaves the company and her
-- record is deactivated. Re-deriving the text later would quietly rewrite
-- history every time the org chart changed.
-- =====================================================================

CREATE TABLE notifications (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,

    -- Who sees it.
    recipient_user_id BIGINT UNSIGNED NOT NULL,

    -- LEAVE_APPLIED, LEAVE_APPROVED, REGULARIZATION_APPLIED, ... See the
    -- NotificationType enum; stored as a string so the log stays readable in
    -- a SQL client years from now.
    type              VARCHAR(50)     NOT NULL,
    category          VARCHAR(30)     NOT NULL,

    title             VARCHAR(200)    NOT NULL,
    body             VARCHAR(1000)   NULL,

    -- Where clicking it should go, as an app-relative path. Stored rather than
    -- derived so an old notification keeps working if routes are reorganised.
    link              VARCHAR(300)    NULL,

    -- What it is about, for de-duplication and for "open the thing" links.
    entity_type       VARCHAR(50)     NULL,
    entity_id         VARCHAR(50)     NULL,

    -- Who caused it. Nullable: a scheduled job has no actor.
    actor_user_id     BIGINT UNSIGNED NULL,
    actor_name        VARCHAR(150)    NULL,

    is_read           BOOLEAN         NOT NULL DEFAULT FALSE,
    read_at           TIMESTAMP       NULL,

    created_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_notification_recipient FOREIGN KEY (recipient_user_id)
        REFERENCES users (id) ON DELETE CASCADE,

    -- The only query on the hot path: this user's notifications, newest first,
    -- and the unread count. Both are served by this one composite index.
    INDEX idx_notification_recipient (recipient_user_id, is_read, created_at DESC),
    INDEX idx_notification_entity (entity_type, entity_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;


-- ---------------------------------------------------------------------
-- Notification preferences are a per-user opt-out, absent by default.
--
-- A row exists only once someone has actually changed something, so "no row"
-- means "everything on" and a new notification type does not need a backfill
-- before anyone can receive it.
-- ---------------------------------------------------------------------
CREATE TABLE notification_preferences (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id      BIGINT UNSIGNED NOT NULL,
    category     VARCHAR(30)     NOT NULL,
    in_app       BOOLEAN         NOT NULL DEFAULT TRUE,
    -- Email delivery is not wired up yet; the column exists so turning it on
    -- later is a service change rather than another migration.
    email        BOOLEAN         NOT NULL DEFAULT FALSE,

    created_at   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    CONSTRAINT fk_notification_pref_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT uk_notification_pref UNIQUE (user_id, category)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
