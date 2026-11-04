import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { SelectField } from '../../components/form/SelectField';
import { TextField } from '../../components/form/TextField';
import { DataTable, type Column } from '../../components/table/DataTable';
import { PunchChange } from '../../features/request/PunchChange';
import { regularizationApi } from '../../features/request/requestApi';
import { ApiError } from '../../types/api';
import {
  REASON_NEEDS,
  REGULARIZATION_REASONS,
  REQUEST_STATUS_TONE,
  type Regularization,
} from '../../types/request';

interface FormValues {
  attendanceDate: string;
  reasonType: string;
  punchInTime: string;
  punchOutTime: string;
  requestedWorkMode: string;
  reason: string;
}

const isoToday = (): string => new Date().toISOString().slice(0, 10);

/**
 * The employee types a wall-clock time; the API takes an instant. Combining the
 * two in the browser's own zone is right, because the time they mean is the
 * time they experienced.
 */
const toInstant = (date: string, time: string): string | null =>
  date && time ? new Date(`${date}T${time}`).toISOString() : null;

const timeOnly = (value: string | null): string =>
  value ? new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '—';

const describeFailure = (error: unknown): { title: string; message: string } => {
  if (!(error instanceof ApiError)) {
    return { title: 'Could not submit', message: 'Something went wrong. Please try again.' };
  }
  switch (error.errorCode) {
    case 'DUPLICATE_REQUEST':
      return { title: 'Already requested', message: error.message };
    case 'POLICY_VIOLATION':
      return { title: 'Not allowed by policy', message: error.message };
    case 'VALIDATION_ERROR':
      return { title: 'Check the times', message: error.message };
    default:
      return { title: 'Could not submit', message: error.message };
  }
};

