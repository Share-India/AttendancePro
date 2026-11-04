import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';

import { Alert } from '../../components/common/Alert';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { ROUTES } from '../../constants/routes';
import { examApi } from '../../features/exam/examApi';
import { ApiError } from '../../types/api';
import { SUBMISSION_LABEL } from '../../types/exam';

const minutesLabel = (seconds: number): string => {
  if (seconds <= 0) {
    return '—';
  }
  const minutes = Math.floor(seconds / 60);
  return minutes < 1 ? 'under a minute' : `${minutes} min`;
};

export const ExamResultPage = () => {
  const { attemptId: attemptIdParam } = useParams<{ attemptId: string }>();
  const attemptId = Number(attemptIdParam);

  const { data, isLoading, error } = useQuery({
    queryKey: ['exam', 'result', attemptId],
    queryFn: () => examApi.result(attemptId),
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
      <div className="flex flex-col gap-md">
        <Alert variant="error" title="Could not load your result">
          {error instanceof ApiError ? error.message : 'Try again from My exams.'}
        </Alert>
        <Link to={ROUTES.exams} className="btn-ghost self-start">
          Back to my exams
        </Link>
      </div>
    );
  }

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-lg">
      <PageHeader
        title={data.examTitle}
        description={`Set ${data.setCode} · ${
          data.submissionSource ? SUBMISSION_LABEL[data.submissionSource] ?? '' : ''
        }`}
        actions={
          <Link to={ROUTES.exams} className="btn-ghost">
            My exams
          </Link>
        }
      />

      <section className="card flex flex-wrap items-center gap-lg p-lg">
        <div>
          <p className="field-label">Score</p>
          <p className="text-display-sm tabular-nums">
            {data.score}
            <span className="text-title-md text-on-surface-variant"> / {data.maxScore}</span>
          </p>
        </div>
        <div>
          <p className="field-label">Correct</p>
          <p className="text-headline-sm tabular-nums">
            {data.correctCount} of {data.questionCount}
          </p>
        </div>
        <div>
          <p className="field-label">Pass mark</p>
          <p className="text-headline-sm tabular-nums">{data.passMarks}</p>
        </div>
        <div>
          <p className="field-label">Time taken</p>
          <p className="text-headline-sm">{minutesLabel(data.secondsTaken)}</p>
        </div>
        <div className="ml-auto">
          <StatusChip
            label={data.passed ? 'Passed' : 'Did not pass'}
            tone={data.passed ? 'success' : 'warning'}
          />
        </div>
      </section>

      {!data.passed ? (
        <Alert variant="info" title="This is a study exam">
          It does not feed your appraisal. Read the explanations below — they are the point of the
          exercise — and HR may open a retake on a different set of questions.
        </Alert>
      ) : null}

      {!data.feedbackVisible ? (
        <Alert variant="info" title="The answers are not published yet">
          Your score is above. The worked answers appear once the exam closes for everybody, so
          that the first person to finish cannot brief the queue.
        </Alert>
      ) : null}

      {data.feedbackVisible ? (
        <section className="flex flex-col gap-md">
          <h2 className="text-title-md">Every question, worked through</h2>

          {data.questions.map((question) => (
            <article
              key={question.questionId}
              className={`card flex flex-col gap-sm border-l-4 p-lg ${
                question.correct
                  ? 'border-l-success'
                  : question.skipped
                    ? 'border-l-outline'
                    : 'border-l-error'
              }`}
            >
              <div className="flex items-baseline justify-between gap-sm">
                <span className="text-body-sm text-on-surface-variant">
                  Question {question.displayNumber}
                  {question.topic ? ` · ${question.topic}` : ''}
                </span>
                <span className="shrink-0 text-body-sm tabular-nums">
                  {question.marksAwarded} / {question.marks}
                </span>
              </div>

              <p className="text-body-lg">{question.questionText}</p>

              <ul className="flex flex-col gap-1">
                {question.options.map((option) => {
                  const tone = option.correct
                    ? 'border-success bg-success-container'
                    : option.chosen
                      ? 'border-error bg-error-container'
                      : 'border-outline-variant';
                  return (
                    <li
                      key={option.label}
                      className={`flex items-start gap-sm rounded-md border p-sm text-body-md ${tone}`}
                    >
                      <span className="font-medium">{option.label}.</span>
                      <span className="flex-1">{option.text}</span>
                      {/*
                        Both, when they got it right. Showing only "Correct
                        answer" leaves the one case where somebody cannot tell
                        from the row whether they picked it.
                      */}
                      {option.correct ? (
                        <span className="shrink-0 text-body-sm">
                          Correct answer{option.chosen ? ' — you chose this' : ''}
                        </span>
                      ) : null}
                      {option.chosen && !option.correct ? (
                        <span className="shrink-0 text-body-sm">You chose this</span>
                      ) : null}
                    </li>
                  );
                })}
              </ul>

              {question.skipped ? (
                <p className="text-body-sm text-on-surface-variant">You left this one blank.</p>
              ) : null}

              {question.explanation ? (
                <div className="rounded-md bg-surface-variant p-sm">
                  <p className="field-label">Why</p>
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
      ) : null}
    </div>
  );
};
