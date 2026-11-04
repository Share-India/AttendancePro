# Exam Module — Implementation Plan

**Status:** built, 2026-10-14. The plan below is kept as written; what changed
during the build is listed under *As built* immediately after it.
**Date:** 2026-09-29
**Purpose:** weekly internal study exams for product knowledge. Not appraisal input.

---

## As built — where the implementation differs from this plan

Read this before the plan. Everything else below still describes the module
accurately.

| Plan said | Built as | Why |
|---|---|---|
| migrations `V16` / `V17` | **`V18__create_exam_tables.sql`**, **`V19__seed_exam_permissions.sql`** | The meeting module took V16 and V17 first |
| `ExamStatus.ARCHIVED` | `CANCELLED` | Archiving was never specified; calling an exam off is a real operation and needed a reason column |
| `ParticipantStatus` with `PASSED` / `FAILED` / `RETAKE_PENDING` | `ASSIGNED`, `IN_PROGRESS`, `SUBMITTED`, `NOT_ATTEMPTED` | Pass and fail live on the attempt, derived from the answers. Duplicating them on the participant creates two places that can disagree about one result |
| `ExamSweepService`, scheduled | `ExamWindowService`, **lazy** | There is no scheduler anywhere in this application. The window is the authority and the status catches up on the next read, exactly as the meeting module settles itself |
| nine service classes under `service/exam/` | `ExamService`/`Impl`, `ExamAttemptService`/`Impl`, plus `ExamImportService`, `ExamRetakeService`, `ExamAnalyticsService`, and the components in `service/exam/` | Matches the existing split: interface + impl for the two large ones, concrete classes for the narrower ones, as `NotificationService` and `AuditLogService` already are |
| — | `exams.entry_closes_before_min` | Starting five minutes before the window shuts gives five minutes for a twenty-five minute paper. Arithmetically right, reliably a complaint |
| — | `exams.partial_credit` | All-or-nothing on multi-select by default, switchable per exam |
| — | a second retake guard on the **exam** | The per-person guard reads `exam_participants`, which do not exist until a retake is published. Two unpublished retakes could otherwise both be opened and published, and the second would have no unseen set left to deal |

### Two bugs the H2 test suite could not have caught

Both were found by starting the application against real MySQL, which is why
that step is in the verification list rather than optional.

1. **`file_sha256 CHAR(64)` against the entity's `VARCHAR(64)`.** Hibernate's
   `ddl-auto: validate` refuses to start on it. The test schema is generated
   from the entities, so it agreed with itself and said nothing.
2. **A bulk `DELETE FROM ExamQuestionSet`** relied on the database cascading to
   `exam_questions`. V18 declares that cascade and the entity-generated test
   schema does not, so the re-import path passed against MySQL and failed
   against H2. Now deleted through JPA, so the cascade is the mapping's.

### Verified end to end, 2026-10-14

Against real MySQL and in a real browser, with the corrected 60-question
workbook (`docs/exam/Health_Insurance_MCQ_3_Sets_FIXED.xlsx`):

- Flyway applied V18 and V19 cleanly; `ddl-auto: validate` passed.
- `SELECT … WHERE p.module = 'EXAM' GROUP BY r.code` → HR 7, **ADMIN 7,
  SUPER_ADMIN 7**, MANAGER 3, EMPLOYEE 2. The explicit grant in V19 is doing
  the work; without it administrators would 403 on every endpoint here.
- The real workbook imported: 3 sets of 20, 100 marks each, answers spread
  5/5/5/5 per set. The quality analyser independently found the same three
  duplicate questions the workbook's own QA Report sheet lists.
- Published to 16 people, split A:6 B:5 C:5. The administrator who loaded the
  questions was marked **tester** automatically and excluded from the pass rate.
- A paper sat in the browser: server clock counting down, every answer autosaved,
  submitted, scored 15/100 for 3 of 20 — and the result screen showed the
  correct answer, the explanation and the source for every question.
- The window closed with nobody watching. The next read settled the exam to
  CLOSED and moved all 15 non-starters to `NOT_ATTEMPTED`. No scheduler ran.
- A retake was opened for those 15 (the tester excluded, "wrote the paper") and
  published. **Every one of the 15 was dealt a set they had not seen; zero
  clashes.**

287 backend tests pass, 60 of them new.

---

## 1. What is being built

