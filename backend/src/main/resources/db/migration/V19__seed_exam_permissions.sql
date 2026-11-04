-- =====================================================================
-- V19 : Permissions for the exam module
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
-- V17 carries the same block for the meeting module, with the same
-- comment. Every future permission migration needs it. Verify rather than
-- assume — after this runs:
--
--   SELECT r.code, COUNT(*) FROM roles r
--     JOIN role_permissions rp ON rp.role_id = r.id
--     JOIN permissions p ON p.id = rp.permission_id
--    WHERE p.module = 'EXAM' GROUP BY r.code;
--
-- A test using an HR principal will not catch a missing grant. Only an
-- ADMIN one will.
-- =====================================================================

INSERT INTO permissions (code, name, module, description) VALUES
  ('EXAM_VIEW_SELF',        'View own exams',         'EXAM', 'See the exams you have been assigned'),
  ('EXAM_ATTEMPT',          'Sit an exam',            'EXAM', 'Start, answer and submit an exam assigned to you'),
  ('EXAM_RESULT_VIEW_TEAM', 'View team exam results', 'EXAM', 'See results and analysis for your reporting subtree'),
  ('EXAM_RESULT_VIEW_ALL',  'View all exam results',  'EXAM', 'See results and analysis across the organisation'),
  ('EXAM_MANAGE',           'Manage exams',           'EXAM', 'Create exams, publish them, open retakes'),
  ('EXAM_QUESTION_IMPORT',  'Import exam questions',  'EXAM', 'Upload a question workbook and read its answer key'),
  ('EXAM_RESULT_EXPORT',    'Export exam results',    'EXAM', 'Download exam results and question analysis');

-- EMPLOYEE: sit the exam, see their own result. Nothing else — note that
-- EXAM_QUESTION_IMPORT is the permission that carries sight of the answer
-- key, so it never travels with the ability to sit a paper.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('EXAM_VIEW_SELF', 'EXAM_ATTEMPT')
WHERE r.code = 'EMPLOYEE';

-- MANAGER: the above, plus the results for their own reporting subtree.
-- Everybody sits the exam, managers included.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('EXAM_VIEW_SELF', 'EXAM_ATTEMPT', 'EXAM_RESULT_VIEW_TEAM')
WHERE r.code = 'MANAGER';

-- HR: sets the paper and reads everything. They sit it too, which is why
-- exam_participants carries a TESTER kind — on the roster, out of the
-- figures, because they read the answers while loading them.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('EXAM_VIEW_SELF', 'EXAM_ATTEMPT', 'EXAM_RESULT_VIEW_TEAM',
                'EXAM_RESULT_VIEW_ALL', 'EXAM_MANAGE', 'EXAM_QUESTION_IMPORT',
                'EXAM_RESULT_EXPORT')
WHERE r.code = 'HR';

-- ADMIN and SUPER_ADMIN: see the header. Without this they get 403.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
  ON p.code IN ('EXAM_VIEW_SELF', 'EXAM_ATTEMPT', 'EXAM_RESULT_VIEW_TEAM',
                'EXAM_RESULT_VIEW_ALL', 'EXAM_MANAGE', 'EXAM_QUESTION_IMPORT',
                'EXAM_RESULT_EXPORT')
WHERE r.code IN ('ADMIN', 'SUPER_ADMIN');
