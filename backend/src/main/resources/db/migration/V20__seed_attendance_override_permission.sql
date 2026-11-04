-- =====================================================================
-- V20 : Direct attendance override, for SUPER_ADMIN only
--
-- The ordinary way to correct a punch is a regularization: the employee
-- raises it, a manager approves it, and it is refused outside a 7-day
-- window or beyond three a month. That is the right default — it keeps a
-- second pair of eyes on anything that changes somebody's pay.
--
-- This permission is the escape hatch for the cases that flow cannot
-- reach: a device that failed for a whole shift, a correction found after
-- the window shut, or a day the employee never punched at all and so has
-- no record to raise a request against.
--
-- WHY ONLY SUPER_ADMIN
--
-- ATTENDANCE_EDIT already exists and is held by HR, ADMIN and SUPER_ADMIN,
-- but nothing implements it. It is deliberately not reused here: this
-- bypasses approval entirely and rewrites a payroll input, so it goes to
-- the one role that also controls the permission catalogue itself —
-- alongside PERMISSION_MANAGE and DATA_RETENTION_MANAGE, which are the
-- only other SUPER_ADMIN-exclusive permissions.
--
-- ADMIN is deliberately excluded. See the header on V17 for why a later
-- migration must grant every role explicitly: V5's grants were one-shot
-- INSERT ... SELECT statements, so leaving ADMIN out of the block below is
-- what actually keeps it out, not an accident of ordering.
--
-- Note what this permission does NOT do. It does not make the change
-- invisible: every use writes an audit_logs row with the before and after
-- values and a mandatory reason, the original punch events are never
-- deleted, and the attendance row is stamped MANUAL_OVERRIDE so a report
-- can tell an administrator's correction from an approved regularization.
-- An attendance figure that cannot be explained is worth less than no
-- figure at all.
-- =====================================================================

INSERT INTO permissions (code, name, module, description) VALUES
  ('ATTENDANCE_OVERRIDE', 'Override attendance times', 'ATTENDANCE',
   'Set punch-in and punch-out directly, without a regularization request or approval');

-- SUPER_ADMIN, and nobody else. Verify rather than assume:
--
--   SELECT r.code FROM roles r
--     JOIN role_permissions rp ON rp.role_id = r.id
--     JOIN permissions p ON p.id = rp.permission_id
--    WHERE p.code = 'ATTENDANCE_OVERRIDE';
--
-- should return exactly one row.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code = 'ATTENDANCE_OVERRIDE'
WHERE r.code = 'SUPER_ADMIN';
