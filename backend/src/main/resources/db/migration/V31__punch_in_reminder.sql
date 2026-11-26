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
--   shift as they walked in.
--
--   Ninety minutes, so a 09:30 shift is reminded at 11:00. That is well
--   past anybody who is merely running late, which is the point: a
--   reminder that arrives while people are still filing in is noise, and
--   noise is what gets a sender filtered to a folder. At ninety minutes
--   the message is about a day that is genuinely unaccounted for, and
--   there is still most of a shift left in which to fix it.
-- =====================================================================

ALTER TABLE attendance_policies
    ADD COLUMN punch_in_reminder_enabled BOOLEAN NOT NULL DEFAULT FALSE
        AFTER auto_punch_out_enabled,
    ADD COLUMN punch_in_reminder_delay_minutes SMALLINT NOT NULL DEFAULT 90
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
