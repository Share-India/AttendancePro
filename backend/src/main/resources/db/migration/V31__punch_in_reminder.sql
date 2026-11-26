-- =====================================================================
-- V31 : Remind the people who have not punched in
--
-- The Team screen already shows, every morning, who has not punched in
-- and is not on leave, on a holiday or on a weekly off. Nothing told
-- those people. They found out when the day was settled against them.
--
-- Two settings, because the useful ones are not the same at every
-- company and neither should need a database session to change:
--
--   enabled -- off here, deliberately. A switch that mails the whole
--   company the moment a migration runs is not a switch anybody wants
--   discovered in production. HR turns it on when they are ready.
--
--   delay -- how long after a person's own shift starts the reminder
--   goes. Shift starts here span 08:30 to 10:00, so a single clock time
--   would chase the early shift ninety minutes late and greet the late
--   shift as they walked in. Thirty minutes is twice the fifteen-minute
--   grace, so nobody is chased for arriving inside it.
-- =====================================================================

ALTER TABLE attendance_policies
    ADD COLUMN punch_in_reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE
        AFTER auto_punch_out_enabled,
    ADD COLUMN punch_in_reminder_delay_minutes SMALLINT NOT NULL DEFAULT 30
        AFTER punch_in_reminder_enabled;

-- A reminder before the shift has started is a reminder to somebody who
-- is not late, and a negative delay would send it before the day began.
ALTER TABLE attendance_policies
    ADD CONSTRAINT ck_attendance_policy_reminder_delay
    CHECK (punch_in_reminder_delay_minutes >= 0
       AND punch_in_reminder_delay_minutes <= 480);

SELECT code,
       punch_in_reminder_enabled,
       punch_in_reminder_delay_minutes
  FROM attendance_policies;
