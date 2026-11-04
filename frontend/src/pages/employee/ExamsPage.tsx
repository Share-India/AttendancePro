import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';

import { Alert } from '../../components/common/Alert';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { ROUTES } from '../../constants/routes';
import { examApi } from '../../features/exam/examApi';
import { ApiError } from '../../types/api';
import { BUCKET_LABEL, type ExamBucket, type MyExam } from '../../types/exam';

const BUCKET_ORDER: ExamBucket[] = ['OPEN', 'IN_PROGRESS', 'UPCOMING', 'COMPLETED', 'MISSED'];

const BUCKET_TONE: Record<ExamBucket, 'neutral' | 'info' | 'success' | 'warning' | 'error'> = {
  OPEN: 'success',
  IN_PROGRESS: 'info',
  UPCOMING: 'neutral',
  COMPLETED: 'neutral',
  MISSED: 'warning',
};

const whenLabel = (iso: string): string =>
  new Date(iso).toLocaleString(undefined, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });

const describeFailure = (error: unknown): { title: string; message: string } => {
  if (!(error instanceof ApiError)) {
    return { title: 'That did not work', message: 'Something went wrong. Try again.' };
  }
  switch (error.errorCode) {
    case 'POLICY_VIOLATION':
    case 'OUTSIDE_PUNCH_WINDOW':
      return { title: 'Cannot start this', message: error.message };
    case 'ACCESS_DENIED':
      return { title: 'Not yours to sit', message: error.message };
    default:
      return { title: 'That did not work', message: error.message };
  }
};

