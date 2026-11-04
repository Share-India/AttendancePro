import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';

import { Alert } from '../../components/common/Alert';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { TextField } from '../../components/form/TextField';
import { ROUTES } from '../../constants/routes';
import { useAuth } from '../../features/auth/useAuth';
import { examApi } from '../../features/exam/examApi';
import { PERMISSIONS } from '../../constants/permissions';
import { ApiError } from '../../types/api';
import {
  EXAM_STATUS_LABEL,
  EXAM_STATUS_TONE,
  PARTICIPANT_STATUS_LABEL,
  PARTICIPANT_STATUS_TONE,
  SUBMISSION_LABEL,
  type Exam,
  type ImportResult,
} from '../../types/exam';

type Tab = 'upload' | 'questions' | 'roster' | 'analysis';

const whenLabel = (iso: string): string =>
  new Date(iso).toLocaleString(undefined, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });

/**
 * A rate, or a dash.
 *
 * <p>Loose null check on purpose: the API omits null fields rather than
 * sending them, so a rate the server could not compute arrives as undefined
 * and a strict test renders the literal text "undefined%". The meeting module
 * shipped the same bug once as "~undefined".
 */
const pct = (value: string | null | undefined): string =>
  value == null ? '—' : `${value}%`;

export const ExamDetailPage = () => {
  const { id } = useParams<{ id: string }>();
  const examId = Number(id);
  const { hasPermission } = useAuth();
  const queryClient = useQueryClient();

  const canImport = hasPermission(PERMISSIONS.EXAM_QUESTION_IMPORT);
  const canManage = hasPermission(PERMISSIONS.EXAM_MANAGE);

  const [tab, setTab] = useState<Tab>('upload');
  const [notice, setNotice] = useState<string | null>(null);

  const exam = useQuery({
    queryKey: ['exam', 'detail', examId],
    queryFn: () => examApi.get(examId),
  });

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['exam'] });

  const publish = useMutation({
    mutationFn: () => examApi.publish(examId),
    onSuccess: (result) => {
      const split = Object.entries(result.perSet)
        .map(([set, count]) => `${set}: ${count}`)
        .join(', ');
      setNotice(`${result.message} Split — ${split}.`);
      refresh();
    },
  });

  if (exam.isLoading) {
    return (
      <div className="card flex items-center justify-center p-xl">
        <Spinner />
      </div>
    );
  }

  if (exam.error || !exam.data) {
    return (
      <Alert variant="error" title="Could not load that exam">
        {exam.error instanceof ApiError ? exam.error.message : 'Try again from the exam list.'}
      </Alert>
    );
  }

  const data = exam.data;
  const tabs: { key: Tab; label: string; hidden?: boolean }[] = [
    { key: 'upload', label: 'Questions upload', hidden: !canImport || !data.canImportQuestions },
    { key: 'questions', label: 'The paper', hidden: !canImport },
    { key: 'roster', label: 'Roster and results' },
    { key: 'analysis', label: 'Analysis' },
  ];
  const visibleTabs = tabs.filter((entry) => !entry.hidden);
  const activeTab = visibleTabs.some((entry) => entry.key === tab) ? tab : visibleTabs[0].key;

  return (
    <div className="flex flex-col gap-lg">
      <PageHeader
        title={data.title}
        description={`${data.examCode}${data.retake ? ` · retake of ${data.parentExamCode}` : ''}`}
        actions={
          <div className="flex flex-wrap gap-sm">
            <Link to={ROUTES.examsManage} className="btn-ghost">
              All exams
            </Link>
            {data.canEdit && canManage ? (
              <Link to={ROUTES.examEdit(data.id)} className="btn-ghost">
                Edit
              </Link>
            ) : null}
            {canManage && data.status === 'QUESTIONS_LOADED' ? (
              <button
                type="button"
                className="btn-primary inline-flex items-center gap-2"
                disabled={!data.canPublish || publish.isPending}
                onClick={() => {
                  setNotice(null);
                  publish.mutate();
                }}
              >
                {publish.isPending ? <Spinner className="h-4 w-4" /> : null}
                Publish
              </button>
            ) : null}
          </div>
        }
      />

      {notice ? <Alert variant="success">{notice}</Alert> : null}
      {publish.error ? (
        <Alert variant="error" title="Could not publish">
          {publish.error instanceof ApiError ? publish.error.message : 'Try again.'}
        </Alert>
      ) : null}

      <section className="card flex flex-wrap items-center gap-lg p-lg">
        <div>
          <p className="field-label">Status</p>
          <StatusChip
            label={EXAM_STATUS_LABEL[data.status] ?? data.status}
            tone={EXAM_STATUS_TONE[data.status] ?? 'neutral'}
          />
        </div>
        <div>
          <p className="field-label">Window</p>
          <p className="text-body-md">
            {whenLabel(data.windowStart)} → {whenLabel(data.windowEnd)}
          </p>
        </div>
        <div>
          <p className="field-label">Paper</p>
          <p className="text-body-md tabular-nums">
            {data.sets.length === 0
              ? 'No questions yet'
              : `${data.sets.length} sets · ${data.sets[0].questionCount} questions · ${data.sets[0].totalMarks} marks`}
          </p>
        </div>
        <div>
          <p className="field-label">Pass mark</p>
          <p className="text-body-md tabular-nums">{data.passMarks}</p>
        </div>
        <div>
          <p className="field-label">Roster</p>
          <p className="text-body-md tabular-nums">{data.rosterSize}</p>
        </div>
        <div>
          <p className="field-label">Audience</p>
          <p className="text-body-md">
            {data.audience.map((rule) => rule.scopeName).join(', ') || 'Nobody chosen'}
          </p>
        </div>
      </section>

      {/*
        Only while it could still be published. The server computes the list
        unconditionally, so an exam that is already open reports "it is open
        rather than ready to publish" — true, and useless to read.
      */}
      {data.publishBlockers.length > 0 &&
      (data.status === 'DRAFT' || data.status === 'QUESTIONS_LOADED') ? (
        <Alert variant="warning" title="Not ready to publish">
          <ul className="ml-md list-disc">
            {data.publishBlockers.map((blocker) => (
              <li key={blocker}>{blocker}</li>
            ))}
          </ul>
        </Alert>
      ) : null}

      {data.cancellationReason ? (
        <Alert variant="error" title="Cancelled">
          {data.cancellationReason}
        </Alert>
      ) : null}

      <nav className="flex gap-1 overflow-x-auto border-b border-outline-variant">
        {visibleTabs.map((entry) => (
          <button
            key={entry.key}
            type="button"
            className={`whitespace-nowrap px-md py-sm text-label-lg ${
              activeTab === entry.key
                ? 'border-b-2 border-primary text-primary'
                : 'text-on-surface-variant hover:text-on-surface'
            }`}
            onClick={() => setTab(entry.key)}
          >
            {entry.label}
          </button>
        ))}
      </nav>

      {activeTab === 'upload' ? <UploadPanel exam={data} onLoaded={refresh} /> : null}
      {activeTab === 'questions' ? <QuestionsPanel examId={examId} /> : null}
      {activeTab === 'roster' ? <RosterPanel examId={examId} /> : null}
      {activeTab === 'analysis' ? (
        <AnalysisPanel examId={examId} canManage={canManage} exam={data} onOpened={refresh} />
      ) : null}
    </div>
  );
};

