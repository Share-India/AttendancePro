-- =====================================================================
-- V18 : Weekly internal study exam
--
-- A learning instrument, not an appraisal input. Nothing here feeds a
-- performance score, and the reason the module exists is the per-question
-- analytics rather than anyone's individual mark. If that ever stops being
-- true, the feedback timing, the tester exclusion and the audit depth
-- around re-scoring all need revisiting.
--
-- Three shapes are worth reading before changing anything:
--
--  1. exam_participants is snapshotted at publish, not computed on read.
--     Somebody who joins on Wednesday must not appear in Monday's
--     participation figure, and last week's numbers must not move every
--     time the org chart does. Same reasoning as meeting_participants.
--
--  2. exam_answers is the record; exam_attempts.score is derived from it.
--     Same relationship as attendance_punch_events to attendance. A
--     question found to be wrongly keyed in November becomes a re-score
--     rather than two hundred people sitting the paper again.
--
--  3. exam_attempts.option_order is part of the record, not a display
--     detail. Options are shuffled per person; without storing the
--     permutation the submission cannot be scored, the candidate cannot be
--     shown the paper they actually sat, and a disputed result cannot be
--     answered.
--
-- Every parameter that might reasonably differ between exams — duration,
-- marks, pass mark, shuffling, feedback timing — is a column rather than a
-- constant in code, because the first request after launch is always "can
-- this one be thirty minutes".
-- =====================================================================

