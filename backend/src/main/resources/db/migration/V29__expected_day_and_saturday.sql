-- =====================================================================
-- V29 : The expected day is nine hours; a full day is eight
--
-- Two different numbers that had been treated as one.
--
--   standard_work_minutes  what the employee is expected to be at work
--                          for. This is the "Expected" figure on the
--                          punch screen, and the line above which
--                          overtime begins.
--
--   full_day_minutes       the net worked minutes that count as a full
--                          day, after deductible breaks are taken off.
--
-- They cannot be the same number, because work is measured net of
-- lunch. Somebody present for the whole nine hours with a 45-minute
-- lunch and personal break records 495 minutes worked. Setting both to
-- 540 is what made a full day unreachable in V28; setting both to 480
-- made the punch screen promise an eight-hour day, which is not the
-- company's day.
--
-- So: nine hours expected, eight hours to clear a full day. Every
-- full-length shift now reads the same, Night included -- its span
-- crosses midnight, so it is named rather than measured.
--
-- This also corrects overtime. At 480, anybody who took a short lunch
-- earned a quarter of an hour of overtime for eating quickly. At 540,
-- overtime means staying late, which is what it is for.
--
-- Saturday is the same mistake in miniature: a 270-minute shift with a
-- 270-minute full day. Share India works Saturdays without a break, so
-- the margin is small by design -- 220 minutes, agreed with HR, leaves
-- room for a late arrival or an early finish without costing half a
-- day. Its expected figure stays at the full 270, because that is how
-- long the shift is.
--
-- Idempotent: these values were applied to production on
-- 18 November 2026 and this must land cleanly on a database that
-- already holds them.
-- =====================================================================

UPDATE shifts
   SET standard_work_minutes = 540
 WHERE is_active = TRUE
   AND (code = 'NIGHT' OR TIMESTAMPDIFF(MINUTE, start_time, end_time) = 540);

UPDATE shifts
   SET full_day_minutes = 220
 WHERE code = 'SAT-HALF';

SELECT code, start_time, end_time,
       standard_work_minutes, full_day_minutes, half_day_minutes
  FROM shifts
 WHERE is_active = TRUE
 ORDER BY code;