A weekly (sometimes twice-weekly) multiple-choice study exam. HR prepares 60
questions in an Excel workbook split into three sets of twenty. Every employee is
assigned one set at random and sits it inside a window. On submission they see
their score and, for every question, the correct answer with an explanation and a
source reference.

The exam exists to teach and to show HR what the organisation does not know. It
does not feed appraisals. That single fact drives several decisions below, and if
it ever stops being true, sections 5.4, 8 and 10 need revisiting.

### Settled parameters

| Parameter | Value |
|---|---|
| Questions per paper | 20 |
| Sets per exam | 3 (A, B, C) from a 60-question upload |
| Set assignment | one per person, random, fixed at publish |
| Marks per question | 5 |
| Total marks | 100 |
| Pass mark | 40 (8 correct) |
| Duration | 25 minutes |
| Negative marking | off (column exists, default 0) |
| Feedback | immediately on submit |
| Attempts | 1, plus a retake if failed or not attempted |
| Audience | everyone, including HR and admins |
| Scheduling | HR creates each exam manually and attaches the workbook |
| Retention | 24 months, configurable |

All of these are stored as configuration on the exam record. None are hardcoded.

---

## 2. The rule that governs the module

**The answer key must never reach the browser during an attempt.**

This is the exam equivalent of the platform's existing rule that employee identity
comes from the JWT and never from the frontend. It is not satisfied by care; it has
to be structural:

- Two disjoint DTO families. `ExamQuestionForAttempt` has no `isCorrect`, no
  `explanation`, no `sourceReference`. `ExamQuestionWithKey` is HR-only. Never one
  DTO with fields nulled out on the way past — a null-out skipped once is a total
  and silent compromise.
- Scoring happens server-side. The client submits choices, never a score.
- Feedback comes from a separate endpoint that returns 403 until the attempt is
  submitted.
- An integration test asserts the attempt payload contains no `correct`,
  `isCorrect`, `answer`, `explanation` or `source` key anywhere in the JSON tree.
  This is the regression that would be both catastrophic and invisible.

Even though results are not consequential, this still matters: if candidates can
read the key before answering, the exam teaches nothing.

---

## 3. Question quality — why the importer has a QA step

The first real workbook was analysed before any code was written. It could be
passed at **92%** by ticking B for every question without reading anything, and at
**80%** by always choosing the longest option. Wrong options contained an absolute
word ("always", "never", "only") 59% of the time against 7% for correct options, so
"eliminate the absolutes and pick the cautious one" also beat it.

That workbook has been corrected (`docs/exam/Health_Insurance_MCQ_3_Sets_FIXED.xlsx`
— 25% / 31% / 15% respectively after the fix). The lesson for the software is that
**question quality fails silently**. The exam runs perfectly, the pass rate looks
excellent, and it has measured nothing.

So the importer produces a quality report before publish, showing:

- answer distribution per set across A/B/C/D
- how often the correct option is also the longest
- absolute-word rate in correct options versus wrong options
- duplicate questions within and across sets
- any question whose options are wildly uneven in length

These are warnings, not errors. HR can publish anyway. But nobody gets to publish
a paper with twenty B answers without having been told.

Per-participant option shuffling (section 6) is the permanent structural defence:
it neutralises position bias in every future upload, including ones nobody checks.

---

## 4. Excel format

The format follows the workbook HR already produces. A blank template lives at
`docs/exam/Exam-Question-Upload-Template.xlsx` and is downloadable from the import
screen — the employee CSV import taught us that most import pain is people guessing
at column names.

**Sheets:** `Set A` + `Set A Answer Key`, same for B and C, and an optional
`Consolidated Answer Key`.

**Set sheet columns** (read by header name, so order does not matter):

| Column | Required | Notes |
|---|---|---|
| Q. No. | yes | unique within the sheet |
| Product | yes | topic tag, drives per-topic analytics |
| Question | yes | |
| Option A / B | yes | |
| Option C / D | no | blank means a 2- or 3-option question |
| Correct Answer | yes | `A`–`D`, or `A,C` for multi-select |
| Explanation | yes | the feedback shown after submission |
| Source | no | citation shown with the feedback |
| Marks | no | defaults to the exam's marks per question |
| Difficulty | no | `EASY` / `MODERATE` / `HARD` |

**The duplicated answer-key sheets are used as a cross-check.** They look
redundant, but if the Set sheet and the key sheet disagree the whole upload is
rejected. Today they agree; the day someone corrects an answer in one place and not
the other, the import must stop rather than silently pick one.