export const RegularizationPage = () => {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [selected, setSelected] = useState<Regularization | null>(null);
  const [submitted, setSubmitted] = useState<Regularization | null>(null);

  const {
    register,
    handleSubmit,
    watch,
    reset,
    formState: { errors },
  } = useForm<FormValues>({
    defaultValues: {
      attendanceDate: '',
      reasonType: '',
      punchInTime: '',
      punchOutTime: '',
      requestedWorkMode: '',
      reason: '',
    },
  });

  const reasonType = watch('reasonType');
  const attendanceDate = watch('attendanceDate');

  // Which times to ask for depends on the reason. Asking for a punch-out when
  // the complaint is a missed punch-in only invites a wrong answer.
  const needs = useMemo(
    () => REASON_NEEDS[reasonType] ?? { punchIn: true, punchOut: true },
    [reasonType],
  );

  const { data, isLoading, error } = useQuery({
    queryKey: ['regularization', 'my', page, size],
    queryFn: () => regularizationApi.myRequests({ page, size }),
    placeholderData: keepPreviousData,
  });

  const apply = useMutation({
    mutationFn: (values: FormValues) =>
      regularizationApi.apply({
        attendanceDate: values.attendanceDate,
        reasonType: values.reasonType,
        requestedPunchInTime: toInstant(values.attendanceDate, values.punchInTime),
        requestedPunchOutTime: toInstant(values.attendanceDate, values.punchOutTime),
        requestedWorkMode: values.requestedWorkMode || null,
        reason: values.reason,
      }),
    onSuccess: (created) => {
      setSubmitted(created);
      reset();
      queryClient.invalidateQueries({ queryKey: ['regularization'] });
      queryClient.invalidateQueries({ queryKey: ['attendance'] });
    },
  });

  const withdraw = useMutation({
    mutationFn: (id: number) => regularizationApi.withdraw(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['regularization'] });
      setSelected(null);
    },
  });

  const columns: Column<Regularization>[] = [
    {
      key: 'date',
      header: 'Date',
      className: 'whitespace-nowrap tabular-nums',
      render: (row) => (
        <div>
          <p className="text-on-surface">{new Date(row.attendanceDate).toLocaleDateString()}</p>
          <p className="text-body-sm text-on-surface-variant">{row.dayOfWeek}</p>
        </div>
      ),
    },
    {
      key: 'reason',
      header: 'Reason',
      render: (row) =>
        REGULARIZATION_REASONS.find((option) => option.value === row.reasonType)?.label ??
        row.reasonType,
    },
    {
      key: 'change',
      header: 'Change',
      className: 'whitespace-nowrap tabular-nums',
      render: (row) => <PunchChange request={row} />,
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <StatusChip label={row.status} tone={REQUEST_STATUS_TONE[row.status] ?? 'neutral'} />
      ),
    },
    {
      key: 'with',
      header: 'With',
      render: (row) => row.currentApproverName ?? '—',
    },
  ];

  const failure = apply.isError ? describeFailure(apply.error) : null;

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title="Attendance Regularization"
        description="Correct a day the clock got wrong. Your manager sees both what the day holds now and what you are asking for."
      />

      {submitted && (
        <Alert variant="success" title="Correction requested" className="mb-lg">
          {submitted.requestNumber}
          {submitted.currentApproverName
            ? ` — ${submitted.currentApproverName} has been asked to review it.`
            : ' — it is waiting for an approver.'}
        </Alert>
      )}

      {failure && (
        <Alert variant="error" title={failure.title} className="mb-lg">
          {failure.message}
        </Alert>
      )}

      <div className="grid gap-lg lg:grid-cols-[minmax(0,380px)_minmax(0,1fr)]">
        <section className="surface-card h-fit p-lg">
          <h2 className="mb-md text-title-md text-on-surface">Request a correction</h2>

          <form
            className="flex flex-col gap-md"
            onSubmit={handleSubmit((values) => {
              setSubmitted(null);
              apply.mutate(values);
            })}
            noValidate
          >
            <TextField
              label="Which day"
              type="date"
              max={isoToday()}
              error={errors.attendanceDate?.message}
              {...register('attendanceDate', {
                required: 'Pick the day you are correcting',
              })}
            />

            <SelectField
              label="What went wrong"
              placeholder="Choose a reason"
              options={REGULARIZATION_REASONS.map((option) => ({
                value: option.value,
                label: option.label,
              }))}
              error={errors.reasonType?.message}
              {...register('reasonType', { required: 'Choose a reason' })}
            />

            {(needs.punchIn || needs.punchOut) && (
              <div className="grid gap-md sm:grid-cols-2">
                {needs.punchIn && (
                  <TextField
                    label="Punch in at"
                    type="time"
                    error={errors.punchInTime?.message}
                    {...register('punchInTime', {
                      required: needs.punchIn ? 'Enter the time you started' : false,
                    })}
                  />
                )}
                {needs.punchOut && (
                  <TextField
                    label="Punch out at"
                    type="time"
                    error={errors.punchOutTime?.message}
                    {...register('punchOutTime', {
                      required: needs.punchOut ? 'Enter the time you finished' : false,
                    })}
                  />
                )}
              </div>
            )}

            <SelectField
              label="Where you worked"
              placeholder="Leave as recorded"
              options={[
                { value: 'OFFICE', label: 'Office' },
                { value: 'WFH', label: 'Home' },
                { value: 'CLIENT_SITE', label: 'Client site' },
                { value: 'FIELD', label: 'Field' },
              ]}
              {...register('requestedWorkMode')}
            />

            <div>
              <label htmlFor="regularization-reason" className="field-label">
                What happened
              </label>
              <textarea
                id="regularization-reason"
                rows={3}
                maxLength={1000}
                className={`field-input resize-y ${errors.reason ? 'field-input-error' : ''}`}
                placeholder="Your approver reads this, so give them enough to say yes."
                {...register('reason', { required: 'Say what happened' })}
              />
              {errors.reason && (
                <p className="field-error">
                  <Icon name="error" className="text-[14px]" />
                  {errors.reason.message}
                </p>
              )}
            </div>

            <button type="submit" className="btn-primary" disabled={apply.isPending}>
              {apply.isPending ? <Spinner className="h-4 w-4" /> : <Icon name="send" className="text-[18px]" />}
              Send for approval
            </button>

            {attendanceDate && (
              <p className="text-body-sm text-on-surface-variant">
                Times are read in your own timezone, the same one your punches are recorded in.
              </p>
            )}
          </form>
        </section>

        <section>
          <h2 className="mb-md text-title-md text-on-surface">Your corrections</h2>
          <DataTable
            columns={columns}
            rows={data?.content ?? []}
            rowKey={(row) => row.id}
            loading={isLoading}
            error={
              error instanceof ApiError ? error.message : error ? 'Could not load your requests.' : null
            }
            emptyTitle="No corrections yet"
            emptyMessage="Requests you raise appear here with their approval trail."
            onRowClick={setSelected}
            page={data?.page ?? 0}
            size={size}
            totalElements={data?.totalElements ?? 0}
            totalPages={data?.totalPages ?? 0}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(0);
            }}
          />
        </section>
      </div>

      <Modal
        open={selected !== null}
        onClose={() => setSelected(null)}
        title={selected ? `Correction for ${new Date(selected.attendanceDate).toLocaleDateString()}` : ''}
        description={selected?.requestNumber}
        footer={
          selected ? (
            <>
              <button type="button" className="btn-secondary" onClick={() => setSelected(null)}>
                Close
              </button>
              {selected.canWithdraw && (
                <button
                  type="button"
                  className="btn-secondary text-error"
                  disabled={withdraw.isPending}
                  onClick={() => withdraw.mutate(selected.id)}
                >
                  {withdraw.isPending ? <Spinner className="h-4 w-4" /> : <Icon name="undo" className="text-[18px]" />}
                  Withdraw
                </button>
              )}
            </>
          ) : undefined
        }
      >
        {selected && (
          <div className="flex flex-col gap-lg">
            <div className="grid gap-md sm:grid-cols-2">
              <div className="rounded-DEFAULT bg-surface-container p-md">
                <p className="text-body-sm text-on-surface-variant">The day as it stands</p>
                {selected.creatingMissingDay ? (
                  <p className="mt-xs text-body-md text-on-surface">No attendance was recorded.</p>
                ) : (
                  <p className="mt-xs text-body-md tabular-nums text-on-surface">
                    {timeOnly(selected.originalPunchInTime)} – {timeOnly(selected.originalPunchOutTime)}
                    <span className="ml-2 text-on-surface-variant">
                      ({selected.originalWorkingMinutes ?? 0}m, {selected.originalStatus})
                    </span>
                  </p>
                )}
              </div>
              <div className="rounded-DEFAULT bg-primary-container p-md">
                <p className="text-body-sm text-on-primary-container">You are asking for</p>
                <p className="mt-xs text-body-md tabular-nums text-on-primary-container">
                  {timeOnly(selected.requestedPunchInTime)} – {timeOnly(selected.requestedPunchOutTime)}
                  {selected.requestedWorkMode && (
                    <span className="ml-2 opacity-80">({selected.requestedWorkMode})</span>
                  )}
                </p>
              </div>
            </div>

            <dl className="grid gap-md sm:grid-cols-2">
              <div className="sm:col-span-2">
                <dt className="text-body-sm text-on-surface-variant">Your explanation</dt>
                <dd className="text-body-md text-on-surface">{selected.reason}</dd>
              </div>
              <div>
                <dt className="text-body-sm text-on-surface-variant">Status</dt>
                <dd>
                  <StatusChip
                    label={selected.status}
                    tone={REQUEST_STATUS_TONE[selected.status] ?? 'neutral'}
                  />
                </dd>
              </div>
              <div>
                <dt className="text-body-sm text-on-surface-variant">Raised</dt>
                <dd className="text-body-md text-on-surface">
                  {new Date(selected.appliedAt).toLocaleString()}
                </dd>
              </div>
            </dl>

            {selected.approvals.length > 0 && (
              <div>
                <h3 className="mb-sm text-title-sm text-on-surface">Approval trail</h3>
                <ol className="flex flex-col gap-sm">
                  {selected.approvals.map((step) => (
                    <li
                      key={`${step.level}-${step.actedAt ?? 'pending'}`}
                      className="flex items-start gap-sm rounded-DEFAULT bg-surface-container p-md"
                    >
                      <Icon
                        name={
                          step.action === 'APPROVED'
                            ? 'check_circle'
                            : step.action === 'REJECTED'
                              ? 'cancel'
                              : 'schedule'
                        }
                        className="mt-0.5 text-[18px] text-on-surface-variant"
                      />
                      <div className="min-w-0">
                        <p className="text-body-md text-on-surface">
                          {step.approverName ?? 'Unassigned'}
                          <span className="ml-2 text-body-sm text-on-surface-variant">
                            {step.action}
                          </span>
                        </p>
                        {step.comments && (
                          <p className="text-body-sm text-on-surface-variant">{step.comments}</p>
                        )}
                        {step.actedAt && (
                          <p className="text-body-sm text-on-surface-variant">
                            {new Date(step.actedAt).toLocaleString()}
                          </p>
                        )}
                      </div>
                    </li>
                  ))}
                </ol>
              </div>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
};
