-- =====================================================================
-- V21 : Permission for managing weekly offs
--
-- weekly_offs has existed since V10 and CalendarService has always read
-- it, but nothing could write a row: no service, no controller and no
-- permission. Alternate-Saturday rosters were expressible in the schema
-- and unreachable from the application.
--
-- V5 already carries HOLIDAY_MANAGE in the CALENDAR module. Weekly offs
-- get their own code rather than reusing it: a recurring roster and a
-- declared public holiday are different things to be trusted with, even
-- though the same people happen to hold both today.
--
-- IMPORTANT — why ADMIN and SUPER_ADMIN are granted explicitly below.
--
-- V5's role grants are one-shot INSERT ... SELECT statements, not views,
-- triggers or rules: they were evaluated once, when V5 ran. A permission
-- added by a later migration gets no role_permissions row for either
-- role, and UserPrincipal builds its authorities strictly from
-- role.getPermissions(), so an administrator would get 403 on every
-- endpoint behind this code.
--
-- Verify rather than assume — after this runs:
--
--   SELECT r.code FROM roles r
--     JOIN role_permissions rp ON rp.role_id = r.id
--     JOIN permissions p ON p.id = rp.permission_id
--    WHERE p.code = 'WEEKLY_OFF_MANAGE';
--
-- Expect HR, ADMIN and SUPER_ADMIN. A test using an HR principal will
-- not catch a missing admin grant.
-- =====================================================================

INSERT INTO permissions (code, name, module, description) VALUES
  ('WEEKLY_OFF_MANAGE', 'Manage weekly offs', 'CALENDAR',
   'Define recurring non-working days, including alternate-week rosters');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code = 'WEEKLY_OFF_MANAGE'
WHERE r.code IN ('HR', 'ADMIN', 'SUPER_ADMIN');