### Excel handling to get right

Learned from the employee CSV import, each one a line of defence and a test:

- `Q. No.` arriving as a float (`1.0`) rather than an int
- lowercase or padded `Correct Answer` values
- headers with trailing spaces or non-breaking spaces pasted from Word
- merged cells, formula cells (read the cached value), blank trailing rows
- `Explanation` longer than 255 characters — all long text columns are `TEXT`
- sheet-name variants (`Set-A`, `SET A `): match tolerantly, but reject with a
  readable message naming what was found and what was expected. Never skip a sheet
  silently.
- the database is already `utf8mb4`, so `₹` and em-dashes are safe

### Row limits and dry run

A validate-only mode parses and reports every problem without writing anything.
Upload → see errors → fix → re-upload, with no half-loaded question bank.

---

## 5. Data model

Nine tables, migration `V16__create_exam_tables.sql`, permissions in
`V17__seed_exam_permissions.sql`. Current Flyway head is V15.

### 5.1 `exams`

`exam_code`, `title`, `description`, `instructions`, `status`, `window_start`,
`window_end`, `duration_minutes`, `marks_per_question`, `negative_marks`,
`total_marks`, `pass_marks`, `shuffle_questions`, `shuffle_options`,
`feedback_visibility`, `parent_exam_id`, `retake_uses_unseen_set`, `published_at`,
`closed_at`, audit columns.

- Window timestamps are stored UTC and entered/displayed in the office `ZoneId`,
  exactly as attendance does it.
- `parent_exam_id` is set on a retake and points at the exam it follows.
- Constraints: `window_end > window_start`, `0 <= pass_marks <= total_marks`.

### 5.2 `exam_question_sets`

`exam_id`, `set_code`, `display_name`, `question_count`, `total_marks`.
Unique `(exam_id, set_code)`.

### 5.3 `exam_questions`

`question_set_id`, `question_number`, `topic`, `difficulty`, `question_type`,
`question_text`, `marks`, `explanation`, `source_reference`.
Unique `(question_set_id, question_number)`.

`topic` is the workbook's *Product* column. It is what makes "the team is weak on
ICICI Elevate" possible, which is the main output of a study exam.

### 5.4 `exam_question_options`

`question_id`, `option_label`, `option_text`, `is_correct`.
Unique `(question_id, option_label)`.

### 5.5 `exam_audience`

`exam_id`, `scope_type`, `scope_id`. `GLOBAL` with a null scope means everyone.
Resolved into participants at publish.

### 5.6 `exam_participants`

`exam_id`, `employee_id`, `assigned_set_id`, `participant_kind`, `status`.
Unique `(exam_id, employee_id)`.

**The roster is snapshotted at publish, not computed on read.** Someone who joins
on Wednesday must not appear in Monday's participation figure, and last week's
numbers must not move every time the org chart does. Same reasoning as
`wasEmployedOn()` in the dashboard.

`participant_kind` is `CANDIDATE` or `TESTER`. HR and admins sit the paper having
seen the answers, so they stay on the roster but are excluded from pass rates and
team averages by default, with a toggle to include them.

### 5.7 `exam_attempts`

`participant_id`, `attempt_number`, `question_order` (JSON), `option_order` (JSON),
`started_at`, `expires_at`, `submitted_at`, `submission_source`, `score`,
`max_score`, `percentage`, `passed`.
Unique `(participant_id, attempt_number)`.

**The permutation is part of the record, not a display detail.** Options are
shuffled per person; without storing the order the submission cannot be scored, the
candidate cannot be shown the paper they actually sat, and a disputed result cannot
be answered.

`expires_at` is server-held and authoritative. The browser clock is display only.

### 5.8 `exam_answers`

`attempt_id`, `question_id`, `selected_options`, `is_correct`, `marks_awarded`,
`answered_at`. Unique `(attempt_id, question_id)`.

**This is the record; `exam_attempts.score` is derived from it** — the same
relationship as `attendance_punch_events` to `attendance`. A question found to be
wrongly keyed in November can be re-scored from the answers instead of asking two
hundred people to sit it again.

Selected options are stored as the canonical labels from the question bank, not the
shuffled labels the candidate clicked. Translation happens once, on the way in, so
scoring and analytics never have to unshuffle.

### 5.9 `exam_uploads`

`exam_id`, `file_name`, `file_sha256`, `file_size_bytes`, `question_count`,
`set_count`, `rejected_count`, `quality_report` (JSON), `uploaded_by`,
`uploaded_at`.

