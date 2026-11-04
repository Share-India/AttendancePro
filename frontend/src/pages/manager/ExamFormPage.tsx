import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';

import { Alert } from '../../components/common/Alert';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { SelectField } from '../../components/form/SelectField';
import { TextField } from '../../components/form/TextField';
import { ROUTES } from '../../constants/routes';
import { useDepartments, useOffices } from '../../features/admin/masterQueries';
import { examApi, type ExamPayload } from '../../features/exam/examApi';
import { ApiError } from '../../types/api';
import { AUDIENCE_TYPES, FEEDBACK_OPTIONS } from '../../types/exam';

interface FormValues {
  examCode: string;
  title: string;
  description: string;
  instructions: string;
  /** datetime-local, i.e. wall-clock time in the browser's own zone. */
  windowStart: string;
  windowEnd: string;
  durationMinutes: string;
  entryClosesBeforeMinutes: string;
  marksPerQuestion: string;
  negativeMarks: string;
  passMarks: string;
  shuffleQuestions: boolean;
  shuffleOptions: boolean;
  partialCredit: boolean;
  feedbackVisibility: string;
  audienceType: string;
  audienceId: string;
}

const describeFailure = (error: unknown): { title: string; message: string } => {
  if (!(error instanceof ApiError)) {
    return { title: 'Could not save', message: 'Something went wrong. Try again.' };
  }
  switch (error.errorCode) {
    case 'VALIDATION_ERROR':
      return { title: 'Check the details', message: error.message };
    case 'INVALID_DATE_RANGE':
      return { title: 'Check the window', message: error.message };
    case 'DUPLICATE_RESOURCE':
      return { title: 'That code is taken', message: error.message };
    case 'POLICY_VIOLATION':
      return { title: 'Too late to edit', message: error.message };
    default:
      return { title: 'Could not save', message: error.message };
  }
};

/** datetime-local gives local wall time with no zone; the API wants an instant. */
const toInstant = (local: string): string => new Date(local).toISOString();

const toLocalInput = (iso: string): string => {
  const date = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
};

const defaultWindow = (): { start: string; end: string } => {
  const start = new Date();
  start.setDate(start.getDate() + 1);
  start.setHours(10, 0, 0, 0);
  const end = new Date(start);
  end.setHours(18, 0, 0, 0);
  return { start: toLocalInput(start.toISOString()), end: toLocalInput(end.toISOString()) };
};