// ---------------------------------------------------------------- the upload

const UploadPanel = ({ exam, onLoaded }: { exam: Exam; onLoaded: () => void }) => {
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);

  const upload = useMutation({
    mutationFn: (input: { file: File; commit: boolean }) =>
      examApi.importWorkbook(exam.id, input.file, input.commit),
    onSuccess: (imported) => {
      setResult(imported);
      if (imported.committed) {
        onLoaded();
      }
    },
  });

  return (
    <div className="flex flex-col gap-md">
      <Alert variant="info" title="How this works">
        The workbook needs a <strong>Set A</strong>, <strong>Set B</strong> and{' '}
        <strong>Set C</strong> sheet, each with its own answer key sheet. Check it first and
        nothing is written; if the key sheets disagree with the questions, the whole file is
        refused rather than one version being picked.
      </Alert>

      <div className="card flex flex-col gap-md p-lg">
        <div className="flex flex-wrap items-end gap-md">
          <div className="min-w-0 flex-1">
            <label className="field-label" htmlFor="workbook">
              Question workbook
            </label>
            <input
              id="workbook"
              type="file"
              accept=".xlsx,.xls"
              className="field-input"
              onChange={(event) => {
                setFile(event.target.files?.[0] ?? null);
                setResult(null);
              }}
            />
          </div>
          <button
            type="button"
            className="btn-ghost"
            onClick={() => void examApi.downloadTemplate()}
          >
            Download the blank template
          </button>
        </div>

        <div className="flex flex-wrap justify-end gap-sm">
          <button
            type="button"
            className="btn-secondary inline-flex items-center gap-2"
            disabled={!file || upload.isPending}
            onClick={() => file && upload.mutate({ file, commit: false })}
          >
            {upload.isPending ? <Spinner className="h-4 w-4" /> : null}
            Check it
          </button>
          <button
            type="button"
            className="btn-primary inline-flex items-center gap-2"
            disabled={!file || upload.isPending}
            onClick={() => file && upload.mutate({ file, commit: true })}
          >
            {upload.isPending ? <Spinner className="h-4 w-4" /> : null}
            Load the questions
          </button>
        </div>
      </div>

      {upload.error ? (
        <Alert variant="error" title="Could not read that file">
          {upload.error instanceof ApiError ? upload.error.message : 'Try again.'}
        </Alert>
      ) : null}

      {result ? (
        <>
          <Alert
            variant={result.committed ? 'success' : result.accepted ? 'info' : 'error'}
            title={
              result.committed
                ? 'Questions loaded'
                : result.accepted
                  ? 'Nothing written yet'
                  : 'Nothing was written'
            }
          >
            {result.message}
          </Alert>

          {result.sets.length > 0 ? (
            <div className="card p-lg">
              <p className="field-label">What was found</p>
              <ul className="mt-sm flex flex-wrap gap-lg text-body-md tabular-nums">
                {result.sets.map((set) => (
                  <li key={set.setCode}>
                    Set {set.setCode}: {set.questionCount} questions, {set.totalMarks} marks
                  </li>
                ))}
              </ul>
            </div>
          ) : null}

          {result.problems.length > 0 ? (
            <div className="card overflow-x-auto p-lg">
              <p className="field-label">
                {result.problems.length} problem(s) — each named by where it is
              </p>
              <table className="mt-sm w-full text-body-sm">
                <thead className="text-left text-on-surface-variant">
                  <tr>
                    <th className="pb-1 pr-md">Sheet</th>
                    <th className="pb-1 pr-md">Row</th>
                    <th className="pb-1 pr-md">Column</th>
                    <th className="pb-1">What is wrong</th>
                  </tr>
                </thead>
                <tbody>
                  {result.problems.map((problem, index) => (
                    <tr key={index} className="border-t border-outline-variant">
                      <td className="py-1 pr-md">{problem.sheet ?? '—'}</td>
                      <td className="py-1 pr-md tabular-nums">{problem.row ?? '—'}</td>
                      <td className="py-1 pr-md">{problem.column ?? '—'}</td>
                      <td className="py-1">{problem.message}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}

          {result.quality ? <QualityPanel quality={result.quality} /> : null}
        </>
      ) : null}
    </div>
  );
};

/**
 * The quality report.
 *
 * <p>Everything here is a warning rather than a refusal. The first real
 * workbook written for this system could be passed at 92% by ticking B twenty
 * times — it would have run perfectly and measured nothing — so the point of
 * this panel is that nobody publishes such a paper without having been told.
 */
const QualityPanel = ({ quality }: { quality: NonNullable<ImportResult['quality']> }) => (
  <div className="flex flex-col gap-md">
    <Alert
      variant={quality.clean ? 'success' : 'warning'}
      title={quality.clean ? 'Nothing obviously beatable' : 'Worth a look before you publish'}
    >
      {quality.clean
        ? 'The answers are spread evenly, no option stands out by length, and no question appears twice.'
        : 'These are warnings, not errors — you can publish anyway. But a paper that can be passed without reading it measures nothing.'}
    </Alert>

    {quality.warnings.map((warning) => (
      <Alert key={warning} variant="warning">
        {warning}
      </Alert>
    ))}

    {quality.duplicateQuestions.length > 0 ? (
      <div className="card p-lg">
        <p className="field-label">The same question in more than one place</p>
        <ul className="mt-sm ml-md list-disc text-body-md">
          {quality.duplicateQuestions.map((entry) => (
            <li key={entry}>{entry}</li>
          ))}
        </ul>
      </div>
    ) : null}

    <div className="grid gap-md lg:grid-cols-3">
      {quality.sets.map((set) => (
        <div key={set.setCode} className="card flex flex-col gap-sm p-lg">
          <p className="text-title-sm">
            Set {set.setCode}
            <span className="text-body-sm text-on-surface-variant">
              {' '}
              · {set.questionCount} questions
            </span>
          </p>

          <div>
            <p className="field-label">Answer spread</p>
            <ul className="flex flex-wrap gap-sm text-body-md tabular-nums">
              {Object.entries(set.answerSpread).map(([label, count]) => (
                <li key={label} className="rounded-md bg-surface-variant px-sm">
                  {label}: {count}
                </li>
              ))}
            </ul>
          </div>

          <dl className="grid grid-cols-2 gap-x-md gap-y-1 text-body-sm">
            <dt className="text-on-surface-variant">Correct is longest</dt>
            <dd className="tabular-nums">{Math.round(set.correctIsLongestRate * 100)}%</dd>
            <dt className="text-on-surface-variant">&ldquo;Always&rdquo; in wrong options</dt>
            <dd className="tabular-nums">{Math.round(set.absoluteRateWrong * 100)}%</dd>
            <dt className="text-on-surface-variant">…in correct ones</dt>
            <dd className="tabular-nums">{Math.round(set.absoluteRateCorrect * 100)}%</dd>
          </dl>

          {set.warnings.length > 0 ? (
            <ul className="ml-md list-disc text-body-sm text-on-surface-variant">
              {set.warnings.map((warning) => (
                <li key={warning}>{warning}</li>
              ))}
            </ul>
          ) : (
            <p className="text-body-sm text-on-surface-variant">Nothing to flag.</p>
          )}
        </div>
      ))}
    </div>
  </div>
);

// --------------------------------------------------------------- the paper

const QuestionsPanel = ({ examId }: { examId: number }) => {
  const { data, isLoading, error } = useQuery({
    queryKey: ['exam', 'questions', examId],
    queryFn: () => examApi.questions(examId),
  });

  if (isLoading) {
    return (
      <div className="card flex items-center justify-center p-xl">
        <Spinner />
      </div>
    );
  }

  if (error) {
    return (
      <Alert variant="error" title="Could not load the paper">
        {error instanceof ApiError ? error.message : 'Try again.'}
      </Alert>
    );
  }

  const sets = data ?? [];
  if (sets.length === 0) {
    return (
      <div className="card p-xl text-center">
        <p className="text-title-md">No questions loaded yet</p>
        <p className="mt-1 text-body-md text-on-surface-variant">
          Upload the workbook and they will appear here for proof-reading.
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-lg">
      <Alert variant="warning" title="This is the answer key">
        Everything below includes the correct answers. Nobody sitting the exam can reach this
        screen, but it is still worth not reading it over somebody&rsquo;s shoulder.
      </Alert>

      {sets.map((set) => (
        <section key={set.setCode} className="flex flex-col gap-sm">
          <h2 className="text-title-md">
            Set {set.setCode}
            <span className="text-body-md text-on-surface-variant">
              {' '}
              · {set.questionCount} questions · {set.totalMarks} marks
            </span>
          </h2>

          {set.questions.map((question) => (
            <article key={question.id} className="card flex flex-col gap-sm p-lg">
              <div className="flex items-baseline justify-between gap-sm">
                <span className="text-body-sm text-on-surface-variant">
                  Q{question.questionNumber}
                  {question.topic ? ` · ${question.topic}` : ''}
                  {question.difficulty ? ` · ${question.difficulty.toLowerCase()}` : ''}
                </span>
                <span className="shrink-0 text-body-sm tabular-nums">{question.marks} marks</span>
              </div>

              <p className="text-body-lg">{question.questionText}</p>

              <ul className="flex flex-col gap-1">
                {question.options.map((option) => (
                  <li
                    key={option.label}
                    className={`flex items-start gap-sm rounded-md border p-sm text-body-md ${
                      option.correct
                        ? 'border-success bg-success-container'
                        : 'border-outline-variant'
                    }`}
                  >
                    <span className="font-medium">{option.label}.</span>
                    <span className="flex-1">{option.text}</span>
                    {option.correct ? <span className="shrink-0 text-body-sm">Correct</span> : null}
                  </li>
                ))}
              </ul>

              {question.explanation ? (
                <div className="rounded-md bg-surface-variant p-sm">
                  <p className="field-label">Explanation shown afterwards</p>
                  <p className="text-body-md">{question.explanation}</p>
                  {question.sourceReference ? (
                    <p className="mt-1 text-body-sm text-on-surface-variant">
                      Source: {question.sourceReference}
                    </p>
                  ) : null}
                </div>
              ) : null}
            </article>
          ))}
        </section>
      ))}
    </div>
  );
};

// -------------------------------------------------------------- the roster

const RosterPanel = ({ examId }: { examId: number }) => {
  const { data, isLoading, error } = useQuery({
    queryKey: ['exam', 'participants', examId],
    queryFn: () => examApi.participants(examId),
  });

  if (isLoading) {
    return (
      <div className="card flex items-center justify-center p-xl">
        <Spinner />
      </div>
    );
  }

  if (error) {
    return (
      <Alert variant="error" title="Could not load the roster">
        {error instanceof ApiError ? error.message : 'Try again.'}
      </Alert>
    );
  }

  const rows = data ?? [];
  if (rows.length === 0) {
    return (
      <div className="card p-xl text-center">
        <p className="text-title-md">Nobody on the roster</p>
        <p className="mt-1 text-body-md text-on-surface-variant">
          Publish the exam to deal the papers and freeze who is on the list.
        </p>
      </div>
    );
  }

  const sat = rows.filter((row) => row.status === 'SUBMITTED');
  const candidates = sat.filter((row) => row.participantKind === 'CANDIDATE');
  const passed = candidates.filter((row) => row.passed === true).length;

  return (
    <div className="flex flex-col gap-md">
      <div className="grid gap-md sm:grid-cols-4">
        {[
          { label: 'On the roster', value: rows.length },
          { label: 'Sat it', value: sat.length },
          { label: 'Passed', value: passed },
          { label: 'Did not sit it', value: rows.filter((r) => r.status !== 'SUBMITTED').length },
        ].map((stat) => (
          <div key={stat.label} className="card p-lg">
            <p className="field-label">{stat.label}</p>
            <p className="text-headline-md tabular-nums">{stat.value}</p>
          </div>
        ))}
      </div>

      <div className="card overflow-x-auto p-lg">
        <table className="w-full text-body-sm">
          <thead className="text-left text-on-surface-variant">
            <tr>
              <th className="pb-2 pr-md">Employee</th>
              <th className="pb-2 pr-md">Set</th>
              <th className="pb-2 pr-md">Outcome</th>
              <th className="pb-2 pr-md text-right">Score</th>
              <th className="pb-2 pr-md">Result</th>
              <th className="pb-2 pr-md text-right">Minutes</th>
              <th className="pb-2">Handed in by</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.participantId} className="border-t border-outline-variant">
                <td className="py-2 pr-md">
                  <p className="font-medium">{row.employeeName}</p>
                  <p className="text-on-surface-variant">
                    {row.employeeCode}
                    {row.departmentName ? ` · ${row.departmentName}` : ''}
                    {row.participantKind === 'TESTER' ? ' · set the paper' : ''}
                  </p>
                </td>
                <td className="py-2 pr-md">{row.setCode}</td>
                <td className="py-2 pr-md">
                  <StatusChip
                    label={PARTICIPANT_STATUS_LABEL[row.status] ?? row.status}
                    tone={PARTICIPANT_STATUS_TONE[row.status] ?? 'neutral'}
                  />
                </td>
                <td className="py-2 pr-md text-right tabular-nums">
                  {/* Loose null check: the API omits null fields entirely. */}
                  {row.score == null ? '—' : `${row.score} / ${row.maxScore}`}
                </td>
                <td className="py-2 pr-md">
                  {row.passed == null ? (
                    '—'
                  ) : (
                    <StatusChip
                      label={row.passed ? 'Passed' : 'Did not pass'}
                      tone={row.passed ? 'success' : 'warning'}
                    />
                  )}
                </td>
                <td className="py-2 pr-md text-right tabular-nums">
                  {row.secondsTaken == null ? '—' : Math.round(row.secondsTaken / 60)}
                </td>
                <td className="py-2">
                  {row.submissionSource == null
                    ? '—'
                    : SUBMISSION_LABEL[row.submissionSource] ?? row.submissionSource}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};

// ------------------------------------------------------------- the analysis

const AnalysisPanel = ({
  examId,
  canManage,
  exam,
  onOpened,
}: {
  examId: number;
  canManage: boolean;
  exam: Exam;
  onOpened: () => void;
}) => {
  const [includeTesters, setIncludeTesters] = useState(false);
  const [retaking, setRetaking] = useState(false);
  const [retakeNotice, setRetakeNotice] = useState<string | null>(null);
  const [windowStart, setWindowStart] = useState('');
  const [windowEnd, setWindowEnd] = useState('');

  const { data, isLoading, error } = useQuery({
    queryKey: ['exam', 'analytics', examId, includeTesters],
    queryFn: () => examApi.analytics(examId, includeTesters),
  });

  const retake = useMutation({
    mutationFn: () =>
      examApi.retake(examId, {
        windowStart: new Date(windowStart).toISOString(),
        windowEnd: new Date(windowEnd).toISOString(),
      }),
    onSuccess: (result) => {
      setRetakeNotice(result.message);
      setRetaking(false);
      onOpened();
    },
  });

  if (isLoading) {
    return (
      <div className="card flex items-center justify-center p-xl">
        <Spinner />
      </div>
    );
  }

  if (error || !data) {
    return (
      <Alert variant="error" title="Could not load the analysis">
        {error instanceof ApiError ? error.message : 'Try again.'}
      </Alert>
    );
  }

  return (
    <div className="flex flex-col gap-md">
      {retakeNotice ? <Alert variant="success">{retakeNotice}</Alert> : null}
      {retake.error ? (
        <Alert variant="error" title="Could not open a retake">
          {retake.error instanceof ApiError ? retake.error.message : 'Try again.'}
        </Alert>
      ) : null}

      <div className="flex flex-wrap items-center justify-between gap-sm">
        <label className="flex items-center gap-sm text-body-md">
          <input
            type="checkbox"
            checked={includeTesters}
            onChange={(event) => setIncludeTesters(event.target.checked)}
          />
          <span>
            Include the {data.testerCount} who set the paper
            <span className="block text-body-sm text-on-surface-variant">
              They sat it having read the answers, so they are left out by default.
            </span>
          </span>
        </label>

        {canManage && exam.status === 'CLOSED' && !exam.retake ? (
          <button type="button" className="btn-secondary" onClick={() => setRetaking(true)}>
            Open a retake
          </button>
        ) : null}
      </div>

      <div className="grid gap-md sm:grid-cols-3 lg:grid-cols-6">
        {[
          { label: 'Sat it', value: String(data.submittedCount) },
          { label: 'Did not sit it', value: String(data.notAttemptedCount) },
          { label: 'Passed', value: String(data.passedCount) },
          { label: 'Pass rate', value: pct(data.passRate) },
          { label: 'Average score', value: data.averageScore ?? '—' },
          {
            label: 'Average time',
            value: data.averageSeconds === 0 ? '—' : `${Math.round(data.averageSeconds / 60)} min`,
          },
        ].map((stat) => (
          <div key={stat.label} className="card p-lg">
            <p className="field-label">{stat.label}</p>
            <p className="text-headline-sm tabular-nums">{stat.value}</p>
          </div>
        ))}
      </div>

      {data.topics.length > 0 ? (
        <div className="card p-lg">
          <p className="field-label">By product, weakest first</p>
          <p className="mb-sm text-body-sm text-on-surface-variant">
            This is the output worth acting on. A product near the bottom names the next training
            session.
          </p>
          <ul className="flex flex-col gap-1">
            {data.topics.map((topic) => (
              <li key={topic.topic} className="flex items-center gap-sm">
                <span className="w-40 shrink-0 truncate text-body-md">{topic.topic}</span>
                <span className="h-2 flex-1 overflow-hidden rounded-full bg-surface-variant">
                  <span
                    className="block h-full bg-primary"
                    style={{ width: `${Number(topic.correctRate ?? 0)}%` }}
                    aria-hidden
                  />
                </span>
                <span className="w-24 shrink-0 text-right text-body-sm tabular-nums">
                  {pct(topic.correctRate)} of {topic.asked}
                </span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {data.questions.length > 0 ? (
        <div className="card overflow-x-auto p-lg">
          <p className="field-label">Every question, worst first</p>
          <table className="mt-sm w-full text-body-sm">
            <thead className="text-left text-on-surface-variant">
              <tr>
                <th className="pb-2 pr-md">Set / Q</th>
                <th className="pb-2 pr-md">Product</th>
                <th className="pb-2 pr-md">Question</th>
                <th className="pb-2 pr-md text-right">Asked</th>
                <th className="pb-2 pr-md text-right">Skipped</th>
                <th className="pb-2 pr-md text-right">Correct</th>
                <th className="pb-2">Commonest wrong answer</th>
              </tr>
            </thead>
            <tbody>
              {data.questions.map((question) => (
                <tr key={question.questionId} className="border-t border-outline-variant">
                  <td className="py-2 pr-md whitespace-nowrap">
                    {question.setCode} · Q{question.questionNumber}
                  </td>
                  <td className="py-2 pr-md">{question.topic ?? '—'}</td>
                  <td className="py-2 pr-md">
                    <span className="line-clamp-2">{question.questionText}</span>
                  </td>
                  <td className="py-2 pr-md text-right tabular-nums">{question.asked}</td>
                  <td className="py-2 pr-md text-right tabular-nums">{question.skipped}</td>
                  <td className="py-2 pr-md text-right tabular-nums">
                    {/*
                      Null means nobody was asked — unmeasured, not failed.
                      Showing 0% would send training after something that was
                      never tested.
                    */}
                    {question.correctRate == null
                      ? 'Not asked'
                      : `${question.correct} (${question.correctRate}%)`}
                  </td>
                  <td className="py-2">
                    {question.commonestWrongText == null
                      ? '—'
                      : `${question.commonestWrongText} (${question.commonestWrongCount})`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      <Modal
        open={retaking}
        onClose={() => setRetaking(false)}
        title="Open a retake"
        description="Whoever failed or missed it, each dealt a set they have not seen."
        footer={
          <div className="flex justify-end gap-sm">
            <button type="button" className="btn-ghost" onClick={() => setRetaking(false)}>
              Not now
            </button>
            <button
              type="button"
              className="btn-primary inline-flex items-center gap-2"
              disabled={!windowStart || !windowEnd || retake.isPending}
              onClick={() => retake.mutate()}
            >
              {retake.isPending ? <Spinner className="h-4 w-4" /> : null}
              Open it
            </button>
          </div>
        }
      >
        <div className="flex flex-col gap-md">
          <p className="text-body-md">
            The marking is copied from this exam, and the questions are the same bank — each person
            gets one of the two sets they have not opened. There is only one retake, because a
            third sitting would need a fourth set of questions.
          </p>
          <div className="grid gap-md sm:grid-cols-2">
            <TextField
              label="Opens"
              type="datetime-local"
              value={windowStart}
              onChange={(event) => setWindowStart(event.target.value)}
            />
            <TextField
              label="Closes"
              type="datetime-local"
              value={windowEnd}
              onChange={(event) => setWindowEnd(event.target.value)}
            />
          </div>
          <p className="text-body-sm text-on-surface-variant">
            It has to open after this exam closed. You will still need to publish it.
          </p>
        </div>
      </Modal>
    </div>
  );
};
