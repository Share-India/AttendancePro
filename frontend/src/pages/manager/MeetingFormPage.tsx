import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';

import { Alert } from '../../components/common/Alert';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { TextField } from '../../components/form/TextField';
import { SelectField } from '../../components/form/SelectField';
import { ROUTES } from '../../constants/routes';
import { useDepartments, useOffices } from '../../features/admin/masterQueries';
import { meetingApi, type MeetingPayload } from '../../features/meeting/meetingApi';
import { ApiError } from '../../types/api';
import { AUDIENCE_TYPES } from '../../types/meeting';

interface FormValues {
  title: string;
  agenda: string;
  meetingUrl: string;
  /** datetime-local, i.e. wall-clock time in the browser's own zone. */
  startsAt: string;
  endsAt: string;
  lateAfterMinutes: string;
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
      return { title: 'Check the times', message: error.message };
    case 'ACCESS_DENIED':
      return { title: 'Not yours to edit', message: error.message };
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

const defaultStart = (): string => {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  date.setHours(9, 30, 0, 0);
  return toLocalInput(date.toISOString());
};

const defaultEnd = (): string => {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  date.setHours(9, 45, 0, 0);
  return toLocalInput(date.toISOString());
};

export const MeetingFormPage = () => {
  const { id } = useParams<{ id: string }>();
  const meetingId = id ? Number(id) : null;
  const isEdit = meetingId !== null;

  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const departments = useDepartments(true);
  const offices = useOffices(true);

  const { register, handleSubmit, reset, watch, formState } = useForm<FormValues>({
    defaultValues: {
      title: 'Morning meeting',
      agenda: '',
      meetingUrl: '',
      startsAt: defaultStart(),
      endsAt: defaultEnd(),
      lateAfterMinutes: '',
      audienceType: 'TEAM',
      audienceId: '',
    },
  });

  const existing = useQuery({
    queryKey: ['meeting', 'detail', meetingId],
    queryFn: () => meetingApi.get(meetingId as number),
    enabled: isEdit,
  });

  const existingLink = useQuery({
    queryKey: ['meeting', 'link', meetingId],
    queryFn: () => meetingApi.link(meetingId as number),
    enabled: isEdit,
  });

  useEffect(() => {
    if (!existing.data || existingLink.data === undefined) {
      return;
    }
    const scope = existing.data.audience[0];
    reset({
      title: existing.data.title,
      agenda: existing.data.agenda ?? '',
      meetingUrl: existingLink.data,
      startsAt: toLocalInput(existing.data.scheduledStartAt),
      endsAt: toLocalInput(existing.data.scheduledEndAt),
      // Loose null checks: the API omits null fields, so these arrive as
      // undefined and a strict test would put the literal text "undefined"
      // into the input.
      lateAfterMinutes:
        existing.data.lateAfterMinutes == null ? '' : String(existing.data.lateAfterMinutes),
      audienceType: scope?.scopeType ?? 'TEAM',
      audienceId: scope?.scopeId == null ? '' : String(scope.scopeId),
    });
  }, [existing.data, existingLink.data, reset]);

  const audienceType = watch('audienceType');
  const needsTarget = audienceType === 'DEPARTMENT' || audienceType === 'OFFICE';

  const save = useMutation({
    mutationFn: (payload: MeetingPayload) =>
      isEdit ? meetingApi.update(meetingId as number, payload) : meetingApi.create(payload),
    onSuccess: (meeting) => {
      queryClient.invalidateQueries({ queryKey: ['meeting'] });
      navigate(ROUTES.meetingDetail(meeting.id));
    },
  });

  const onSubmit = (values: FormValues) => {
    save.mutate({
      title: values.title.trim(),
      agenda: values.agenda.trim() || null,
      meetingUrl: values.meetingUrl.trim(),
      scheduledStartAt: toInstant(values.startsAt),
      scheduledEndAt: toInstant(values.endsAt),
      lateAfterMinutes: values.lateAfterMinutes ? Number(values.lateAfterMinutes) : null,
      audience: [
        {
          scopeType: values.audienceType,
          scopeId: needsTarget && values.audienceId ? Number(values.audienceId) : null,
        },
      ],
    });
  };

  const failure = save.error ? describeFailure(save.error) : null;

  if (isEdit && (existing.isLoading || existingLink.isLoading)) {
    return (
      <div className="card flex items-center justify-center p-xl">
        <Spinner />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-lg">
      <PageHeader
        title={isEdit ? 'Edit meeting' : 'New meeting'}
        description="Paste the link from Google Meet, Zoom, Teams or anywhere else."
      />

      {failure ? (
        <Alert variant="error" title={failure.title}>
          {failure.message}
        </Alert>
      ) : null}

      <form onSubmit={handleSubmit(onSubmit)} className="card flex flex-col gap-md p-lg">
        <TextField
          label="Title"
          placeholder="Morning meeting"
          error={formState.errors.title?.message}
          {...register('title', { required: 'Give the meeting a title' })}
        />

        <div>
          <label className="field-label" htmlFor="agenda">
            Agenda
          </label>
          <textarea id="agenda" className="field-input" rows={3} {...register('agenda')} />
        </div>

        <TextField
          label="Meeting link"
          placeholder="https://meet.google.com/abc-defg-hij"
          hint="Must start with https://. Nobody sees this link directly — joining goes through the app so attendance is recorded."
          error={formState.errors.meetingUrl?.message}
          {...register('meetingUrl', {
            required: 'Paste the meeting link',
            pattern: { value: /^https:\/\//i, message: 'The link must start with https://' },
          })}
        />

        <div className="grid gap-md sm:grid-cols-2">
          <TextField
            label="Starts"
            type="datetime-local"
            error={formState.errors.startsAt?.message}
            {...register('startsAt', { required: 'When does it start?' })}
          />
          <TextField
            label="Ends"
            type="datetime-local"
            error={formState.errors.endsAt?.message}
            {...register('endsAt', { required: 'When does it end?' })}
          />
        </div>

        <div className="grid gap-md sm:grid-cols-2">
          <SelectField
            label="Who is expected"
            options={AUDIENCE_TYPES.map((option) => ({ ...option }))}
            {...register('audienceType')}
          />
          {needsTarget ? (
            <SelectField
              label={audienceType === 'DEPARTMENT' ? 'Department' : 'Office'}
              placeholder="Choose one"
              options={(audienceType === 'DEPARTMENT' ? departments.data : offices.data)?.map(
                (item: { id: number; name: string }) => ({
                  value: String(item.id),
                  label: item.name,
                }),
              ) ?? []}
              error={formState.errors.audienceId?.message}
              {...register('audienceId', {
                required: needsTarget ? 'Choose one' : false,
              })}
            />
          ) : null}
        </div>

        <TextField
          label="Count as late after (minutes)"
          type="number"
          min={0}
          placeholder="Company default"
          hint="Leave blank to use the company setting."
          {...register('lateAfterMinutes')}
        />

        <div className="flex justify-end gap-sm pt-sm">
          <button type="button" className="btn-ghost" onClick={() => navigate(-1)}>
            Back
          </button>
          <button
            type="submit"
            className="btn-primary inline-flex items-center gap-2"
            disabled={save.isPending}
          >
            {save.isPending ? <Spinner className="h-4 w-4" /> : null}
            {isEdit ? 'Save changes' : 'Create meeting'}
          </button>
        </div>
      </form>
    </div>
  );
};
