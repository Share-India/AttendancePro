-- =====================================================================
-- V5 : Seed the role and permission catalogue.
-- This is the DEFAULT matrix, not a compile-time constant. ADMIN and
-- SUPER_ADMIN can change grants at runtime.
-- =====================================================================

INSERT INTO roles (code, name, description, is_system, is_active) VALUES
  ('EMPLOYEE',    'Employee',    'Self-service: punch, own attendance, leave and requests', TRUE, TRUE),
  ('MANAGER',     'Manager',     'Team visibility and first-level approvals',               TRUE, TRUE),
  ('HR',          'HR',          'Organisation-wide administration and second-level approvals', TRUE, TRUE),
  ('ADMIN',       'Admin',       'System configuration, policies, roles and audit',         TRUE, TRUE),
  ('SUPER_ADMIN', 'Super Admin', 'Full system control including the permission catalogue',  TRUE, TRUE);

INSERT INTO permissions (code, name, module, description) VALUES
  ('ATTENDANCE_PUNCH',            'Punch in and out',            'ATTENDANCE',     'Record own punch, break start and break end'),
  ('ATTENDANCE_VIEW_SELF',        'View own attendance',         'ATTENDANCE',     'Own attendance history, calendar and summary'),
  ('ATTENDANCE_VIEW_TEAM',        'View team attendance',        'ATTENDANCE',     'Attendance of the reporting subtree'),
  ('ATTENDANCE_VIEW_ALL',         'View all attendance',         'ATTENDANCE',     'Organisation-wide attendance'),
  ('ATTENDANCE_EDIT',             'Edit attendance',             'ATTENDANCE',     'HR correction of an attendance record'),
  ('ATTENDANCE_LOCK',             'Lock attendance period',      'ATTENDANCE',     'Freeze a period for payroll'),

  ('LEAVE_APPLY',                 'Apply for leave',             'LEAVE',          'Submit own leave requests'),
  ('LEAVE_APPROVE_L1',            'Approve leave (level 1)',     'LEAVE',          'First-level approval for the reporting subtree'),
  ('LEAVE_APPROVE_L2',            'Approve leave (level 2)',     'LEAVE',          'Second-level HR approval'),
  ('LEAVE_CANCEL_ANY',            'Cancel any leave',            'LEAVE',          'Cancel a leave request on behalf of an employee'),
  ('LEAVE_BALANCE_ADJUST',        'Adjust leave balance',        'LEAVE',          'Manual credit or debit of a leave balance'),
  ('LEAVE_TYPE_MANAGE',           'Manage leave types',          'LEAVE',          'Create and configure leave types and quotas'),

  ('REGULARIZATION_APPLY',        'Apply for regularization',    'REGULARIZATION', 'Request an attendance correction'),
  ('REGULARIZATION_APPROVE_L1',   'Approve regularization (L1)', 'REGULARIZATION', 'First-level approval'),
  ('REGULARIZATION_APPROVE_L2',   'Approve regularization (L2)', 'REGULARIZATION', 'Second-level HR approval'),

  ('WFH_APPLY',                   'Apply for work from home',    'WFH',            'Submit own WFH requests'),
  ('WFH_APPROVE',                 'Approve work from home',      'WFH',            'Approve WFH for the reporting subtree'),
  ('OVERTIME_APPROVE',            'Approve overtime',            'OVERTIME',       'Approve overtime for the reporting subtree'),

  ('EMPLOYEE_VIEW_SELF',          'View own profile',            'EMPLOYEE',       'Own employee profile'),
  ('EMPLOYEE_VIEW_TEAM',          'View team profiles',          'EMPLOYEE',       'Profiles within the reporting subtree'),
  ('EMPLOYEE_VIEW_ALL',           'View all employees',          'EMPLOYEE',       'Organisation-wide employee directory'),
  ('EMPLOYEE_CREATE',             'Create employee',             'EMPLOYEE',       'Add a new employee record'),
  ('EMPLOYEE_UPDATE',             'Update employee',             'EMPLOYEE',       'Modify an employee record'),
  ('EMPLOYEE_DEACTIVATE',         'Deactivate employee',         'EMPLOYEE',       'Deactivate or reactivate an employee'),
  ('EMPLOYEE_BULK_IMPORT',        'Bulk import employees',       'EMPLOYEE',       'Import employees from CSV or Excel'),

  ('DEPARTMENT_MANAGE',           'Manage departments',          'ORGANIZATION',   'Create and modify departments'),
  ('DESIGNATION_MANAGE',          'Manage designations',         'ORGANIZATION',   'Create and modify designations'),
  ('SHIFT_MANAGE',                'Manage shifts',               'SHIFT',          'Create and configure shifts'),
  ('SHIFT_ASSIGN',                'Assign shifts',               'SHIFT',          'Assign shifts to employees or departments'),
  ('HOLIDAY_MANAGE',              'Manage holidays',             'CALENDAR',       'Manage holidays and holiday calendars'),

  ('REPORT_VIEW_TEAM',            'View team reports',           'REPORT',         'Reports scoped to the reporting subtree'),
  ('REPORT_VIEW_ALL',             'View all reports',            'REPORT',         'Organisation-wide reports'),
  ('REPORT_EXPORT',               'Export reports',              'REPORT',         'Export report data to Excel, CSV or PDF'),

  ('OFFICE_MANAGE',               'Manage offices',              'ORGANIZATION',   'Create and modify offices'),
  ('GEOFENCE_MANAGE',             'Manage geofences',            'GEOFENCE',       'Configure office locations and radii'),
  ('POLICY_MANAGE',               'Manage policies',             'POLICY',         'Attendance, location, overtime and leave policies'),
  ('SETTINGS_MANAGE',             'Manage company settings',     'POLICY',         'Company-wide configuration'),
  ('ROLE_MANAGE',                 'Manage roles',                'RBAC',           'Create roles and change their permissions'),
  ('USER_ROLE_ASSIGN',            'Assign roles to users',       'RBAC',           'Grant or revoke a user role'),
  ('AUDIT_VIEW',                  'View audit logs',             'AUDIT',          'Read the audit trail'),
  ('PERMISSION_MANAGE',           'Manage permission catalogue', 'RBAC',           'Add or remove permission definitions'),
  ('DATA_RETENTION_MANAGE',       'Manage data retention',       'PRIVACY',        'Configure and execute retention purges');

