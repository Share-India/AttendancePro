# Build an Exam Module — implementation brief

Copy everything below the line into the other portal's assistant. Fill in section 0 first.

---

I need you to build an **exam module** into an existing application. Read this whole brief before
writing code, then tell me what you would change about the plan before you start.

## 0. My stack — FILL THIS IN BEFORE SENDING

```
Backend language / framework:
Database:
Migration tool:
Frontend framework:
Auth mechanism (how the logged-in user is identified):
Existing user / employee table name and primary key:
Existing roles or permissions mechanism:
Excel library already available (if any):
```

Fit the module to those. Everything below describes behaviour and data, not a specific stack.
Match the conventions already in the codebase — naming, layering, error handling, pagination — in
preference to anything implied by how I've written this brief.

---

## 1. What this is

A **weekly internal study exam** for staff product knowledge. Twice a week at most.

It is **not** appraisal input and does not feed performance ratings. It exists so people learn, and
so the training team can see what the organisation does not know. If that ever changes, several
decisions below need revisiting — I'll tell you if it does.

The flow:

1. HR creates an exam and uploads an Excel workbook of **60 questions split into 3 sets of 20**.
2. HR publishes it. Every employee is assigned **one set at random**.
3. Employees sit their set inside a time window.
4. On submit they immediately see their score and, for every question, the correct answer with an
   explanation and a source reference.
5. Anyone who fails or misses it gets **one retake** a few days later.

## 2. Fixed parameters

| Parameter | Value |
|---|---|
| Questions per paper | 20 |
| Sets per exam | 3 (A, B, C) from a 60-question upload |
| Set assignment | one per person, random, fixed at publish time |
| Marks per question | 5 |
| Total marks | 100 |
| Pass mark | 40 (8 correct) |
| Duration | 25 minutes |
| Negative marking | off — but the field must exist so it can be switched on |
| Feedback | shown immediately on submit |
| Attempts | 1, plus a retake if failed or not attempted |
| Audience | everyone, including HR and admins |
| Scheduling | HR creates each exam manually — **no recurrence engine** |
| Retention | keep attempts and answers 24 months |

**Store all of these as configuration on the exam record. Do not hardcode any of them.** The numbers
above are today's policy, not a law of nature.

## 3. Non-negotiable rules

These are the six things that, if you get them wrong, break the module in ways that are hard to
notice until it matters.

### 3.1 The answer key must never reach the browser during an attempt

Not in the JSON, not in a hidden field, not in a lazily-loaded sub-resource, not inferable from an
`isCorrect` flag someone forgot to strip.

Enforce it structurally, not by care:

- **Two separate, disjoint response types.** One for sitting the exam (question text and options
  only — no correct flag, no explanation, no source). One for HR review (everything). Never a single
  type with fields nulled out on the way past: a null-out skipped once is a total and silent
  compromise.
- Feedback comes from a **separate endpoint** that refuses with 403 until that attempt is submitted.
- Write a test that walks the entire attempt response JSON tree and asserts no key matching
  `correct`, `isCorrect`, `answer`, `explanation` or `source` exists anywhere in it.

### 3.2 Scoring happens on the server, always

The client submits which options were chosen. Never a score, never a correctness flag. Recompute
from the stored answer key on submit.

### 3.3 The timer is server-held

Store `expires_at` on the attempt when it starts:
`expires_at = min(started_at + duration, window_end)`.

The browser clock is display only. A submission arriving after `expires_at` is rejected and the
attempt is scored as it stood at expiry. Do not trust a client-supplied elapsed time.

### 3.4 Shuffle options per participant, and store the permutation

Each person sees the four options in a different order. This permanently neutralises answer-position
bias in every future question file, including ones nobody proofreads — see section 7 for why that
matters more than it sounds.

**The permutation must be persisted on the attempt.** Without it you cannot score the submission,
cannot show the candidate the paper they actually sat, and cannot answer a disputed result.