**The workbook itself is not stored.** It is an answer key, and an answer key on
disk is an answer key waiting to leak. What is kept is provenance and the quality
report.

---

## 6. Enums

| Enum | Values |
|---|---|
| `ExamStatus` | DRAFT, QUESTIONS_LOADED, PUBLISHED, OPEN, CLOSED, ARCHIVED |
| `ParticipantKind` | CANDIDATE, TESTER |
| `ParticipantStatus` | ASSIGNED, IN_PROGRESS, SUBMITTED, PASSED, FAILED, NOT_ATTEMPTED, RETAKE_PENDING |
| `QuestionType` | SINGLE_CHOICE, MULTI_CHOICE |
| `SubmissionSource` | SELF, AUTO_TIMEOUT, AUTO_WINDOW_CLOSE |
| `FeedbackVisibility` | ON_SUBMIT, ON_WINDOW_CLOSE, NEVER |

Additions to existing enums:

- `ReportType` += `EXAM_RESULTS`, `EXAM_PARTICIPATION`, `EXAM_QUESTION_ANALYSIS`
- `AuditAction` += `EXAM_PUBLISH`, `EXAM_SUBMIT`, `EXAM_RESCORE`, `RETAKE_OPEN`

---

## 7. Permissions — `V17`

| Permission | Granted to |
|---|---|
| `EXAM_VIEW_SELF` | EMPLOYEE, MANAGER, HR |
| `EXAM_ATTEMPT` | EMPLOYEE, MANAGER, HR |
| `EXAM_RESULT_VIEW_TEAM` | MANAGER, HR |
| `EXAM_MANAGE` | HR |
| `EXAM_QUESTION_IMPORT` | HR |
| `EXAM_RESULT_VIEW_ALL` | HR |
| `EXAM_RESULT_EXPORT` | HR |

> **ADMIN and SUPER_ADMIN do _not_ pick these up automatically.** An earlier
> draft of this plan said they did, on the grounds that V5 grants ADMIN
> everything except two named permissions and SUPER_ADMIN the whole catalogue.
> That is wrong, and it was confirmed wrong while building the meeting module.
>
> Those V5 statements are one-shot `INSERT … SELECT`s, evaluated once when V5
> ran. They are not views, triggers or rules, and there is no runtime superuser
> bypass — `UserPrincipal` builds its authorities strictly from
> `role.getPermissions()`. A permission inserted by a later migration therefore
> gets no `role_permissions` row for either role, and an administrator would
> get 403 on every endpoint in this module.
>
> `V17__seed_exam_permissions.sql` must end with an explicit grant:
>
> ```sql
> INSERT INTO role_permissions (role_id, permission_id)
> SELECT r.id, p.id FROM roles r JOIN permissions p
>   ON p.code IN ('EXAM_VIEW_SELF', 'EXAM_ATTEMPT', 'EXAM_MANAGE',
>                 'EXAM_QUESTION_IMPORT', 'EXAM_RESULT_VIEW_TEAM',
>                 'EXAM_RESULT_VIEW_ALL', 'EXAM_RESULT_EXPORT')
> WHERE r.code IN ('ADMIN', 'SUPER_ADMIN');
> ```
>
> `V17__seed_meeting_permissions.sql` carries the same block, with the same
> comment explaining why. **Every future permission migration needs it.**
>
> Verify it rather than assuming — after the migration runs:
>
> ```sql
> SELECT r.code, COUNT(*) FROM roles r
>   JOIN role_permissions rp ON rp.role_id = r.id
>   JOIN permissions p ON p.id = rp.permission_id
>  WHERE p.module = 'EXAM' GROUP BY r.code;
> ```
>
> A test that uses an HR principal will not catch this; only an ADMIN one will.

---

## 8. Lifecycle

```
HR creates exam (DRAFT)
  └─ uploads workbook
       ├─ validate-only pass → quality report + rejected rows
       └─ commit → QUESTIONS_LOADED
  └─ sets window, duration, marks, pass mark
  └─ PUBLISH
       ├─ roster snapshotted from exam_audience
       ├─ sets assigned round-robin across A / B / C
       └─ per-participant question and option order generated
  └─ window opens → OPEN
       ├─ employee starts: expires_at = min(now + duration, window_end)
       ├─ answers autosave per question, freely changeable until submit
       └─ submit → scored server-side → result + feedback shown immediately
  └─ window closes → CLOSED
       ├─ sweep auto-submits anything still open
       └─ anyone who never started becomes NOT_ATTEMPTED
  └─ HR opens a retake
       └─ child exam, same question bank, each failed/absent person assigned
          a set they have not seen
```

