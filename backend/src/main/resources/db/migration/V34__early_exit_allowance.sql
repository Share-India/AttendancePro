-- =====================================================================
-- V34 : Two early departures a month, not one every evening
--
-- Early-exit grace had the same shape lateness had before V25: fifteen
-- minutes every single day, with nothing counting the total. Somebody
-- could leave a quarter of an hour early on every working day of the
-- month -- five and a half hours -- and the system would record not one
-- minute of it. Nobody was hiding anything; the figure simply did not
-- exist to be looked at.
--
-- From here the fifteen minutes is an allowance that runs out: two
-- departures a month are forgiven, and the third is recorded in full.
--
-- WHY THIS IS NOT A MINUTES POOL LIKE V25's
--
-- Lateness got a budget of minutes because arriving is a moment -- five
-- minutes late and twelve minutes late differ by seven minutes and the
-- pool can hold the difference. Leaving early is an occasion: what the
-- rule is about is how often somebody goes home before the shift ends,
-- not the total time. "Two early exits a month" is what was asked for
-- and what a manager will check, so the allowance counts occasions.
--
-- Each still forgives up to early_exit_grace_minutes, which stays at 15
-- and stays per-occasion. Leaving 40 minutes early uses one of the two
-- and still records 25.
--
-- WHAT THIS DOES NOT DO
--
-- It costs nobody anything. early_exit_minutes is read by the
-- attendance screen, the dashboard's count and the Early Exits report,
-- and by no pay calculation anywhere -- that was true before this
-- migration and is still true after it. The change makes repeat early
-- leaving visible to a manager. Whether it should also cost something
-- is a separate decision, deliberately not taken here.
--
-- On production this is a small number: of November's tracked fortnight,
-- 12 departures across 7 people were forgiven by the daily grace, and
-- only 2 people used it more than twice. The hole was worth closing
-- before it was worth using, not after.
-- =====================================================================

-- What this day drew from the month's allowance, in minutes. Stored for
-- the same reason grace_used_minutes is: the count is a query over the
-- month, and re-settling a day has to be able to exclude that day's own
-- earlier spend rather than charging the month twice for it.
--
-- Minutes rather than a flag, so the sheet can say how much was
-- forgiven and not merely that something was.
ALTER TABLE attendance
    ADD COLUMN early_exit_grace_used_minutes INT NOT NULL DEFAULT 0
        AFTER early_exit_minutes;

-- How many of those a month gets. Zero forgives nothing and records
-- every early departure; a high number is the old per-day behaviour.
ALTER TABLE attendance_policies
    ADD COLUMN early_exits_allowed_per_month SMALLINT NOT NULL DEFAULT 2
        AFTER early_exit_grace_minutes;

UPDATE attendance_policies
   SET early_exits_allowed_per_month = 2;

-- Nothing is backfilled onto attendance. Days already settled keep the
-- figures they were settled with: every one of them was forgiven under
-- the rule in force at the time, and rewriting them would invent early
-- exits against people for evenings the system told them were fine.

-- Check after this runs: 15 minutes an occasion, two occasions a month.
SELECT code, early_exit_grace_minutes, early_exits_allowed_per_month
  FROM attendance_policies;