CREATE TABLE exams (
    id                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    exam_code                 VARCHAR(40)     NOT NULL,
    title                     VARCHAR(200)    NOT NULL,
    description               VARCHAR(1000)   NULL,
    -- Shown on the instructions screen. Rendered as text, never as HTML.
    instructions              TEXT            NULL,
    status                    VARCHAR(20)     NOT NULL DEFAULT 'DRAFT',

    -- Stored UTC, entered and displayed in the office zone — exactly as
    -- attendance does it. The JVM runs on UTC.
    window_start              TIMESTAMP       NOT NULL,
    window_end                TIMESTAMP       NOT NULL,
    duration_minutes          INT             NOT NULL DEFAULT 25,
    -- Somebody starting two minutes before the window shuts gets two
    -- minutes. That is arithmetically correct and reliably produces a
    -- complaint, so the door closes this far early.
    entry_closes_before_min   INT             NOT NULL DEFAULT 5,

    marks_per_question        INT             NOT NULL DEFAULT 5,
    -- Off at launch. The column exists so switching it on is configuration
    -- rather than a migration, which is why marks_awarded below is signed.
    negative_marks            INT             NOT NULL DEFAULT 0,
    total_marks               INT             NOT NULL DEFAULT 100,
    pass_marks                INT             NOT NULL DEFAULT 40,

    shuffle_questions         BOOLEAN         NOT NULL DEFAULT TRUE,
    -- The structural defence against a paper that can be passed by ticking
    -- B twenty times. It works whether or not anybody reads the quality
    -- report, which is the point.
    shuffle_options           BOOLEAN         NOT NULL DEFAULT TRUE,
    -- All-or-nothing on multi-select. Two of three right is not two thirds
    -- of knowing the answer, so partial credit is a decision per exam.
    partial_credit            BOOLEAN         NOT NULL DEFAULT FALSE,
    feedback_visibility       VARCHAR(20)     NOT NULL DEFAULT 'IMMEDIATE',

    -- Set on a retake, pointing at the exam it is a second chance for.
    parent_exam_id            BIGINT UNSIGNED NULL,
    -- A retake on the paper somebody already sat hands them the answer key
    -- and then re-tests them on it. Enforced in code; the flag is here so
    -- the intent is visible on the record rather than implied by behaviour.
    retake_requires_unseen_set BOOLEAN        NOT NULL DEFAULT TRUE,

    published_at              TIMESTAMP       NULL,
    published_by              BIGINT UNSIGNED NULL,
    closed_at                 TIMESTAMP       NULL,
    cancellation_reason       VARCHAR(500)    NULL,

    created_at                TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by                BIGINT UNSIGNED NULL,
    updated_by                BIGINT UNSIGNED NULL,
    version                   INT             NOT NULL DEFAULT 0,

    PRIMARY KEY (id),
    UNIQUE KEY uk_exam_code (exam_code),
    KEY idx_exam_window (status, window_start, window_end),
    KEY idx_exam_parent (parent_exam_id),
    CONSTRAINT fk_exam_parent FOREIGN KEY (parent_exam_id) REFERENCES exams (id) ON DELETE RESTRICT,
    CONSTRAINT ck_exam_window   CHECK (window_end > window_start),
    CONSTRAINT ck_exam_pass     CHECK (pass_marks >= 0 AND pass_marks <= total_marks),
    CONSTRAINT ck_exam_duration CHECK (duration_minutes > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- One of A, B, C. The totals are stored per set because publish refuses to
-- go ahead unless they agree: a pass mark of 40 is not the same bar if set
-- A is out of 100 and set B is out of 95, and nothing in the results would
-- ever show it.
CREATE TABLE exam_question_sets (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    exam_id        BIGINT UNSIGNED NOT NULL,
    set_code       VARCHAR(10)     NOT NULL,
    question_count INT             NOT NULL DEFAULT 0,
    total_marks    INT             NOT NULL DEFAULT 0,

    created_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by     BIGINT UNSIGNED NULL,
    updated_by     BIGINT UNSIGNED NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_exam_set_code (exam_id, set_code),
    CONSTRAINT fk_exam_set_exam FOREIGN KEY (exam_id) REFERENCES exams (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


CREATE TABLE exam_questions (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    question_set_id  BIGINT UNSIGNED NOT NULL,
    question_number  INT             NOT NULL,

    -- The workbook's "Product" column, normalised on import. This is what
    -- the per-question analytics group by, and "Health Insurance" against
    -- "health insurance " would split one training need into two.
    topic            VARCHAR(120)    NULL,
    difficulty       VARCHAR(20)     NULL,
    question_type    VARCHAR(20)     NOT NULL DEFAULT 'SINGLE_CHOICE',

    -- TEXT, not VARCHAR: stems run past 255 characters routinely and the
    -- explanation almost always does.
    question_text    TEXT            NOT NULL,
    marks            INT             NOT NULL DEFAULT 5,
    explanation      TEXT            NULL,
    source_reference VARCHAR(500)    NULL,

    created_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by       BIGINT UNSIGNED NULL,
    updated_by       BIGINT UNSIGNED NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_exam_question_number (question_set_id, question_number),
    KEY idx_exam_question_topic (topic),
    CONSTRAINT fk_exam_question_set FOREIGN KEY (question_set_id)
        REFERENCES exam_question_sets (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- Canonical labels, as they appeared in the workbook. What a candidate sees
-- is a permutation of these, recorded on their own attempt.
CREATE TABLE exam_question_options (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    question_id   BIGINT UNSIGNED NOT NULL,
    option_label  VARCHAR(4)      NOT NULL,
    option_text   TEXT            NOT NULL,
    is_correct    BOOLEAN         NOT NULL DEFAULT FALSE,
    display_order INT             NOT NULL DEFAULT 0,

    created_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by    BIGINT UNSIGNED NULL,
    updated_by    BIGINT UNSIGNED NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_exam_option_label (question_id, option_label),
    CONSTRAINT fk_exam_option_question FOREIGN KEY (question_id)
        REFERENCES exam_questions (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- Who the exam is for, as scopes. A GLOBAL row with no scope id means
-- everybody. Read once, at publish, and never consulted again.
CREATE TABLE exam_audience (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    exam_id    BIGINT UNSIGNED NOT NULL,
    scope_type VARCHAR(20)     NOT NULL,
    scope_id   BIGINT UNSIGNED NULL,

    created_at TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_exam_audience (exam_id, scope_type, scope_id),
    CONSTRAINT fk_exam_audience_exam FOREIGN KEY (exam_id) REFERENCES exams (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- The roster, fixed at publish. See note 1 in the header.
CREATE TABLE exam_participants (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    exam_id          BIGINT UNSIGNED NOT NULL,
    employee_id      BIGINT UNSIGNED NOT NULL,
    assigned_set_id  BIGINT UNSIGNED NOT NULL,

    -- HR and administrators sit the paper having already read the answers.
    -- They stay on the roster and out of the pass rate.
    participant_kind VARCHAR(20)     NOT NULL DEFAULT 'CANDIDATE',
    status           VARCHAR(20)     NOT NULL DEFAULT 'ASSIGNED',

    created_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by       BIGINT UNSIGNED NULL,
    updated_by       BIGINT UNSIGNED NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_exam_participant (exam_id, employee_id),
    KEY idx_exam_participant_status (exam_id, status),
    KEY idx_exam_participant_employee (employee_id),
    CONSTRAINT fk_exam_participant_exam FOREIGN KEY (exam_id)
        REFERENCES exams (id) ON DELETE CASCADE,
    CONSTRAINT fk_exam_participant_employee FOREIGN KEY (employee_id)
        REFERENCES employees (id) ON DELETE RESTRICT,
    CONSTRAINT fk_exam_participant_set FOREIGN KEY (assigned_set_id)
        REFERENCES exam_question_sets (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


CREATE TABLE exam_attempts (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    participant_id    BIGINT UNSIGNED NOT NULL,
    attempt_number    INT             NOT NULL DEFAULT 1,

    -- See note 3 in the header. JSON held as TEXT: nothing queries into it,
    -- and the test schema is generated from the entities rather than from
    -- this file.
    question_order    TEXT            NULL,
    option_order      TEXT            NULL,

    started_at        TIMESTAMP       NOT NULL,
    -- min(started_at + duration, window_end), fixed at start. The browser
    -- clock is decoration.
    expires_at        TIMESTAMP       NOT NULL,
    submitted_at      TIMESTAMP       NULL,
    submission_source VARCHAR(30)     NULL,

    score             INT             NULL,
    max_score         INT             NULL,
    percentage        DECIMAL(5,2)    NULL,
    -- Decided on marks against the stored pass mark, never on a rounded
    -- percentage: with five-mark questions seven correct is 35 and a fail.
    passed            BOOLEAN         NULL,

    created_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by        BIGINT UNSIGNED NULL,
    updated_by        BIGINT UNSIGNED NULL,

    PRIMARY KEY (id),
    -- Carries the idempotent start: a double tap inserts twice, the second
    -- violates this, and the caller is handed the attempt that already
    -- exists. Same pattern as punch-in.
    UNIQUE KEY uk_exam_attempt (participant_id, attempt_number),
    KEY idx_exam_attempt_open (submitted_at, expires_at),
    CONSTRAINT fk_exam_attempt_participant FOREIGN KEY (participant_id)
        REFERENCES exam_participants (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- The record of what happened. See note 2 in the header.
CREATE TABLE exam_answers (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    attempt_id       BIGINT UNSIGNED NOT NULL,
    question_id      BIGINT UNSIGNED NOT NULL,

    -- Canonical labels, translated back from what the candidate clicked on
    -- the way in. Scoring and analytics never have to unshuffle anything.
    selected_options VARCHAR(20)     NULL,
    is_correct       BOOLEAN         NOT NULL DEFAULT FALSE,
    -- Signed: harmless at zero negative marking, correct when it is on.
    marks_awarded    INT             NOT NULL DEFAULT 0,
    answered_at      TIMESTAMP       NOT NULL,

    created_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by       BIGINT UNSIGNED NULL,
    updated_by       BIGINT UNSIGNED NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uk_exam_answer (attempt_id, question_id),
    KEY idx_exam_answer_analysis (question_id, is_correct),
    CONSTRAINT fk_exam_answer_attempt FOREIGN KEY (attempt_id)
        REFERENCES exam_attempts (id) ON DELETE CASCADE,
    CONSTRAINT fk_exam_answer_question FOREIGN KEY (question_id)
        REFERENCES exam_questions (id) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- What was uploaded, never the upload itself. The workbook is an answer
-- key, and an answer key on disk is an answer key waiting to leak. The hash
-- is enough to prove later which file produced this question bank.
CREATE TABLE exam_uploads (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    exam_id        BIGINT UNSIGNED NOT NULL,
    file_name      VARCHAR(260)    NOT NULL,
    file_sha256    VARCHAR(64)     NOT NULL,
    file_size      BIGINT          NOT NULL,
    question_count INT             NOT NULL DEFAULT 0,
    set_count      INT             NOT NULL DEFAULT 0,
    rejected_count INT             NOT NULL DEFAULT 0,

    -- Answer spread, longest-option rate, absolute words, duplicates.
    -- Warnings rather than errors, but recorded so "we were told" is
    -- checkable afterwards.
    quality_report TEXT            NULL,
    uploaded_by    BIGINT UNSIGNED NULL,
    uploaded_at    TIMESTAMP       NOT NULL,

    created_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by     BIGINT UNSIGNED NULL,
    updated_by     BIGINT UNSIGNED NULL,

    PRIMARY KEY (id),
    KEY idx_exam_upload (exam_id, uploaded_at),
    CONSTRAINT fk_exam_upload_exam FOREIGN KEY (exam_id) REFERENCES exams (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;


-- Defaults for a new exam. Settings rather than constants, for the same
-- reason the columns above exist: the first request after launch is always
-- to change one of them.
INSERT INTO company_settings (setting_key, setting_value, value_type, category, description) VALUES
  ('exam.default_duration_minutes',    '25', 'INT', 'EXAM',
   'Minutes a candidate gets once they start, used as the default on a new exam'),
  ('exam.default_marks_per_question',  '5',  'INT', 'EXAM',
   'Marks for a correct answer when the workbook does not give a Marks column'),
  ('exam.default_pass_marks',          '40', 'INT', 'EXAM',
   'Marks needed to pass a 100-mark paper, used as the default on a new exam'),
  ('exam.entry_closes_before_minutes', '5',  'INT', 'EXAM',
   'Minutes before the window shuts beyond which starting the paper is refused');