-- EMPLOYEE: self-service only
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('ATTENDANCE_PUNCH','ATTENDANCE_VIEW_SELF','LEAVE_APPLY','REGULARIZATION_APPLY',
                'WFH_APPLY','EMPLOYEE_VIEW_SELF')
WHERE r.code = 'EMPLOYEE';

-- MANAGER: employee rights plus the reporting subtree and L1 approvals
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('ATTENDANCE_PUNCH','ATTENDANCE_VIEW_SELF','ATTENDANCE_VIEW_TEAM',
                'LEAVE_APPLY','LEAVE_APPROVE_L1','REGULARIZATION_APPLY','REGULARIZATION_APPROVE_L1',
                'WFH_APPLY','WFH_APPROVE','OVERTIME_APPROVE',
                'EMPLOYEE_VIEW_SELF','EMPLOYEE_VIEW_TEAM','REPORT_VIEW_TEAM')
WHERE r.code = 'MANAGER';

-- HR: organisation-wide administration and L2 approvals
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('ATTENDANCE_PUNCH','ATTENDANCE_VIEW_SELF','ATTENDANCE_VIEW_TEAM','ATTENDANCE_VIEW_ALL',
                'ATTENDANCE_EDIT','ATTENDANCE_LOCK',
                'LEAVE_APPLY','LEAVE_APPROVE_L1','LEAVE_APPROVE_L2','LEAVE_CANCEL_ANY',
                'LEAVE_BALANCE_ADJUST','LEAVE_TYPE_MANAGE',
                'REGULARIZATION_APPLY','REGULARIZATION_APPROVE_L1','REGULARIZATION_APPROVE_L2',
                'WFH_APPLY','WFH_APPROVE','OVERTIME_APPROVE',
                'EMPLOYEE_VIEW_SELF','EMPLOYEE_VIEW_TEAM','EMPLOYEE_VIEW_ALL','EMPLOYEE_CREATE',
                'EMPLOYEE_UPDATE','EMPLOYEE_DEACTIVATE','EMPLOYEE_BULK_IMPORT',
                'DEPARTMENT_MANAGE','DESIGNATION_MANAGE','SHIFT_MANAGE','SHIFT_ASSIGN','HOLIDAY_MANAGE',
                'REPORT_VIEW_TEAM','REPORT_VIEW_ALL','REPORT_EXPORT')
WHERE r.code = 'HR';

-- ADMIN: everything HR has, plus system configuration
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code NOT IN ('PERMISSION_MANAGE','DATA_RETENTION_MANAGE')
WHERE r.code = 'ADMIN';

-- SUPER_ADMIN: the whole catalogue
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON 1 = 1
WHERE r.code = 'SUPER_ADMIN';
