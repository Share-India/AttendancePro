-- =====================================================================
-- V32 : Late marks cost a day, and an absence nobody applied for is
--       unpaid
--
-- Four rules, agreed 28 November 2026. Three of them are new behaviour;
-- the first was already built and is only now being shown.
--
--   1. THE BUFFER. 30 minutes a month, shared across every late
--      arrival, already enforced by MonthlyGraceService since V25.
--      Unchanged here. What changes is that the salary sheet now shows
--      it: how many minutes a day drew from the pool, and how many
--      were recorded late once it ran dry.
--
--   2. A LATE MARK COSTS. Any day with recorded late minutes -- that
--      is, lateness the month's buffer could no longer absorb -- is a
--      late mark. Every second late mark in the month is one day's
--      loss of pay.
--
--      late_marks_for_half_day is dropped for it. That column has been
--      in this table since V9, has been editable on the Attendance
--      Rules screen the whole time, and was read by no calculation
--      anywhere: a knob wired to nothing. HR could set it to 3 and
--      watch three late marks cost nobody anything. Replacing it with
--      two columns that are actually read is the point of the change,
--      so leaving the old one in place "for compatibility" would
--      preserve exactly the thing that was wrong.
--
--   3. AN ABSENCE IS UNPAID UNLESS SOMEBODY APPLIED FOR IT. Until now
--      the sheet charged every absence to the paid-leave balance by
--      itself, which is what made payroll's payable figure come to a
--      full month for somebody who simply did not turn up. From the
--      effective date below it does not: an absence with no approved
--      leave against it is loss of pay, and the balance is untouched.
--      Applying for the leave and having it approved is what converts
--      it back, which is the behaviour the rule exists to encourage.
--
--   4. A SHORT SATURDAY IS WORTH HALF A DAY. 10:00 to 14:30 is half a
--      working day and is now paid as half a working day: 0.5 owed,
--      0.5 paid when worked, 0.5 unpaid when missed, 0.5 charged when
--      covered by approved leave.
--
-- WHY AN EFFECTIVE DATE RATHER THAN A SWITCH
--
-- The sheet computes its own opening balance by replaying every month
-- of the leave year before it -- absence is inferred, never stored, so
-- there is nothing to look up. Rule 3 applied to that replay would
-- refund every absence since April to everybody's balance, for months
-- that have already been paid under the old rule. So the rules are
-- dated instead of switched: a day before the effective date is
-- settled the way it was settled at the time, and only days from the
-- effective date onward see the new rules. Re-running October's sheet
-- next year still reproduces October.
--
-- The date is the 1st of November 2026, which is this month: the
-- decision was to apply the rules to the month now closing rather than
-- to start them in December. November is also the month this system
-- went live -- it began recording on the 16th -- so there is no
-- part-month to split: every day the sheet counts for November is a
-- day under the new rules.
--
-- WHAT THIS BREAKS ON PURPOSE
--
-- The salary sheet was reconciled line for line against payroll's own
-- October sheet, and rule 4 deliberately ends that agreement. Their
-- sheet pays a worked Saturday in full; this one pays half. On an
-- October-shaped month with three worked Saturdays the two sheets now
-- differ by 1.5 days per person, and the Saturday half days column
-- exists so that the difference reads as a rule rather than an error.
-- =====================================================================

-- --------------------------------------------------------------------
-- Rule 2: the late-mark penalty
-- --------------------------------------------------------------------

ALTER TABLE attendance_policies
    ADD COLUMN late_marks_per_penalty SMALLINT NOT NULL DEFAULT 2
        AFTER late_marks_for_half_day;

-- Decimal, not an integer count of half days: 1.0 and 0.5 are both
-- things a company asks for, and a column that can only hold whole
-- days cannot express the second without a second column to qualify it.
ALTER TABLE attendance_policies
    ADD COLUMN late_mark_penalty_days DECIMAL(3,1) NOT NULL DEFAULT 1.0
        AFTER late_marks_per_penalty;

-- Share India's rule, stated explicitly rather than left to the
-- defaults, so the row says what was agreed.
UPDATE attendance_policies
   SET late_marks_per_penalty = 2,
       late_mark_penalty_days = 1.0;

ALTER TABLE attendance_policies
    DROP COLUMN late_marks_for_half_day;

-- --------------------------------------------------------------------
-- Rules 3 and 4, and the date they start from
-- --------------------------------------------------------------------

INSERT INTO company_settings (setting_key, setting_value, value_type, category, description)
SELECT 'payroll.rules_effective_date', '2026-11-01', 'STRING', 'PAYROLL',
       'The first business date settled under the rules introduced in V32: an unapplied absence is loss of pay rather than a charge to the leave balance, a short Saturday is worth half a day, and every second late mark in a month costs a day. Days before this date keep the rules they were settled under, so earlier months reproduce. Blank disables all three.'
 WHERE NOT EXISTS (
     SELECT 1 FROM company_settings WHERE setting_key = 'payroll.rules_effective_date'
 );

INSERT INTO company_settings (setting_key, setting_value, value_type, category, description)
SELECT 'payroll.saturday_day_value', '0.5', 'DECIMAL', 'PAYROLL',
       'What one rostered short Saturday (10:00-14:30) is worth on the salary sheet: owed, paid when worked, charged when covered by leave, and unpaid when missed. 1.0 pays a worked Saturday as a full day, which is how the sheet behaved before V32.'
 WHERE NOT EXISTS (
     SELECT 1 FROM company_settings WHERE setting_key = 'payroll.saturday_day_value'
 );

-- Check after this runs: the policy should read 2 late marks to 1.0
-- days, the two settings should be 2026-11-01 and 0.5, and
-- late_marks_for_half_day should be gone.
SELECT code, late_marks_per_penalty, late_mark_penalty_days, late_mark_reset_period
  FROM attendance_policies;

SELECT setting_key, setting_value
  FROM company_settings
 WHERE setting_key IN ('payroll.rules_effective_date', 'payroll.saturday_day_value',
                       'attendance.monthly_grace_minutes')
 ORDER BY setting_key;