Translate the candidate's clicked labels back to the canonical labels from the question bank **once,
on the way in**, and store canonical labels. Then scoring and analytics never have to unshuffle.

### 3.5 A retake must use a set the candidate has not seen

This one is easy to miss. Feedback is shown immediately on submit. So if the retake reuses the same
20 questions, **you have handed the failing candidate the answer key and then re-tested them on it.**
Everyone passes and the retake measures nothing.

With 60 questions in 3 sets there is exactly enough for one clean retake per person. Enforce it in
code — do not rely on whoever sets up the retake remembering.

People who never sat the original join the retake cohort too. They have seen no feedback, so any
unused set works for them.

### 3.6 Pass is decided on marks, never a rounded percentage

With 5-mark questions every score is a multiple of 5. 8 correct = 40 = pass. 7 correct = 35 = fail.
Compare integer marks against the stored pass mark. Do not let a float percentage and a rounding
rule decide whether someone passed.

## 4. The Excel upload format

One workbook per exam. Read columns **by header name**, not by position — column order must not
matter.

**Sheets:** `Set A`, `Set A Answer Key`, `Set B`, `Set B Answer Key`, `Set C`, `Set C Answer Key`,
and an optional `Consolidated Answer Key`.

**Columns in each `Set X` sheet:**

| Column | Required | Notes |
|---|---|---|
| Q. No. | yes | unique within the sheet |
| Product | yes | topic tag — keep it, it drives per-topic analytics |
| Question | yes | the question stem |
| Option A | yes | |
| Option B | yes | |
| Option C | no | blank means a 2- or 3-option question |
| Option D | no | |
| Correct Answer | yes | `A`–`D`, or `A,C` for multi-select |
| Explanation | yes | shown to the candidate after submission |
| Source | no | citation shown alongside the explanation |
| Marks | no | falls back to the exam's marks-per-question |
| Difficulty | no | `EASY` / `MODERATE` / `HARD` |

**Use the answer-key sheets as a cross-check.** They duplicate the Correct Answer column, which looks
redundant — but if a Set sheet and its key sheet disagree, **reject the entire upload**. The day
someone corrects an answer in one place and not the other, the import must stop rather than silently
pick one.

### Import behaviour

- **Offer a downloadable blank template.** Most import pain comes from people guessing at column
  names. Generate the exact file you accept.
- **Offer a validate-only dry run.** Parse, report every problem, write nothing. Upload → see errors
  → fix → re-upload, with no half-loaded question bank.
- **Report errors per row**, naming the sheet and row number and what was wrong. Never fail the
  whole file with one generic message, and never silently skip a bad row.

### Excel quirks that will bite you

Each is one line of defence and one test:

- `Q. No.` arrives as a float (`1.0`) not an integer
- `Correct Answer` arrives lowercase, or padded with spaces
- headers carry trailing spaces or non-breaking spaces pasted from Word
- merged cells; formula cells (read the cached value); blank trailing rows Excel invents
- `Explanation` longer than 255 characters — use a long-text column type
- sheet-name variants (`Set-A`, `SET A `, `Sheet1`). Match tolerantly, but if you cannot resolve a
  sheet, **reject with a readable message naming what you found and what you expected**. Never skip
  a sheet silently — importing 2 of 3 sets and saying nothing is the worst outcome.
- the database and the whole request path must be UTF-8 (`utf8mb4` on MySQL) — question text
  contains `₹` and typographic dashes

### Do not store the uploaded file

It is an answer key, and an answer key sitting on disk is an answer key waiting to leak. Store the
filename, a SHA-256 of the contents, who uploaded it, when, the row counts, and the quality report
(section 7). Parse the questions into the database and discard the file.

## 5. Data model

Nine tables. Adapt names and types to the existing schema conventions.

**`exams`** — code, title, description, instructions, status, window start, window end, duration
minutes, marks per question, negative marks, total marks, pass marks, shuffle questions flag,
shuffle options flag, feedback visibility, parent exam id (set on a retake, points at the original),
"retake uses unseen set" flag, published at, closed at, audit columns.
Constraints: window end after window start; pass marks between 0 and total marks.

