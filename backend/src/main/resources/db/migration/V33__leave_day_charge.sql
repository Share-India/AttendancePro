-- =====================================================================
-- V33 : What a leave day actually takes off the balance
--
-- V32 made a short Saturday worth half a day on the salary sheet. It
-- left a hole on the other side of the same transaction: applying for
-- leave on a Saturday still debited a whole day from the leave balance,
-- while the sheet credited only half of it back. The missing half came
-- out of the employee -- they spent a day of paid leave and were paid
-- for half.
--
-- The quantity a leave day charges used to be derivable: day_portion
-- said FULL_DAY or a half, and DayPortion.days() turned that into 1.0
-- or 0.5. It is not derivable any more, because it now depends on the
-- date as well -- a FULL_DAY on a working Saturday charges 0.5 -- and
-- on which rules were in force when the leave was granted.
--
-- WHY IT IS STORED RATHER THAN COMPUTED ON READ
--
-- Because the balance has already been debited. A leave approved last
-- month took a specific number of days out of a ledger, and that
-- number is now history: an audit row names it, and the employee has
-- seen the balance it left behind. Recomputing it on every read means
-- the figure moves whenever a setting changes -- so the day a Saturday
-- becomes worth half, every Saturday leave ever approved starts
-- reporting half, and no ledger anywhere agrees with the sum of the
-- days that produced it.
--
-- Same reasoning as grace_used_minutes in V25, and as V32's dated
-- rules: what was settled stays settled, and only new days see new
-- rules.
--
-- THE BACKFILL
--
-- Existing rows get what they were actually charged, with one
-- deliberate exception. A deductible FULL_DAY on a WORKING Saturday on
-- or after the V32 effective date is set to half, because the salary
-- sheet now credits that day as half and the two have to agree. On
-- production that is exactly one row -- one approved leave on Saturday
-- 28 November 2026 -- and it leaves that person's ledger 0.5 short of
-- what they are now owed. The ledger is not touched here: a balance
-- correction belongs in the application, where it is audited against
-- whoever made it, not buried in a migration. See the note printed at
-- the end.
--
-- A Saturday that is the employee's own weekly off is left alone.
-- Charged at all, it is charged by the sandwich rule as a bridged
-- non-working day, and a day somebody was not rostered to work is not
-- a short shift they can miss.
-- =====================================================================

-- Scale 2, not 1. Half a day of leave on a short Saturday is a quarter of
-- a day -- half of a half-length shift -- and DECIMAL(3,1) rounds 0.25 to
-- 0.3 without complaining about it. Nothing would have failed; the employee
-- would simply have been debited 0.05 of a day too much, for ever.
ALTER TABLE leave_request_days
    ADD COLUMN deductible_days DECIMAL(4,2) NOT NULL DEFAULT 0.00
        AFTER is_deductible;

-- 1. What each row was charged under the rules that were in force.
UPDATE leave_request_days
   SET deductible_days = CASE
           WHEN is_deductible = FALSE          THEN 0.00
           WHEN day_portion = 'FULL_DAY'       THEN 1.00
           ELSE 0.50
       END;

-- 2. The exception: a working Saturday under V32's rules is a half day.
--    DAYOFWEEK is 1-indexed from Sunday in MySQL, so Saturday is 7.
UPDATE leave_request_days d
   SET d.deductible_days = d.deductible_days * (
           SELECT CAST(s.setting_value AS DECIMAL(4,2))
             FROM company_settings s
            WHERE s.setting_key = 'payroll.saturday_day_value')
 WHERE d.is_deductible = TRUE
   AND d.day_type = 'WORKING'
   AND DAYOFWEEK(d.leave_date) = 7
   AND d.leave_date >= (
           SELECT CAST(s.setting_value AS DATE)
             FROM company_settings s
            WHERE s.setting_key = 'payroll.rules_effective_date');

-- Check after this runs. Every deductible row should carry a non-zero
-- charge, every non-deductible row zero, and the Saturday rows should
-- show which were halved.
SELECT d.leave_date,
       DAYNAME(d.leave_date)  AS falls_on,
       d.day_portion,
       d.day_type,
       d.is_deductible,
       d.deductible_days,
       r.request_number,
       r.status
  FROM leave_request_days d
  JOIN leave_requests r ON r.id = d.leave_request_id
 WHERE DAYOFWEEK(d.leave_date) = 7
 ORDER BY d.leave_date;

-- Anybody whose ledger now disagrees with the days their approved leave
-- actually charges. Each of these needs a balance adjustment made
-- through the application, with a reason, so the correction is audited
-- against a person rather than appearing out of nowhere.
SELECT e.employee_code,
       e.first_name,
       SUM(1.00 - d.deductible_days) AS owed_back_days
  FROM leave_request_days d
  JOIN leave_requests r ON r.id = d.leave_request_id
  JOIN employees      e ON e.id = d.employee_id
 WHERE r.status = 'APPROVED'
   AND d.is_deductible = TRUE
   AND d.day_portion = 'FULL_DAY'
   AND d.deductible_days < 1.00
 GROUP BY e.employee_code, e.first_name
 ORDER BY e.employee_code;
