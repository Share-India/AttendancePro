-- =====================================================================
-- V30 : A day short of the mark is not half a day
--
-- Until now a day had three outcomes once somebody had worked a
-- reasonable stretch: full, half, or not enough. Half was everything
-- from four hours to one minute short of eight -- so arriving twenty
-- minutes late and leaving on time cost half a day's pay, the same as
-- leaving at lunchtime.
--
-- SHORT_DAY sits between them. At or above short_day_minutes but below
-- full_day_minutes, the day is paid in full and marked short, so a
-- manager can see it and have a conversation about it. Below that it is
-- still a half day, as before.
--
-- The window is one hour: 420 minutes against a 480-minute full day.
-- Beyond an hour short, the day really has not been worked.
--
-- Saturday keeps proportion rather than the same absolute window: its
-- full day is 220 minutes, so 160 leaves the same one-hour margin.
-- =====================================================================

ALTER TABLE shifts
    ADD COLUMN short_day_minutes SMALLINT NOT NULL DEFAULT 420 AFTER full_day_minutes;

ALTER TABLE attendance_policies
    ADD COLUMN short_day_minutes SMALLINT NOT NULL DEFAULT 420 AFTER full_day_minutes;

-- One hour below whatever that shift calls a full day, so a shift with
-- a different length keeps the same margin rather than inheriting a
-- number that makes no sense for it.
UPDATE shifts
   SET short_day_minutes = GREATEST(half_day_minutes, full_day_minutes - 60);

UPDATE attendance_policies
   SET short_day_minutes = GREATEST(half_day_minutes, full_day_minutes - 60);

-- The ordering the bands depend on. Without these a careless edit could
-- put the short-day line above a full day, where it would never be
-- reached, or below the half-day line, where it would swallow it.
ALTER TABLE shifts
    ADD CONSTRAINT ck_shifts_short_day
    CHECK (short_day_minutes <= full_day_minutes AND short_day_minutes >= half_day_minutes);

ALTER TABLE attendance_policies
    ADD CONSTRAINT ck_attendance_policy_short_day
    CHECK (short_day_minutes <= full_day_minutes AND short_day_minutes >= half_day_minutes);

SELECT code, full_day_minutes, short_day_minutes, half_day_minutes, min_attendance_minutes
  FROM shifts
 WHERE is_active = TRUE
 ORDER BY code;