**`exam_question_sets`** — exam id, set code, question count, total marks. Unique (exam, set code).

**`exam_questions`** — set id, question number, topic, difficulty, question type, question text,
marks, explanation, source reference. Unique (set, question number).

**`exam_question_options`** — question id, option label, option text, is correct.
Unique (question, label).

**`exam_audience`** — exam id, scope type, scope id. A `GLOBAL` scope with no id means everyone.

**`exam_participants`** — exam id, employee id, assigned set id, participant kind, status.
Unique (exam, employee).

> **Snapshot the roster at publish. Do not compute it on read.** Someone who joins on Wednesday must
> not appear in Monday's participation figure, and last week's numbers must not shift every time the
> org chart changes.
>
> `participant_kind` is `CANDIDATE` or `TESTER`. HR and admins sit the paper having already seen the
> answers, so they stay on the roster but are **excluded from pass rates and averages by default**,
> with a toggle to include them.

**`exam_attempts`** — participant id, attempt number, question order, option order (the per-person
permutation — see 3.4), started at, expires at, submitted at, submission source
(`SELF` / `AUTO_TIMEOUT` / `AUTO_WINDOW_CLOSE`), score, max score, percentage, passed.
Unique (participant, attempt number).

**`exam_answers`** — attempt id, question id, selected options (canonical labels), is correct,
marks awarded, answered at. Unique (attempt, question).

> **This table is the record of what happened; the score on the attempt is derived from it.** If a
> question turns out to be wrongly keyed in November, you re-score every past attempt from these rows
> instead of asking two hundred people to sit it again. Never store only the total.

**`exam_uploads`** — exam id, file name, SHA-256, file size, question count, set count, rejected
count, quality report, uploaded by, uploaded at.

**Indexes:** participants by (exam, status) and by (employee, status); attempts by
(submitted at, expires at) for the expiry sweep; answers by (question, is correct) for analytics.

## 6. Lifecycle

```
HR creates exam                       → DRAFT
  uploads workbook, validate-only     → quality report + rejected rows
  uploads workbook, commit            → QUESTIONS_LOADED
  sets window, duration, marks
  publishes                           → PUBLISHED
      roster snapshotted from audience
      sets assigned round-robin across A / B / C
      per-participant question and option order generated
  window opens                        → OPEN
      employee starts  → expires_at = min(now + duration, window_end)
      answers autosave per question, changeable until submit
      submit → scored server-side → result and feedback shown immediately
  window closes                       → CLOSED
      sweep auto-submits anything still open
      anyone who never started        → NOT_ATTEMPTED
  HR opens a retake
      child exam, same question bank
      each failed / absent person assigned a set they have not seen
```

Three implementation points:

1. **Starting an attempt must be idempotent.** A double-click on Start must not create two attempts.
   Put a unique constraint on (participant, attempt number), catch the violation, and return the
   existing attempt.
2. **The expiry sweep must score each attempt in its own transaction.** One malformed row must not
   roll back everyone else's submission.
3. **Autosave each answer as it is given.** Someone forty minutes into a test must not lose it to a
   refresh or a dropped connection. Make the attempt resumable.

## 7. Question quality — build a pre-publish check

This is not optional polish. Here is why.

The first real 60-question workbook for this system was analysed before any code existed. It could be
passed at **92%** by ticking B for every question without reading anything — one set was B for all
twenty. It could be passed at **80%** by always choosing the longest option. Wrong options contained
an absolute word ("always", "never", "only") **59%** of the time versus **7%** for correct options,
so "eliminate the absolutes, pick the cautious one" also beat it.

The exam would have run perfectly. The pass rate would have looked excellent. It would have measured
nothing.

**Question quality fails silently.** So after import and before publish, show a quality report:

