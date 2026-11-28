-- =====================================================================
-- V35 : Let somebody apply for the leave they already took
--
-- V32 made an absence nobody applied for a loss of pay, and the salary
-- sheet says so in as many words: "Applying for the leave and having it
-- approved converts this back." It could not be done. Paid Leave --
-- the only everyday leave type that is active -- had allow_backdated
-- false, so the date picker greyed out every day up to today and the
-- API refused the request behind it. The rule named a remedy that did
-- not exist.
--
-- THE FLAG ALONE WAS NOT ENOUGH
--
-- Two gates stood in the way and only one of them was obvious.
-- validateDates checks allow_backdated, and then separately checks
-- notice:
--
--     long notice = ChronoUnit.DAYS.between(today, request.fromDate());
--     if (notice < leaveType.getMinNoticeDays()) ...refuse
--
-- For a date in the past that subtraction is negative, so any type with
-- min_notice_days above zero fails it however allow_backdated is set.
-- Paid Leave asks for one day's notice. Turning the flag on by itself
-- would have changed the refusal from "cannot be applied for
-- retrospectively" to "needs 1 day(s) notice; this request gives -3",
-- which is worse: it reads like a bug, and the employee cannot act on
-- it. The service now skips the notice check for a backdated request,
-- which is the only reading that makes sense -- notice is a thing you
-- give before a day, and there is no giving notice for last Tuesday.
--
-- Sick Leave is already configured for exactly this (allow_backdated
-- true, exempt_from_notice true) and is switched off (is_active false).
-- Whether to turn it on is a business decision and is not taken here.
--
-- HOW FAR BACK
--
-- The current month, and the previous month only while it is still
-- open. The salary sheet recomputes past months every time it runs --
-- absence is inferred, never stored -- so an approved backdated leave
-- rewrites the payable figure for whatever month it lands in. Allowing
-- April in November would change a sheet that was paid in May.
--
-- "Still open" needs a date, because nothing in this system knows when
-- payroll runs. The grace below is that date: for this many days into
-- a new month, the one before it can still be applied against. Five
-- covers a payroll run in the first week and closes well before the
-- next one.
-- =====================================================================

INSERT INTO company_settings (setting_key, setting_value, value_type, category, description)
SELECT 'payroll.backdated_leave_grace_days', '5', 'INT', 'PAYROLL',
       'How many days into a new month the previous month still accepts backdated leave. Past this, the earliest date anybody can apply for is the 1st of the current month, so a month payroll has already run cannot be changed underneath it. 0 means the current month only, from its 1st.'
 WHERE NOT EXISTS (
     SELECT 1 FROM company_settings WHERE setting_key = 'payroll.backdated_leave_grace_days'
 );

-- The only everyday leave type that is active, and the one the rule
-- about unapplied absence actually points at.
UPDATE leave_types
   SET allow_backdated = TRUE
 WHERE code = 'PL';

-- Check after this runs: PL backdatable, and the grace set to 5.
SELECT code, name, min_notice_days, exempt_from_notice, allow_backdated, is_active
  FROM leave_types
 ORDER BY is_active DESC, code;

SELECT setting_key, setting_value
  FROM company_settings
 WHERE setting_key LIKE 'payroll.%'
 ORDER BY setting_key;