export const ExamFormPage = () => {
  const { id } = useParams<{ id: string }>();
  const examId = id ? Number(id) : null;
  const isEdit = examId !== null;

  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const departments = useDepartments(true);
  const offices = useOffices(true);

  const window = defaultWindow();

  const { register, handleSubmit, reset, watch, formState } = useForm<FormValues>({
    defaultValues: {
      examCode: '',
      title: 'Product knowledge',
      description: '',
      instructions:
        'Twenty questions, twenty-five minutes. No negative marking, so answer everything.',
      windowStart: window.start,
      windowEnd: window.end,
      // The settled defaults: 20 questions at 5 marks, pass at 40 of 100.
      durationMinutes: '25',
      entryClosesBeforeMinutes: '5',
      marksPerQuestion: '5',
      negativeMarks: '0',
      passMarks: '40',
      shuffleQuestions: true,
      shuffleOptions: true,
      partialCredit: false,
      feedbackVisibility: 'IMMEDIATE',
      audienceType: 'GLOBAL',
      audienceId: '',
    },
  });

  const existing = useQuery({
    queryKey: ['exam', 'detail', examId],
    queryFn: () => examApi.get(examId as number),
    enabled: isEdit,
  });

  useEffect(() => {
    if (!existing.data) {
      return;
    }
    const exam = existing.data;
    const scope = exam.audience[0];
    reset({
      examCode: exam.examCode,
      title: exam.title,
      description: exam.description ?? '',
      instructions: exam.instructions ?? '',
      windowStart: toLocalInput(exam.windowStart),
      windowEnd: toLocalInput(exam.windowEnd),
      durationMinutes: String(exam.durationMinutes),
      entryClosesBeforeMinutes: String(exam.entryClosesBeforeMinutes),
      marksPerQuestion: String(exam.marksPerQuestion),
      negativeMarks: String(exam.negativeMarks),
      passMarks: String(exam.passMarks),
      shuffleQuestions: exam.shuffleQuestions,
      shuffleOptions: exam.shuffleOptions,
      partialCredit: exam.partialCredit,
      feedbackVisibility: exam.feedbackVisibility,
      audienceType: scope?.scopeType ?? 'GLOBAL',
      // Loose null check: the API omits null fields, so a strict test would
      // put the literal text "undefined" into the input.
      audienceId: scope?.scopeId == null ? '' : String(scope.scopeId),
    });
  }, [existing.data, reset]);

  const audienceType = watch('audienceType');
  const needsTarget = audienceType !== 'GLOBAL';

  const save = useMutation({
    mutationFn: (payload: ExamPayload) =>
      isEdit ? examApi.update(examId as number, payload) : examApi.create(payload),
    onSuccess: (exam) => {
      queryClient.invalidateQueries({ queryKey: ['exam'] });
      navigate(ROUTES.examDetail(exam.id));
    },
  });

  const onSubmit = (values: FormValues) => {
    save.mutate({
      examCode: values.examCode.trim().toUpperCase(),
      title: values.title.trim(),
      description: values.description.trim() || null,
      instructions: values.instructions.trim() || null,
      windowStart: toInstant(values.windowStart),
      windowEnd: toInstant(values.windowEnd),
      durationMinutes: Number(values.durationMinutes),
      entryClosesBeforeMinutes: Number(values.entryClosesBeforeMinutes),
      marksPerQuestion: Number(values.marksPerQuestion),
      negativeMarks: Number(values.negativeMarks),
      passMarks: Number(values.passMarks),
      shuffleQuestions: values.shuffleQuestions,
      shuffleOptions: values.shuffleOptions,
      partialCredit: values.partialCredit,
      feedbackVisibility: values.feedbackVisibility,
      audience: [
        {
          scopeType: values.audienceType,
          scopeId: needsTarget && values.audienceId ? Number(values.audienceId) : null,
        },
      ],
    });
  };

  const failure = save.error ? describeFailure(save.error) : null;

  if (isEdit && existing.isLoading) {
    return (
      <div className="card flex items-center justify-center p-xl">
        <Spinner />
      </div>
    );
  }

  const targetOptions =
    audienceType === 'DEPARTMENT'
      ? departments.data
      : audienceType === 'OFFICE'
        ? offices.data
        : undefined;

  return (
    <div className="flex flex-col gap-lg">
      <PageHeader
        title={isEdit ? 'Edit exam' : 'New exam'}
        description="Set the window and the marking. The questions are uploaded afterwards."
      />

      {failure ? (
        <Alert variant="error" title={failure.title}>
          {failure.message}
        </Alert>
      ) : null}

      <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-lg">
        <section className="card flex flex-col gap-md p-lg">
          <h2 className="text-title-sm text-on-surface-variant">What and when</h2>

          <div className="grid gap-md sm:grid-cols-3">
            <TextField
              label="Code"
              placeholder="PK-W42"
              hint="Short and unique. It appears on every result."
              error={formState.errors.examCode?.message}
              {...register('examCode', { required: 'Give the exam a code' })}
            />
            <div className="sm:col-span-2">
              <TextField
                label="Title"
                placeholder="Product knowledge"
                error={formState.errors.title?.message}
                {...register('title', { required: 'Give the exam a title' })}
              />
            </div>
          </div>

          <div>
            <label className="field-label" htmlFor="description">
              Description
            </label>
            <textarea id="description" className="field-input" rows={2} {...register('description')} />
          </div>

          <div>
            <label className="field-label" htmlFor="instructions">
              Instructions
            </label>
            <textarea
              id="instructions"
              className="field-input"
              rows={3}
              {...register('instructions')}
            />
            <p className="mt-1 text-body-sm text-on-surface-variant">
              Shown before they start, and again at the top of the paper.
            </p>
          </div>

          <div className="grid gap-md sm:grid-cols-2">
            <TextField
              label="Opens"
              type="datetime-local"
              error={formState.errors.windowStart?.message}
              {...register('windowStart', { required: 'When does it open?' })}
            />
            <TextField
              label="Closes"
              type="datetime-local"
              error={formState.errors.windowEnd?.message}
              {...register('windowEnd', { required: 'When does it close?' })}
            />
          </div>
        </section>

        <section className="card flex flex-col gap-md p-lg">
          <h2 className="text-title-sm text-on-surface-variant">Marking</h2>

          <div className="grid gap-md sm:grid-cols-2 lg:grid-cols-4">
            <TextField
              label="Minutes per attempt"
              type="number"
              min={1}
              error={formState.errors.durationMinutes?.message}
              {...register('durationMinutes', { required: 'How long do they get?' })}
            />
            <TextField
              label="Marks per question"
              type="number"
              min={1}
              {...register('marksPerQuestion', { required: true })}
            />
            <TextField
              label="Pass mark"
              type="number"
              min={0}
              hint="On marks, not a percentage."
              {...register('passMarks', { required: true })}
            />
            <TextField
              label="Deduct for a wrong answer"
              type="number"
              min={0}
              hint="Zero means no negative marking."
              {...register('negativeMarks', { required: true })}
            />
          </div>

          <TextField
            label="Stop new starts this many minutes before the close"
            type="number"
            min={0}
            hint="Someone starting five minutes before the end would get five minutes for the whole paper."
            {...register('entryClosesBeforeMinutes', { required: true })}
          />

          <div className="grid gap-md sm:grid-cols-2">
            <SelectField
              label="Show the answers"
              options={FEEDBACK_OPTIONS.map((option) => ({ ...option }))}
              {...register('feedbackVisibility')}
            />
          </div>

          <fieldset className="flex flex-col gap-2">
            <legend className="field-label">Shuffling</legend>
            <label className="flex items-start gap-sm text-body-md">
              <input type="checkbox" className="mt-1" {...register('shuffleQuestions')} />
              <span>Shuffle the question order for each person</span>
            </label>
            <label className="flex items-start gap-sm text-body-md">
              <input type="checkbox" className="mt-1" {...register('shuffleOptions')} />
              <span>
                Shuffle the options for each person
                <span className="block text-body-sm text-on-surface-variant">
                  Leave this on. It is what stops a paper being passed by ticking B every time,
                  whether or not anybody reads the quality report.
                </span>
              </span>
            </label>
            <label className="flex items-start gap-sm text-body-md">
              <input type="checkbox" className="mt-1" {...register('partialCredit')} />
              <span>
                Part marks on multi-answer questions
                <span className="block text-body-sm text-on-surface-variant">
                  Off by default: two of three right is not two thirds of knowing the answer.
                </span>
              </span>
            </label>
          </fieldset>
        </section>

        <section className="card flex flex-col gap-md p-lg">
          <h2 className="text-title-sm text-on-surface-variant">Who sits it</h2>
          <p className="text-body-sm text-on-surface-variant">
            Resolved into a fixed list when you publish, so somebody who joins next week will not
            appear in this exam&rsquo;s figures.
          </p>

          <div className="grid gap-md sm:grid-cols-2">
            <SelectField
              label="Audience"
              options={AUDIENCE_TYPES.map((option) => ({ ...option }))}
              {...register('audienceType')}
            />
            {needsTarget ? (
              <SelectField
                label={audienceType === 'DEPARTMENT' ? 'Department' : 'Office'}
                placeholder="Choose one"
                options={
                  targetOptions?.map((item: { id: number; name: string }) => ({
                    value: String(item.id),
                    label: item.name,
                  })) ?? []
                }
                error={formState.errors.audienceId?.message}
                {...register('audienceId', { required: needsTarget ? 'Choose one' : false })}
              />
            ) : null}
          </div>

          {audienceType === 'DESIGNATION' ? (
            <Alert variant="info" title="Designations are not pickable here yet">
              Choose a department or office for now, or everybody.
            </Alert>
          ) : null}
        </section>

        <div className="flex justify-end gap-sm">
          <button type="button" className="btn-ghost" onClick={() => navigate(-1)}>
            Back
          </button>
          <button
            type="submit"
            className="btn-primary inline-flex items-center gap-2"
            disabled={save.isPending}
          >
            {save.isPending ? <Spinner className="h-4 w-4" /> : null}
            {isEdit ? 'Save changes' : 'Create exam'}
          </button>
        </div>
      </form>
    </div>
  );
};
