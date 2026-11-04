-- =====================================================================
-- V6 : Minimal reference data and the bootstrap administrator.
--
-- The bootstrap password hash below is BCrypt(strength 12) of "ChangeMe@123".
-- must_change_password is TRUE, so the account cannot be used until the
-- password is rotated. Delete or disable this user before production.
-- =====================================================================

INSERT INTO company_settings (setting_key, setting_value, value_type, category, description) VALUES
  ('company.name',                    'Your Company',      'STRING',  'GENERAL',      'Display name used across the UI and reports'),
  ('company.timezone',                'Asia/Kolkata',      'STRING',  'GENERAL',      'Fallback timezone when an office has none'),
  ('auth.max_failed_attempts',        '5',                 'INT',     'SECURITY',     'Consecutive failures before the account locks'),
  ('auth.lockout_minutes',            '30',                'INT',     'SECURITY',     'How long an account stays locked'),
  ('auth.password_min_length',        '8',                 'INT',     'SECURITY',     'Minimum password length'),
  ('leave.year_start_month',          '4',                 'INT',     'LEAVE',        'Month the leave year begins (1-12)'),
  ('data.retention_days.audit_log',   '2555',              'INT',     'PRIVACY',      'Audit log retention in days'),
  ('data.retention_days.punch_location', '1095',           'INT',     'PRIVACY',      'Raw punch coordinate retention in days'),
  ('notification.channels.enabled',   'IN_APP',            'STRING',  'NOTIFICATION', 'Comma-separated active channels');

INSERT INTO offices (code, name, city, state, country, timezone, is_active) VALUES
  ('HO', 'Head Office', 'Mumbai', 'Maharashtra', 'India', 'Asia/Kolkata', TRUE);

INSERT INTO office_locations (office_id, location_name, address, latitude, longitude, allowed_radius_meters, location_type, is_active)
SELECT o.id, 'Head Office - Main Gate', 'Update this address in Admin > Offices', 19.0759837, 72.8776559, 200, 'OFFICE', TRUE
FROM offices o WHERE o.code = 'HO';

INSERT INTO departments (code, name, description, is_active) VALUES
  ('ADMIN-DEPT', 'Administration', 'Default department created at install time', TRUE);

INSERT INTO designations (code, name, level, is_active) VALUES
  ('SYS-ADMIN', 'System Administrator', 90, TRUE);

INSERT INTO employees (employee_code, first_name, last_name, official_email, joining_date,
                       department_id, designation_id, office_id, employment_type, employment_status, is_active)
SELECT 'EMP0001', 'System', 'Administrator', 'admin@company.com', CURRENT_DATE,
       (SELECT id FROM departments WHERE code = 'ADMIN-DEPT'),
       (SELECT id FROM designations WHERE code = 'SYS-ADMIN'),
       (SELECT id FROM offices WHERE code = 'HO'),
       'PERMANENT', 'ACTIVE', TRUE;

INSERT INTO users (employee_id, username, email, password_hash, is_active, must_change_password)
SELECT e.id, 'EMP0001', 'admin@company.com',
       '$2a$12$RJKZwn5VqVvPkuoCGWpbBu6q/hjaNVePEs/wFWFH1v9.mHPxcBCIy',
       TRUE, TRUE
FROM employees e WHERE e.employee_code = 'EMP0001';

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u JOIN roles r ON r.code IN ('SUPER_ADMIN', 'EMPLOYEE')
WHERE u.username = 'EMP0001';
