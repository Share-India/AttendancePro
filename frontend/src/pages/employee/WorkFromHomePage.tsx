import { useState } from 'react';
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
import { wfhApi } from '../../features/request/requestApi';
import { ApiError } from '../../types/api';
import {
  REQUEST_STATUS_TONE,
  WORK_LOCATIONS,
  type WorkFromHomeRequest,
} from '../../types/request';

interface FormValues {
  fromDate: string;
  toDate: string;
  workLocation: string;
  contactNumber: string;
  reason: string;
}

const isoToday = (): string => new Date().toISOString().slice(0, 10);

const describeFailure = (error: unknown): { title: string; message: string } => {
  if (!(error instanceof ApiError)) {
    return { title: 'Could not submit', message: 'Something went wrong. Please try again.' };
  }
  switch (error.errorCode) {
    case 'OVERLAPPING_REQUEST':
    case 'DUPLICATE_REQUEST':
      return { title: 'Those days clash', message: error.message };
    case 'POLICY_VIOLATION':
      return { title: 'Not allowed by policy', message: error.message };
    default:
      return { title: 'Could not submit', message: error.message };
  }
};

export const WorkFromHomePage = () => {
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [selected, setSelected] = useState<WorkFromHomeRequest | null>(null);
  const [submitted, setSubmitted] = useState<WorkFromHomeRequest | null>(null);

  const {
    register,
    handleSubmit,
    watch,
    reset,
    formState: { errors },
  } = useForm<FormValues>({
    defaultValues: {
      fromDate: '',
      toDate: '',
      workLocation: 'HOME',
      contactNumber: '',
      reason: '',
    },
  });

  const fromDate = watch('fromDate');

  const { data, isLoading, error } = useQuery({
    queryKey: ['wfh', 'my', page, size],
    queryFn: () => wfhApi.myRequests({ page, size }),
    placeholderData: keepPreviousData,
  });

  const apply = useMutation({
    mutationFn: (values: FormValues) =>
      wfhApi.apply({
        fromDate: values.fromDate,
        toDate: values.toDate || values.fromDate,
        workLocation: values.workLocation || null,
        contactNumber: values.contactNumber || null,
        reason: values.reason,
      }),
    onSuccess: (created) => {
      setSubmitted(created);
      reset({ fromDate: '', toDate: '', workLocation: 'HOME', contactNumber: '', reason: '' });
      queryClient.invalidateQueries({ queryKey: ['wfh'] });
    },
  });

  const act = useMutation({
    mutationFn: ({ id, kind }: { id: number; kind: 'withdraw' | 'cancel' }) =>
      kind === 'withdraw' ? wfhApi.withdraw(id) : wfhApi.cancel(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['wfh'] });
      setSelected(null);
    },
  });

  const columns: Column<WorkFromHomeRequest>[] = [
    {
      key: 'dates',
      header: 'Days',
      className: 'whitespace-nowrap tabular-nums',
      render: (row) =>
        row.fromDate === row.toDate
          ? new Date(row.fromDate).toLocaleDateString()
          : `${new Date(row.fromDate).toLocaleDateString()} – ${new Date(row.toDate).toLocaleDateString()}`,
    },
    {
      key: 'count',
      header: 'Working days',
      className: 'tabular-nums',
      // The span and the count differ whenever a weekend or holiday falls
      // inside it, so show both rather than leaving the gap unexplained.
      render: (row) =>
        row.totalDays === row.calendarDays
          ? `${row.totalDays}`
          : `${row.totalDays} of ${row.calendarDays}`,
    },
    {
      key: 'where',
      header: 'From',
      render: (row) =>
        WORK_LOCATIONS.find((option) => option.value === row.workLocation)?.label ??
        row.workLocation ??
        '—',
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
        title="Work From Home"
        description="Approved days let you punch in without being at an office. Until a day is approved, the geofence still applies."
      />

      {submitted && (
        <Alert variant="success" title="Request sent" className="mb-lg">
          {submitted.requestNumber} — {submitted.totalDays} working day
          {submitted.totalDays === 1 ? '' : 's'}
          {submitted.totalDays !== submitted.calendarDays &&
            ` out of ${submitted.calendarDays} calendar days`}
          .{' '}
          {submitted.currentApproverName
            ? `${submitted.currentApproverName} has been asked to review it.`
            : 'It is waiting for an approver.'}
        </Alert>
      )}

      {failure && (
        <Alert variant="error" title={failure.title} className="mb-lg">
          {failure.message}
        </Alert>
      )}

      <div className="grid gap-lg lg:grid-cols-[minmax(0,380px)_minmax(0,1fr)]">
        <section className="surface-card h-fit p-lg">
          <h2 className="mb-md text-title-md text-on-surface">Request days at home</h2>

          <form
            className="flex flex-col gap-md"
            onSubmit={handleSubmit((values) => {
              setSubmitted(null);
              apply.mutate(values);
            })}
            noValidate
          >
            <div className="grid gap-md sm:grid-cols-2">
              <TextField
                label="From"
                type="date"
                min={isoToday()}
                error={errors.fromDate?.message}
                {...register('fromDate', { required: 'Pick the first day' })}
              />
              <TextField
                label="To"
                type="date"
                min={fromDate || isoToday()}
                hint="Leave empty for a single day"
                {...register('toDate')}
              />
            </div>

            <SelectField
              label="Working from"
              options={WORK_LOCATIONS.map((option) => ({
                value: option.value,
                label: option.label,
              }))}
              {...register('workLocation')}
            />

            <TextField
              label="Reachable on"
              type="tel"
              leadingIcon="call"
              hint="Optional, but it saves someone hunting for you"
              {...register('contactNumber')}
            />

            <div>
              <label htmlFor="wfh-reason" className="field-label">
                Why
              </label>
              <textarea
                id="wfh-reason"
                rows={3}
                maxLength={1000}
                className={`field-input resize-y ${errors.reason ? 'field-input-error' : ''}`}
                placeholder="What you will be doing, or why you cannot be in the office."
                {...register('reason', { required: 'Give a reason' })}
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
          </form>
        </section>

        <section>
          <h2 className="mb-md text-title-md text-on-surface">Your requests</h2>
          <DataTable
            columns={columns}
            rows={data?.content ?? []}
            rowKey={(row) => row.id}
            loading={isLoading}
            error={
              error instanceof ApiError ? error.message : error ? 'Could not load your requests.' : null
            }
            emptyTitle="No work-from-home requests"
            emptyMessage="Days you request appear here, with the approval trail."
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
        title={selected ? `Work from home · ${selected.totalDays} day${selected.totalDays === 1 ? '' : 's'}` : ''}
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
                  disabled={act.isPending}
                  onClick={() => act.mutate({ id: selected.id, kind: 'withdraw' })}
                >
                  {act.isPending ? <Spinner className="h-4 w-4" /> : <Icon name="undo" className="text-[18px]" />}
                  Withdraw
                </button>
              )}
              {selected.canCancel && (
                <button
                  type="button"
                  className="btn-secondary text-error"
                  disabled={act.isPending}
                  onClick={() => act.mutate({ id: selected.id, kind: 'cancel' })}
                >
                  {act.isPending ? <Spinner className="h-4 w-4" /> : <Icon name="event_busy" className="text-[18px]" />}
                  Cancel
                </button>
              )}
            </>
          ) : undefined
        }
      >
        {selected && (
          <div className="flex flex-col gap-lg">
            <dl className="grid gap-md sm:grid-cols-2">
              <div>
                <dt className="text-body-sm text-on-surface-variant">Dates</dt>
                <dd className="text-body-md tabular-nums text-on-surface">
                  {selected.fromDate} to {selected.toDate}
                </dd>
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
              <div className="sm:col-span-2">
                <dt className="text-body-sm text-on-surface-variant">Reason</dt>
                <dd className="text-body-md text-on-surface">{selected.reason}</dd>
              </div>
              {selected.contactNumber && (
                <div>
                  <dt className="text-body-sm text-on-surface-variant">Reachable on</dt>
                  <dd className="text-body-md text-on-surface">{selected.contactNumber}</dd>
                </div>
              )}
            </dl>

            {selected.days.length > 0 && (
              <div>
                <h3 className="mb-sm text-title-sm text-on-surface">
                  Days covered
                  <span className="ml-2 text-body-sm font-normal text-on-surface-variant">
                    weekends and holidays already removed
                  </span>
                </h3>
                <ul className="flex flex-wrap gap-xs">
                  {selected.days.map((day) => (
                    <li
                      key={day}
                      className="rounded-full bg-surface-container-high px-3 py-1 text-body-sm tabular-nums text-on-surface-variant"
                    >
                      {new Date(day).toLocaleDateString(undefined, {
                        weekday: 'short',
                        day: 'numeric',
                        month: 'short',
                      })}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {selected.status === 'APPROVED' && (
              <Alert variant="info" title="On these days">
                Punch in as usual and choose <strong>Work from home</strong>. The geofence will not
                be applied.
              </Alert>
            )}

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
