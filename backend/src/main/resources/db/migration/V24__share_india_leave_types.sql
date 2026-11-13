-- =====================================================================
-- V24 : Share India's leave types — Paid, Maternity, Paternity
--
-- V6 seeded a generic Indian set: Casual, Sick, Earned, Leave Without
-- Pay, Comp Off, Maternity and Paternity. Share India runs three of
-- those ideas as one: a single Paid Leave accrued monthly, plus the two
-- statutory types.
--
-- The five that go are DEACTIVATED, not deleted. leave_requests,
-- leave_balances and the audit log all reference leave_types by id, and
-- a deleted row turns every historical request into an unresolvable
-- foreign key. Nothing has been requested yet in production, so a delete
-- would work today — and would stop working the first time somebody
-- applies for leave, which is the worst moment to discover it.
--
-- PL's monthly rate is 1 or 2 depending on probation, and accrual_days
-- cannot express that. The column carries 2.00, the post-probation rate,
-- so a reader is not misled about the usual case; the authoritative
-- figure is computed in PaidLeaveAccrualService, which also applies the
-- joining-day rule. The column is informational for this type.
--
-- counts_holidays_weekly_offs stays FALSE deliberately. The sandwich
-- rule is conditional — an intervening holiday counts only when there is
-- leave on the working day either side of it — and this flag is
-- unconditional. Setting it here would charge somebody for the weekend
-- after a single Friday off.
-- =====================================================================

UPDATE leave_types
   SET is_active = FALSE
 WHERE code IN ('CL', 'SL', 'EL', 'LWP', 'COMP');

-- The policies behind them go too, or a deactivated type still resolves a
-- quota if it is ever reactivated by hand.
UPDATE leave_policies
   SET is_active = FALSE
 WHERE leave_type_id IN (SELECT id FROM leave_types WHERE code IN ('CL', 'SL', 'EL', 'LWP', 'COMP'));

INSERT INTO leave_types
  (code, name, description, is_paid, allow_half_day, requires_attachment,
   attachment_required_after_days, min_notice_days, exempt_from_notice, allow_backdated,
   applicable_gender, counts_holidays_weekly_offs, accrual_type, accrual_days,
   allow_carry_forward, max_carry_forward_days, hr_approval_threshold_days, display_order)
SELECT 'PL', 'Paid Leave',
       'Accrued monthly: 1 day a month during probation, 2 a month after. Unused days carry month to month, and up to 10 carry into the next calendar year.',
       TRUE,  -- paid
       TRUE,  -- half days: the November opening balances carry .5 values
       FALSE, NULL,
       1,     -- a day's notice; HR can waive it on a request
       FALSE, FALSE,
       'ALL',
       FALSE, -- see the note above on the sandwich rule
       'MONTHLY', 2.00,
       TRUE,  10.00,
       5,     -- HR approves beyond five consecutive days
       1
 WHERE NOT EXISTS (SELECT 1 FROM leave_types WHERE code = 'PL');

-- A policy row is still required: LeaveBalanceService resolves one before it
-- will open a balance, and returns a zero entitlement when none matches.
--
-- annual_quota is 0 and pro_rata_on_joining FALSE on purpose. PL does not
-- have an annual quota to pro-rate — the entitlement is whatever has accrued
-- month by month, which the accrual service computes and writes to
-- credited_days. A non-zero quota here would be added to that and hand
-- everybody a second entitlement on top.
INSERT INTO leave_policies (leave_type_id, scope_type, annual_quota, pro_rata_on_joining)
SELECT lt.id, 'GLOBAL', 0, FALSE
  FROM leave_types lt
 WHERE lt.code = 'PL'
   AND NOT EXISTS (
       SELECT 1 FROM leave_policies p
        WHERE p.leave_type_id = lt.id AND p.scope_type = 'GLOBAL'
   );

-- The date this system took over Paid Leave from the spreadsheet.
--
-- Balances were kept by hand until October 2026, and the figures loaded into
-- leave_balances.carried_forward_days are the NET position at 1 November —
-- they already contain this year's accrual and this year's usage.
--
-- Without this date the accrual service would start from the leave-year
-- opening and credit January to October all over again, on top of a figure
-- that already includes them. Somebody on two a month would be handed twenty
-- days they have not earned, and the error would look like generosity rather
-- than a bug.
--
-- It is a handover marker, not a policy: accrual starts at whichever is later,
-- this date or the employee's joining date. Leave it alone once set. Moving it
-- forward silently stops accrual; moving it back re-credits months the opening
-- figures already cover.
INSERT INTO company_settings (setting_key, setting_value, value_type, category, description)
SELECT 'leave.accrual_start_date', '2026-11-01', 'STRING', 'LEAVE',
       'Paid Leave accrues from this date onward. Balances before it were kept by hand and are loaded as carried-forward days.'
 WHERE NOT EXISTS (
     SELECT 1 FROM company_settings WHERE setting_key = 'leave.accrual_start_date'
 );

-- Check after this runs. Expect exactly PL, MAT and PAT.
SELECT code, name, is_active, accrual_type, accrual_days,
       allow_carry_forward, max_carry_forward_days
  FROM leave_types
 ORDER BY is_active DESC, display_order;