### Three things that need care

**Starting an attempt must be idempotent.** A double-click on Start must not create
two attempts. Unique constraint on `(participant_id, attempt_number)`, catch the
integrity violation, return the existing row — the same pattern as punch-in.

**The auto-submit sweep scores each attempt in its own `REQUIRES_NEW` transaction**,
so one malformed row cannot roll back everyone else's submission. Same reasoning as
the notification listener.

**Pass is decided on marks, not a rounded percentage.** With 5-mark questions every
score is a multiple of 5, so 8 correct = 40 = pass and 7 correct = 35 = fail. No
float rounding decides anyone's result.

### The retake rule

Feedback is shown immediately, so a retake on the same twenty questions would be
re-testing someone on answers they have already been given. The default is
therefore **a set the candidate has not seen**, enforced by the system rather than
left to HR to remember. With 60 questions in 3 sets there is exactly enough for one
clean retake per person.

`retake_uses_unseen_set` can be turned off if the intention is deliberate re-drilling
of the same material, which is a defensible study technique — but it should be a
conscious choice, not an accident.

Absentees join the retake cohort alongside those who failed. They have seen no
feedback, so any unused set works for them.

---

## 9. Services

```
service/exam/
  ExamService                 lifecycle, CRUD, duplicate-last-exam
  ExamQuestionImportService   POI reader, per-row errors, key cross-check
  ExamTemplateService         emits the blank workbook
  ExamQualityService          the pre-publish quality report
  ExamAssignmentService       roster snapshot, set allocation, retake sets
  ExamAttemptService          start / autosave / submit / auto-submit
  ExamScoringService          server-side scoring and feedback assembly
  ExamResultService           results, per-question analytics, re-score
  ExamSweepService            scheduled expiry and window-close sweep
```

### Scheduling

There is no recurrence engine. HR creates each exam. To keep that from becoming a
chore at 50–100 exams a year, `ExamService` offers **Duplicate last exam**: copies
title pattern, window offsets, duration, marks and audience, then asks for a new
workbook. This is much less machinery than a recurrence generator, and a wrong
recurrence rule silently creating or skipping exams for everyone is a bad failure
mode.

---

## 10. API surface

**Employee** (`EXAM_ATTEMPT`, identity from the JWT — never from the request):

```
GET  /api/v1/exams/me                    upcoming, open, completed
GET  /api/v1/exams/me/{id}               instructions, duration, marks
POST /api/v1/exams/me/{id}/attempt       start (idempotent)
GET  /api/v1/exams/me/attempts/{id}      the paper, NO key
PUT  /api/v1/exams/me/attempts/{id}/answers/{qid}   autosave
POST /api/v1/exams/me/attempts/{id}/submit
GET  /api/v1/exams/me/attempts/{id}/result          403 until submitted
```

**Manager** (`EXAM_RESULT_VIEW_TEAM`): team results for the reporting subtree.

**HR** (`EXAM_MANAGE`, `EXAM_QUESTION_IMPORT`, `EXAM_RESULT_VIEW_ALL`):
exam CRUD, template download, validate-only import, commit import, quality report,
publish, participant list with assigned sets, results, per-question analytics,
re-score, open retake, exports.

---

## 11. Frontend

| Role | Pages |
|---|---|
| Employee | `ExamsPage`, `ExamInstructionsPage`, `ExamAttemptPage`, `ExamResultPage` |
| Manager | `TeamExamResultsPage` |
| HR / Admin | `ExamListPage`, `ExamFormPage`, `ExamQuestionImportPage`, `ExamQuestionReviewPage`, `ExamResultsPage` |

All lazy-loaded under the existing `<Suspense>`, with sidebar entries gated on
permission.

- `ExamQuestionImportPage` mirrors `BulkImportPage` — same rejected-rows table, same
  per-row reasons. People already know that interaction.
- `ExamAttemptPage` needs a real mobile layout, not a responsive afterthought. Field
  sales staff will sit this on phones, and these questions punish misreading the
  options. Each paper is roughly 1,200 words of dense policy text; at a careful
  120 wpm that is 10 minutes of reading inside a 25-minute budget.
