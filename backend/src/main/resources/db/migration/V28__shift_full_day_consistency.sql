-- =====================================================================
-- V28 : A full day must be shorter than the shift it sits in
--
-- The Early and Late shifts were defined with full_day_minutes = 540 --
-- exactly the length of the shift itself (08:30-17:30 and 10:00-19:00).
-- A full day therefore required being present for every minute of the
-- span with no break at all. Any break, or leaving one minute early,
-- dropped the day to HALF_DAY and cost half a day's pay.
--
-- Lunch and personal breaks are deducted from working hours, so for the
-- eighteen people on those two shifts a full day was not merely hard,
-- it was unreachable: they could not eat and be present.
--
-- Every other nine-hour shift -- General, Morning, Evening, Night --
-- already used 480, leaving an hour inside the span for breaks. That is
-- the company's actual rule: nine hours present, eight hours worked.
-- Early and Late were the only two that disagreed, on all three
-- columns, and nothing but an oversight distinguishes them.
--
-- Written to be idempotent: the same values were applied directly to
-- production on 17 November 2026 to stop that evening's punch-outs
-- being settled wrongly, and this migration must land cleanly on a
-- database that already has them.
-- =====================================================================

UPDATE shifts
   SET standard_work_minutes = 480,
       full_day_minutes      = 480,
       half_day_minutes      = 240
 WHERE code IN ('EARLY', 'LATE');

-- ---------------------------------------------------------------------
-- The days already settled under the old definition.
--
-- A settled day keeps the status it was given; nothing recomputes it
-- later. Two days were marked HALF_DAY purely by this misconfiguration
-- -- 505 and 535 minutes worked, both comfortably past the 480 a full
-- day actually requires.
--
-- Scoped deliberately narrowly: only days on these two shifts, only
-- those marked HALF_DAY, and only where the hours clear the corrected
-- threshold. A genuine half day -- someone who worked four hours -- is
-- below 480 and is left exactly as it is.
-- ---------------------------------------------------------------------

UPDATE attendance a
  JOIN shifts s ON s.id = a.shift_id
   SET a.attendance_status = 'PRESENT',
       a.remarks = CONCAT(COALESCE(CONCAT(a.remarks, ' '), ''),
                          '[V28] Re-settled as a full day: the shift required ',
                          'the whole 9-hour span with no break.')
 WHERE s.code IN ('EARLY', 'LATE')
   AND a.attendance_status = 'HALF_DAY'
   AND a.working_minutes >= 480
   AND a.punch_out_time IS NOT NULL;

SELECT code, name, start_time, end_time,
       standard_work_minutes, full_day_minutes, half_day_minutes
  FROM shifts
 WHERE is_active = 1
 ORDER BY code;
