-- =====================================================================
-- V23 : Permission for an administrator to reset someone's password
--
-- Until now the only way back into a locked-out account was the
-- self-service token flow, and that flow has no delivery: AuthServiceImpl
-- writes the reset token to the application log, so recovering a password
-- meant SSH to the server and grep the container log. With 40-odd people
-- about to receive a first password, that is the support queue.
--
-- Its own code rather than reusing USER_ROLE_ASSIGN or EMPLOYEE_UPDATE.
-- Resetting a credential is a different thing to be trusted with than
-- editing a record: whoever holds it can take any account in the company,
-- because the reset lands on a known shared password. Keeping it separate
-- is what makes that grant a decision somebody has to make on purpose.
--
-- IMPORTANT -- why ADMIN and SUPER_ADMIN are granted explicitly below.
--
-- V5's role grants are one-shot INSERT ... SELECT statements, not views,
-- triggers or rules: they were evaluated once, when V5 ran. A permission
-- added by a later migration gets no role_permissions row for either
-- role, and UserPrincipal builds its authorities strictly from
-- role.getPermissions(), so an administrator would get 403 on every
-- endpoint behind this code.
--
-- Verify rather than assume -- after this runs:
--
--   SELECT r.code FROM roles r
--     JOIN role_permissions rp ON rp.role_id = r.id
--     JOIN permissions p ON p.id = rp.permission_id
--    WHERE p.code = 'USER_PASSWORD_RESET';
--
-- Expect HR, ADMIN and SUPER_ADMIN. A test using an HR principal will
-- not catch a missing admin grant.
-- =====================================================================

INSERT INTO permissions (code, name, module, description) VALUES
  ('USER_PASSWORD_RESET', 'Reset a password', 'RBAC',
   'Return an account to the configured first-time password, forcing a change on next sign-in');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code = 'USER_PASSWORD_RESET'
WHERE r.code IN ('HR', 'ADMIN', 'SUPER_ADMIN');
