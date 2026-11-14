-- =====================================================================
-- V26 : The date this system started recording attendance
--
-- Attendance is inferred, not only recorded: a working day with no punch,
-- no holiday and no weekly off is counted ABSENT. That is right once
-- people are using the application and wrong before they are.
--
-- Share India goes live on Monday 16 November 2026. Without this date,
-- every employee opening their November summary on day one would see
-- roughly ten absent days for the 1st to the 13th — days on which the
-- application did not exist for them — and an attendance percentage near
-- zero, because those days also sit in the denominator. A launch that
-- opens by telling forty-two people they have been absent all month is
-- not a launch anybody recovers from quickly.
--
-- The same date also protects the monthly grace allowance. Grace is
-- counted per calendar month, so any minutes spent before go-live — a
-- test punch, a trial run — would come out of the thirty minutes people
-- actually have from the 16th. Spending the buffer before anybody has
-- used the system is not a rule, it is an accident of timing.
--
-- Before this date: not absent, not in the attendance percentage, and no
-- grace consumed. After it: everything behaves normally.
--
-- Set it once and leave it. Moving it forward erases attendance that has
-- been recorded; moving it back resurrects absences for days nobody was
-- using the system.
-- =====================================================================

INSERT INTO company_settings (setting_key, setting_value, value_type, category, description)
SELECT 'attendance.tracking_start_date', '2026-11-16', 'STRING', 'ATTENDANCE',
       'Attendance is only inferred from this date on. Earlier working days are not counted absent, are excluded from the attendance percentage, and consume no monthly grace.'
 WHERE NOT EXISTS (
     SELECT 1 FROM company_settings WHERE setting_key = 'attendance.tracking_start_date'
 );

-- Any grace recorded before go-live was a test, not a late arrival.
UPDATE attendance
   SET grace_used_minutes = 0
 WHERE attendance_date < '2026-11-16'
   AND grace_used_minutes > 0;

SELECT setting_key, setting_value
  FROM company_settings
 WHERE setting_key IN ('attendance.tracking_start_date', 'attendance.monthly_grace_minutes');