export const ExamsPage = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState<MyExam | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ['exam', 'mine'],
    queryFn: () => examApi.mine(),
  });

  const start = useMutation({
    mutationFn: (examId: number) => examApi.start(examId),
    onSuccess: (attempt) => {
      queryClient.invalidateQueries({ queryKey: ['exam'] });
      navigate(ROUTES.examAttempt(attempt.attemptId));
    },
  });

  const failure = start.error ? describeFailure(start.error) : null;
  const exams = data ?? [];

  const byBucket = BUCKET_ORDER.map((bucket) => ({
    bucket,
    exams: exams.filter((exam) => exam.bucket === bucket),
  })).filter((group) => group.exams.length > 0);

  return (
    <div className="flex flex-col gap-lg">
      <PageHeader
        title="My exams"
        description="Short product-knowledge papers. They are here to teach, not to rate anybody."
      />

      {failure ? (
        <Alert variant="error" title={failure.title}>
          {failure.message}
        </Alert>
      ) : null}

      {isLoading ? (
        <div className="card flex items-center justify-center p-xl">
          <Spinner />
        </div>
      ) : null}

      {error ? (
        <Alert variant="error" title="Could not load your exams">
          {error instanceof ApiError ? error.message : 'Try again in a moment.'}
        </Alert>
      ) : null}

      {!isLoading && !error && exams.length === 0 ? (
        <div className="card p-xl text-center">
          <p className="text-title-md">No exams yet</p>
          <p className="mt-1 text-body-md text-on-surface-variant">
            You will see one here when HR publishes it, and you will be notified.
          </p>
        </div>
      ) : null}

      {byBucket.map((group) => (
        <section key={group.bucket} className="flex flex-col gap-sm">
          <h2 className="text-title-sm text-on-surface-variant">{BUCKET_LABEL[group.bucket]}</h2>

          <div className="grid gap-md lg:grid-cols-2">
            {group.exams.map((exam) => (
              <article key={exam.examId} className="card flex flex-col gap-sm p-lg">
                <div className="flex items-start justify-between gap-sm">
                  <div className="min-w-0">
                    <p className="truncate text-title-md">{exam.title}</p>
                    <p className="text-body-sm text-on-surface-variant">
                      {exam.examCode} · Set {exam.setCode}
                      {exam.retake ? ' · retake' : ''}
                    </p>
                  </div>
                  <StatusChip
                    label={BUCKET_LABEL[exam.bucket]}
                    tone={BUCKET_TONE[exam.bucket]}
                  />
                </div>

                <dl className="grid grid-cols-2 gap-x-md gap-y-1 text-body-sm sm:grid-cols-4">
                  <div>
                    <dt className="text-on-surface-variant">Questions</dt>
                    <dd className="tabular-nums">{exam.questionCount}</dd>
                  </div>
                  <div>
                    <dt className="text-on-surface-variant">Time</dt>
                    <dd className="tabular-nums">{exam.durationMinutes} min</dd>
                  </div>
                  <div>
                    <dt className="text-on-surface-variant">Pass mark</dt>
                    <dd className="tabular-nums">
                      {exam.passMarks} of {exam.totalMarks}
                    </dd>
                  </div>
                  <div>
                    <dt className="text-on-surface-variant">Opens</dt>
                    <dd>{whenLabel(exam.windowStart)}</dd>
                  </div>
                </dl>

                {/* Loose check: the API omits null fields, so this arrives undefined. */}
                {exam.bucket === 'COMPLETED' && exam.score != null ? (
                  <div className="flex items-center gap-sm rounded-md bg-surface-variant p-sm">
                    <span className="text-headline-sm tabular-nums">
                      {exam.score}
                      <span className="text-body-md text-on-surface-variant">
                        {' '}
                        / {exam.maxScore}
                      </span>
                    </span>
                    <StatusChip
                      label={exam.passed ? 'Passed' : 'Did not pass'}
                      tone={exam.passed ? 'success' : 'warning'}
                    />
                  </div>
                ) : null}

                {exam.blockedReason ? (
                  <p className="text-body-sm text-on-surface-variant">{exam.blockedReason}</p>
                ) : null}

                <div className="flex flex-wrap justify-end gap-sm pt-sm">
                  {exam.bucket === 'OPEN' && !exam.blockedReason ? (
                    <button
                      type="button"
                      className="btn-primary"
                      disabled={start.isPending}
                      onClick={() => setConfirming(exam)}
                    >
                      Start
                    </button>
                  ) : null}

                  {exam.bucket === 'IN_PROGRESS' && exam.attemptId ? (
                    <button
                      type="button"
                      className="btn-primary"
                      onClick={() => navigate(ROUTES.examAttempt(exam.attemptId as number))}
                    >
                      Carry on
                    </button>
                  ) : null}

                  {exam.bucket === 'COMPLETED' && exam.attemptId ? (
                    <button
                      type="button"
                      className="btn-secondary"
                      onClick={() => navigate(ROUTES.examResult(exam.attemptId as number))}
                    >
                      {exam.feedbackAvailable ? 'See the answers' : 'See my score'}
                    </button>
                  ) : null}
                </div>
              </article>
            ))}
          </div>
        </section>
      ))}

      {/*
        The clock starts on the server the moment Start is pressed and does not
        stop, so this says so plainly before anybody presses it.
      */}
      <Modal
        open={confirming !== null}
        onClose={() => setConfirming(null)}
        title="Ready to start?"
        description={confirming ? confirming.title : undefined}
        footer={
          <div className="flex justify-end gap-sm">
            <button type="button" className="btn-ghost" onClick={() => setConfirming(null)}>
              Not yet
            </button>
            <button
              type="button"
              className="btn-primary inline-flex items-center gap-2"
              disabled={start.isPending}
              onClick={() => {
                if (confirming) {
                  start.mutate(confirming.examId);
                  setConfirming(null);
                }
              }}
            >
              {start.isPending ? <Spinner className="h-4 w-4" /> : null}
              Start the exam
            </button>
          </div>
        }
      >
        {confirming ? (
          <div className="flex flex-col gap-sm text-body-md">
            <p>
              You have <strong>{confirming.durationMinutes} minutes</strong> for{' '}
              {confirming.questionCount} questions. The clock starts now and keeps running if you
              close the page, so your answers are saved as you go.
            </p>
            <p>
              Pass mark is {confirming.passMarks} of {confirming.totalMarks}. There is no negative
              marking, so an answer you are unsure of costs nothing.
            </p>
            {confirming.instructions ? (
              <p className="whitespace-pre-line rounded-md bg-surface-variant p-sm">
                {confirming.instructions}
              </p>
            ) : null}
          </div>
        ) : null}
      </Modal>
    </div>
  );
};