- answer distribution per set across A / B / C / D (target: roughly even)
- how often the correct option is also the longest option (target: around 25%)
- absolute-word rate in wrong options versus correct options (target: similar)
- duplicate questions within and across sets
- questions whose options differ wildly in length

These are **warnings, not errors** — HR can publish anyway. But nobody gets to publish a paper with
twenty B answers without having been told.

Per-participant option shuffling (3.4) is the structural defence that works even when nobody reads
the report.

## 8. Screens

**Employee**
- exam list: upcoming, open now, completed
- instructions page: duration, marks, pass mark, number of questions, rules
- attempt page: timer, question palette, free navigation, autosave indicator
- result page: score, pass/fail, and per question — what they chose, the correct answer, the
  explanation, the source

**Manager** — results for their team

**HR / Admin** — exam list; create and edit; template download; validate-only import; quality report;
commit import; question review (all 3 sets with keys); publish; participant list showing assigned
sets; results; per-question analytics; open retake; exports

**The attempt page needs a real mobile layout, not a responsive afterthought.** Staff will sit this on
phones. Each paper is roughly 1,200 words of dense text; at a careful 120 wpm that is 10 minutes of
reading inside a 25-minute budget, and these questions punish misreading the options.

## 9. Reporting

Three reports, exportable to CSV / Excel / PDF:

- **Results** — per person: assigned set, score, pass/fail, time taken
- **Participation** — sat / in progress / not attempted, by department
- **Question analysis** — per question: attempts, correct rate, most-chosen wrong option, grouped by
  topic

**The third is the most important one.** For a study exam, "31 of 40 people got this question wrong"
is worth more than anyone's individual score — it names the next training session. Build it properly,
not as an afterthought.

## 10. Tests that must exist

| Test | Covers |
|---|---|
| Import | valid import; per-row rejections; **key mismatch rejects the whole file**; the Excel quirks in section 4; sheet-name variants |
| Attempt | idempotent start; autosave; submit; expiry auto-submit; submit after expiry rejected; resume after disconnect |
| Scoring | scoring with shuffled options; the pass boundary at exactly 7 vs 8 correct; multi-select |
| Security | **attempt payload contains no answer key**; user B cannot read user A's attempt or result; feedback endpoint 403s before submit |
| Retake | **a retake never assigns a set the candidate has already seen**; absentees included; no third attempt |
| Reports | the three reports; export headers; testers excluded from pass rates |

The two in bold are the ones that would otherwise fail silently in production.

## 11. Build order

1. Schema, entities, permissions — verified by the migration running clean against a real database
2. Importer, template, quality report, question review screen — verified by a good file importing and
   a key-mismatched file being rejected whole
3. Exam CRUD, audience, publish, roster snapshot, set assignment — verified by 60 people splitting
   20/20/20 with distinct option orders
4. Attempt, autosave, timer, submit, auto-submit, scoring, feedback — verified by the answer-key
   leakage test being red before the response-type split and green after
5. Results, retake, manager and HR views, the three reports — verified end to end in a real browser

Steps 1–4 are the module. Step 5 is what makes it usable every week.

## 12. Things I already know will be a problem

- **Content supply is the real bottleneck, not the code.** 50–100 exams a year is 3,000–6,000
  questions. Tag every question by topic and difficulty from day one so a reusable bank is possible
  later, even though the current flow is a fresh upload each time.
- **Retention.** Keep attempts and answers for 24 months, as a setting. Do not purge answer rows
  early to save space — one person sitting one exam is about 21 rows, so 500 people twice a week is
  roughly 150 MB a year, and those rows are what the question analytics are computed from.
- **An unproctored web exam cannot be made cheat-proof.** Don't try. Skip tab-switch detection and
  copy-paste blocking — they irritate honest people, break on mobile, and are bypassed with a second
  device. Since this is for learning, someone copying an answer is only cheating themselves.

---

Before you write any code: tell me which parts of this don't fit the existing codebase, anything you
think is wrong, and what you'd do differently.