- `ExamResultPage` shows score, pass/fail, and per question: what they chose, the
  correct answer, the explanation and the Source reference.

---

## 12. Reporting

Three new `ReportType` values, which inherit Excel / CSV / PDF export from the
existing exporter:

- **`EXAM_RESULTS`** — per person: set, score, pass/fail, time taken
- **`EXAM_PARTICIPATION`** — sat / not attempted / in progress, by department
- **`EXAM_QUESTION_ANALYSIS`** — per question: attempts, correct rate, most-chosen
  wrong option, grouped by topic

The third is the one that matters. For a study exam, "31 of 40 people got the Reset
Benefit question wrong" is worth more than anyone's individual score — it names the
next training session.

---

## 13. Retention

Keep attempts and answers for **24 months**, as a configurable policy rather than a
hardcoded rule, consistent with how the rest of the platform handles policy.

The arithmetic: one person sitting one exam is 1 attempt row and 20 answer rows. At
two exams a week and 500 employees that is about 1,000,000 answer rows and roughly
150 MB a year — against the ~500,000 punch-event rows a year the platform already
stores without trouble.

Storage is not the constraint. Deleting answer rows after a few days would destroy
per-question analytics, which for a study exam is the primary output, and would also
remove the ability to re-score a mis-keyed question or let an employee review what
they got wrong last month.

What is safe to purge: abandoned in-progress attempts nobody submitted. If a hard
bound is ever needed, roll answers into per-question aggregates *before* deleting
the raw rows so the analytics survive.

The question bank is kept permanently. It is a few megabytes and it is the asset
being accumulated.

---

## 14. Testing

New integration tests, all against the H2 MySQL-mode suite, then verified against
real MySQL before the module is called done:

| Test | Covers |
|---|---|
| `ExamImportIT` | valid import, per-row rejections, key mismatch rejects the whole file, Excel type quirks, sheet-name variants |
| `ExamAttemptIT` | idempotent start, autosave, submit, expiry auto-submit, submission after expiry rejected |
| `ExamScoringIT` | scoring with shuffled options, marks-not-percentage pass boundary at 7 vs 8 correct, multi-select |
| `ExamSecurityIT` | **attempt payload contains no key**, employee B cannot read A's attempt or result, feedback 403 before submit |
| `ExamRetakeIT` | **a retake never assigns a set the candidate has seen**, absentees included, no third attempt |
| `ExamReportIT` | the three reports, export headers, tester exclusion from pass rates |

All nine tables must be added to `DatabaseCleaner`, which has a guard that names any
forgotten table — a lesson from a test that passed alone and failed in the suite.

---

## 15. Build order

| Step | Scope | Done when |
|---|---|---|
| 1 | Migrations, entities, enums, permissions | Flyway clean on real MySQL; `ddl-auto: validate` passes |
| 2 | Importer, template, quality report, question review screen | The corrected workbook imports; a file with a key mismatch is rejected whole |
| 3 | Exam CRUD, audience, publish, roster snapshot, set assignment | 60 people across 3 sets gives 20/20/20; every participant has a distinct option order |
| 4 | Attempt, autosave, timer, submit, auto-submit, scoring, feedback | The answer-key leakage test is red before the DTO split and green after |
| 5 | Results, retake, manager and HR views, 3 reports | Retake assigns an unseen set; verified end to end in a real browser against real MySQL |

Steps 1–4 are the module. Step 5 is what makes it usable every week.

Verification for every step follows the practice the earlier phases established:
real MySQL, the real browser, and the real production build command — not H2, not
curl, and not the preview pane alone.

---

## 16. Open items

- **Content supply.** 50–100 exams a year is 3,000–6,000 questions. The schema
  supports reuse (questions are tagged by topic and difficulty), but the current
  plan has HR uploading a fresh 60 each time. If authoring becomes the bottleneck,
  the next step is a persistent bank that exams draw from, filtered by topic and
  "not used in the last N weeks". Worth revisiting after about six weeks of real use.
- **Three duplicate questions** in the current corrected workbook (57 distinct
  across 60 slots) — flagged on its QA Report sheet, needs the source prospectuses
  to replace.
- **Domain review of the corrected workbook.** 180 distractors were reworded to
  remove the answer-pattern tells. Meaning was preserved mechanically, but someone
  who knows the products should read one set before it is used.
- **If results ever become appraisal input**, revisit: feedback timing, the sitting
  window, tester exclusion, and the audit depth around re-scoring.
