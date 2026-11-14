-- =====================================================================
-- V25 : A monthly grace allowance, replacing the daily one
--
-- Until now grace was per day: 15 minutes on every shift, every day,
-- with nothing counting the total. Somebody could arrive 15 minutes late
-- on all 22 working days of a month — five and a half hours — and never
-- be recorded late once.
--
-- Share India's rule is a monthly budget instead: 30 minutes for the
-- whole month, shared across every late arrival. Three ten-minute
-- arrivals spend it; the fourth is recorded late. Only the excess is
-- recorded — with 5 minutes left and a 12-minute arrival, the day shows
-- 7 late, not 12.
--
-- WHY BOTH GRACE COLUMNS ARE ZEROED
--
-- AttendanceCalculationService reads the shift's grace, and falls back
-- to the policy's when the shift's is zero:
--
--     shift.getGracePeriodMinutes() > 0 ? shift... : policy...
--
-- So on that code path zero does not mean "no grace", it means "ask the
-- policy". Zeroing only the shifts would silently hand everybody the
-- policy's 15 minutes a day back, on top of the new monthly pool, and
-- the daily allowance this migration is meant to remove would still be
-- there. Both are set to zero so late minutes are measured from the
-- shift start and nothing else.
--
-- Early-exit grace is deliberately untouched. The rule is about arriving
-- late; leaving early is a different conversation and nobody asked for
-- it to change.
-- =====================================================================

-- What each day drew from the month's pool. Stored rather than derived
-- because the running total is a SUM over the month, and recomputing
-- every earlier day on every punch would turn one settlement into
-- twenty-two.
ALTER TABLE attendance
    ADD COLUMN grace_used_minutes INT NOT NULL DEFAULT 0 AFTER late_minutes;

UPDATE shifts SET grace_period_minutes = 0;

UPDATE attendance_policies SET grace_period_minutes = 0;

INSERT INTO company_settings (setting_key, setting_value, value_type, category, description)
SELECT 'attendance.monthly_grace_minutes', '30', 'INT', 'ATTENDANCE',
       'Minutes of lateness forgiven per employee per calendar month, shared across all late arrivals. Replaces the per-day grace period.'
 WHERE NOT EXISTS (
     SELECT 1 FROM company_settings WHERE setting_key = 'attendance.monthly_grace_minutes'
 );

-- Check after this runs. Every shift and the policy should read 0, and
-- the setting should be 30.
SELECT 'shifts' AS source, code, grace_period_minutes, early_exit_grace_minutes
  FROM shifts WHERE is_active
UNION ALL
SELECT 'policy', code, grace_period_minutes, early_exit_grace_minutes
  FROM attendance_policies;

SELECT setting_key, setting_value
  FROM company_settings
 WHERE setting_key = 'attendance.monthly_grace_minutes';
