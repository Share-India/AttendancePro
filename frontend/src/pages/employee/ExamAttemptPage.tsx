import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';

import { Alert } from '../../components/common/Alert';
import { Modal } from '../../components/common/Modal';
import { Spinner } from '../../components/common/Spinner';
import { ROUTES } from '../../constants/routes';
import { examApi } from '../../features/exam/examApi';
import { ApiError } from '../../types/api';
import type { AttemptQuestion } from '../../types/exam';

/** mm:ss, because an exam clock that says "0.4 hours left" helps nobody. */
const clockLabel = (seconds: number): string => {
  const safe = Math.max(0, seconds);
  const minutes = Math.floor(safe / 60);
  return `${String(minutes).padStart(2, '0')}:${String(safe % 60).padStart(2, '0')}`;
};

/** Under this much left, the clock turns red and stops being decoration. */
const URGENT_SECONDS = 120;

export const ExamAttemptPage = () => {
  const { attemptId: attemptIdParam } = useParams<{ attemptId: string }>();
  const attemptId = Number(attemptIdParam);

  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const { data, isLoading, error } = useQuery({
    queryKey: ['exam', 'attempt', attemptId],
    queryFn: () => examApi.attempt(attemptId),
    // The paper is fetched once and then held: refetching mid-exam would
    // discard unsent selections and reshuffle nothing useful.
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });

  /**
   * Which labels are selected, by question.
   *
   * <p>Held locally as well as saved, so a tap registers immediately rather
   * than after a round trip. The server remains the record: every change is
   * sent, and a refresh reads it back from there.
   */
  const [chosen, setChosen] = useState<Record<number, string[]>>({});
  const [saveFailed, setSaveFailed] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [secondsLeft, setSecondsLeft] = useState<number | null>(null);
  const [expired, setExpired] = useState(false);

  /**
   * The server's clock, corrected once.
   *
   * <p>The countdown runs off the difference between the browser's clock and
   * the server's, measured when the paper arrives. Counting down from
   * `expiresAt` against `Date.now()` directly would be wrong by however far the
   * device's clock is out — which on a phone is routinely minutes.
   */
  const skewRef = useRef<number>(0);

  useEffect(() => {
    if (!data) {
      return;
    }
    setChosen(
      Object.fromEntries(data.questions.map((q) => [q.questionId, q.selectedLabels ?? []])),
    );
    const expiresAt = new Date(data.expiresAt).getTime();
    skewRef.current = expiresAt - (Date.now() + data.secondsRemaining * 1000);
    setSecondsLeft(data.secondsRemaining);
  }, [data]);

  useEffect(() => {
    if (!data || data.submitted) {
      return;
    }
    const expiresAt = new Date(data.expiresAt).getTime();
    const tick = () => {
      const remaining = Math.round((expiresAt - skewRef.current - Date.now()) / 1000);
      setSecondsLeft(remaining);
      if (remaining <= 0) {
        setExpired(true);
      }
    };
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [data]);

  const save = useMutation({
    mutationFn: (input: { questionId: number; labels: string[] }) =>
      examApi.saveAnswer(attemptId, input.questionId, input.labels),
    onSuccess: () => setSaveFailed(null),
    onError: (saveError) => {
      // The one error worth interrupting for: their time is up, and nothing
      // further will be recorded.
      setSaveFailed(
        saveError instanceof ApiError ? saveError.message : 'That answer could not be saved.',
      );
      if (saveError instanceof ApiError && saveError.message.includes('time is up')) {
        setExpired(true);
      }
    },
  });

  const submit = useMutation({
    mutationFn: () => examApi.submit(attemptId),
    onSuccess: (result) => {
      queryClient.invalidateQueries({ queryKey: ['exam'] });
      navigate(ROUTES.examResult(result.attemptId), { replace: true });
    },
  });

  const toggle = (question: AttemptQuestion, label: string) => {
    const current = chosen[question.questionId] ?? [];
    const next = question.multiSelect
      ? current.includes(label)
        ? current.filter((l) => l !== label)
        : [...current, label].sort()
      : current.includes(label)
        ? []
        : [label];

    setChosen((previous) => ({ ...previous, [question.questionId]: next }));
    save.mutate({ questionId: question.questionId, labels: next });
  };

  const answeredCount = useMemo(
    () => Object.values(chosen).filter((labels) => labels.length > 0).length,
    [chosen],
  );

  if (isLoading) {
    return (
      <div className="flex min-h-[60vh] items-center justify-center">
        <Spinner />
      </div>
    );
  }

  if (error || !data) {
    return (
      <div className="mx-auto max-w-2xl p-md">
        <Alert variant="error" title="Could not open your paper">
          {error instanceof ApiError ? error.message : 'Try again from My exams.'}
        </Alert>
        <button type="button" className="btn-ghost mt-md" onClick={() => navigate(ROUTES.exams)}>
          Back to my exams
        </button>
      </div>
    );
  }

  if (data.submitted) {
    return (
      <div className="mx-auto max-w-2xl p-md">
        <Alert variant="info" title="This paper is already in">
          You have submitted this exam. Your result is on the next screen.
        </Alert>
        <button
          type="button"
          className="btn-primary mt-md"
          onClick={() => navigate(ROUTES.examResult(attemptId), { replace: true })}
        >
          See my result
        </button>
      </div>
    );
  }

  const urgent = secondsLeft !== null && secondsLeft <= URGENT_SECONDS;

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-md pb-32">
      {/*
        Sticky, because this is read on a phone with one thumb and the clock is
        the single thing somebody needs at all times.
      */}
      <header className="sticky top-0 z-10 -mx-md border-b border-outline-variant bg-surface px-md py-sm sm:mx-0 sm:rounded-md sm:border">
        <div className="flex items-center justify-between gap-sm">
          <div className="min-w-0">
            <p className="truncate text-title-sm">{data.examTitle}</p>
            <p className="text-body-sm text-on-surface-variant">
              Set {data.setCode} · {answeredCount} of {data.questionCount} answered
            </p>
          </div>
          <div
            className={`shrink-0 rounded-md px-sm py-1 text-title-md tabular-nums ${
              urgent ? 'bg-error-container text-on-error-container' : 'bg-surface-variant'
            }`}
            role="timer"
            aria-live={urgent ? 'polite' : 'off'}
          >
            {secondsLeft === null ? '—' : clockLabel(secondsLeft)}
          </div>
        </div>
        <div
          className="mt-sm h-1 w-full overflow-hidden rounded-full bg-surface-variant"
          role="progressbar"
          aria-valuenow={answeredCount}
          aria-valuemax={data.questionCount}
        >
          <div
            className="h-full bg-primary transition-all"
            style={{ width: `${(answeredCount / Math.max(1, data.questionCount)) * 100}%` }}
          />
        </div>
      </header>

      {expired ? (
        <Alert variant="warning" title="Your time is up">
          Nothing further can be recorded. Submit to see your result — the paper will be marked as
          it stood when the clock ran out.
        </Alert>
      ) : null}

      {saveFailed && !expired ? (
        <Alert variant="error" title="That answer was not saved">
          {saveFailed}
        </Alert>
      ) : null}

      {data.instructions ? (
        <Alert variant="info" title="Before you begin">
          <span className="whitespace-pre-line">{data.instructions}</span>
        </Alert>
      ) : null}

      <ol className="flex flex-col gap-md">
        {data.questions.map((question) => {
          const selected = chosen[question.questionId] ?? [];
          return (
            <li key={question.questionId} className="card flex flex-col gap-sm p-lg">
              <div className="flex items-baseline justify-between gap-sm">
                <span className="text-body-sm text-on-surface-variant">
                  Question {question.displayNumber} of {data.questionCount}
                  {question.topic ? ` · ${question.topic}` : ''}
                </span>
                <span className="shrink-0 text-body-sm text-on-surface-variant tabular-nums">
                  {question.marks} marks
                </span>
              </div>

              <p className="text-body-lg">{question.questionText}</p>

              {question.multiSelect ? (
                <p className="text-body-sm text-on-surface-variant">
                  More than one answer is correct. All of them, and none of the others.
                </p>
              ) : null}

              <div className="flex flex-col gap-2">
                {question.options.map((option) => {
                  const isChosen = selected.includes(option.label);
                  return (
                    <label
                      key={option.label}
                      className={`flex cursor-pointer items-start gap-sm rounded-md border p-sm transition-colors ${
                        isChosen
                          ? 'border-primary bg-primary-container'
                          : 'border-outline-variant hover:bg-surface-variant'
                      }`}
                    >
                      <input
                        type={question.multiSelect ? 'checkbox' : 'radio'}
                        name={`question-${question.questionId}`}
                        className="mt-1"
                        checked={isChosen}
                        disabled={expired}
                        onChange={() => toggle(question, option.label)}
                      />
                      <span className="text-body-md">
                        <span className="mr-1 font-medium">{option.label}.</span>
                        {option.text}
                      </span>
                    </label>
                  );
                })}
              </div>
            </li>
          );
        })}
      </ol>

      {/*
        Fixed, so Submit is always one tap away however long the paper is.

        pad-safe-bottom keeps it above the iPhone home indicator — without it
        the bar renders underneath the indicator and Submit is the control a
        candidate cannot reach at the one moment it matters.
      */}
      <div className="pad-safe-bottom fixed inset-x-0 bottom-0 border-t border-outline-variant bg-surface px-md py-sm">
        <div className="mx-auto flex max-w-3xl items-center justify-between gap-sm">
          <span className="text-body-sm text-on-surface-variant">
            {answeredCount === data.questionCount
              ? 'All questions answered'
              : `${data.questionCount - answeredCount} still unanswered`}
          </span>
          <button
            type="button"
            className="btn-primary inline-flex items-center gap-2"
            disabled={submit.isPending}
            onClick={() => setConfirming(true)}
          >
            {submit.isPending ? <Spinner className="h-4 w-4" /> : null}
            Submit
          </button>
        </div>
      </div>

      <Modal
        open={confirming}
        onClose={() => setConfirming(false)}
        title="Hand this in?"
        description="You cannot change your answers afterwards."
        footer={
          <div className="flex justify-end gap-sm">
            <button type="button" className="btn-ghost" onClick={() => setConfirming(false)}>
              Keep going
            </button>
            <button
              type="button"
              className="btn-primary inline-flex items-center gap-2"
              disabled={submit.isPending}
              onClick={() => {
                setConfirming(false);
                submit.mutate();
              }}
            >
              {submit.isPending ? <Spinner className="h-4 w-4" /> : null}
              Submit
            </button>
          </div>
        }
      >
        <p className="text-body-md">
          {answeredCount === data.questionCount
            ? 'Every question has an answer.'
            : `${data.questionCount - answeredCount} question(s) have no answer. An unanswered question scores nothing, and there is no penalty for a wrong one — so a guess is worth more than a blank.`}
        </p>
        {submit.error ? (
          <Alert variant="error" title="Could not submit">
            {submit.error instanceof ApiError ? submit.error.message : 'Try again.'}
          </Alert>
        ) : null}
      </Modal>
    </div>
  );
};
