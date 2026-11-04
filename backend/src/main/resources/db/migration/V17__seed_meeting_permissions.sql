-- =====================================================================
-- V17 : Permissions for the meeting module
--
-- IMPORTANT — why ADMIN and SUPER_ADMIN are granted explicitly below.
--
-- V5 gave ADMIN "everything except two codes" and SUPER_ADMIN "the whole
-- catalogue". Those are one-shot INSERT ... SELECT statements, not views,
-- triggers or rules: they were evaluated once, when V5 ran. A permission
-- added by a later migration therefore gets no role_permissions row for
-- either role, and since UserPrincipal builds its authorities strictly
-- from role.getPermissions(), an administrator would get 403 on every
-- endpoint added here.
--
-- Every future permission migration must repeat this block.
-- =====================================================================

INSERT INTO permissions (code, name, module, description) VALUES
  ('MEETING_VIEW_SELF',   'View own meetings',      'MEETING', 'See meetings you have been invited to'),
  ('MEETING_JOIN',        'Join a meeting',         'MEETING', 'Open the meeting link, which records attendance'),
  ('MEETING_MANAGE_TEAM', 'Manage team meetings',   'MEETING', 'Create and run meetings for the reporting subtree'),
  ('MEETING_MANAGE_ALL',  'Manage all meetings',    'MEETING', 'Create and run meetings for the whole organisation'),
  ('MEETING_REPORT_VIEW', 'View meeting attendance','MEETING', 'See who joined a meeting and when');

-- EMPLOYEE: see and join what they are invited to, nothing more.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('MEETING_VIEW_SELF', 'MEETING_JOIN')
WHERE r.code = 'EMPLOYEE';

-- MANAGER: the above, plus running meetings for their own reporting subtree.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('MEETING_VIEW_SELF', 'MEETING_JOIN',
                'MEETING_MANAGE_TEAM', 'MEETING_REPORT_VIEW')
WHERE r.code = 'MANAGER';

-- HR: organisation-wide.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('MEETING_VIEW_SELF', 'MEETING_JOIN',
                'MEETING_MANAGE_TEAM', 'MEETING_MANAGE_ALL', 'MEETING_REPORT_VIEW')
WHERE r.code = 'HR';

-- ADMIN and SUPER_ADMIN: see the header. Without this they get 403.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('MEETING_VIEW_SELF', 'MEETING_JOIN',
                'MEETING_MANAGE_TEAM', 'MEETING_MANAGE_ALL', 'MEETING_REPORT_VIEW')
WHERE r.code IN ('ADMIN', 'SUPER_ADMIN');
