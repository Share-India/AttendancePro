-- =====================================================================
-- V27 : Share India's break allowances
--
-- The figures seeded in V11 were the application's defaults, not this
-- company's rules. Share India allows:
--
--     Lunch Break      30 minutes  (was 45)
--     Tea Break        15 minutes  (unchanged)
--     Personal Break   15 minutes  (was 30)
--
-- Only the ceiling changes. Whether a break is paid, and whether it is
-- deducted from the day's working hours, is left exactly as it was:
-- lunch and personal breaks come out of the working day, tea does not.
--
-- The cap is read when a break is started and when it is settled, so
-- this takes effect on the next break anybody takes. Breaks already
-- recorded keep the minutes they actually ran for -- nobody is
-- retrospectively marked as having overrun a limit that did not exist
-- when they took the break.
-- =====================================================================

UPDATE break_types SET max_minutes = 30 WHERE code = 'LUNCH';
UPDATE break_types SET max_minutes = 15 WHERE code = 'PERSONAL';
UPDATE break_types SET max_minutes = 15 WHERE code = 'TEA';

SELECT code, name, max_minutes, is_paid, is_deducted_from_working_hours
  FROM break_types
 ORDER BY display_order;
