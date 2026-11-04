import { useState } from 'react';
import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';

import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { DataTable, type Column } from '../../components/table/DataTable';
import { SelectField } from '../../components/form/SelectField';
import { ROUTES } from '../../constants/routes';
import { meetingApi } from '../../features/meeting/meetingApi';
import { ApiError } from '../../types/api';
import {
  MEETING_STATUS_LABEL,
  MEETING_STATUS_TONE,
  PROVIDER_LABEL,
  type Meeting,
} from '../../types/meeting';

const STATUS_OPTIONS = [
  { value: '', label: 'All statuses' },
  { value: 'DRAFT', label: 'Draft' },
  { value: 'SCHEDULED', label: 'Scheduled' },
  { value: 'OPEN', label: 'In progress' },
  { value: 'FINALISED', label: 'Finished' },
  { value: 'CANCELLED', label: 'Cancelled' },
];

const describeFailure = (error: unknown): { title: string; message: string } => {
  if (!(error instanceof ApiError)) {
    return { title: 'That did not work', message: 'Something went wrong. Try again.' };
  }
  switch (error.errorCode) {
    case 'POLICY_VIOLATION':
      return { title: 'Not allowed at this point', message: error.message };
    case 'ACCESS_DENIED':
      return { title: 'Not yours to manage', message: error.message };
    case 'VALIDATION_ERROR':
      return { title: 'Check the details', message: error.message };
    default:
      return { title: 'That did not work', message: error.message };
  }
};

const whenLabel = (iso: string): string =>
  new Date(iso).toLocaleString(undefined, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });

export const ManageMeetingsPage = () => {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [status, setStatus] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [cancelling, setCancelling] = useState<Meeting | null>(null);
  const [reason, setReason] = useState('');

  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const { data, isLoading, error } = useQuery({
    queryKey: ['meeting', 'manage', page, size, status],
    queryFn: () => meetingApi.manage({ page, size, status: status || undefined }),
    placeholderData: keepPreviousData,
  });

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['meeting'] });

  const publish = useMutation({
    mutationFn: (id: number) => meetingApi.publish(id),
    onSuccess: (meeting) => {
      const who = meeting.invitedCount === 1 ? '1 person' : `${meeting.invitedCount} people`;
      setNotice(`${who} invited to "${meeting.title}".`);
      refresh();
    },
  });

  const start = useMutation({
    mutationFn: (id: number) => meetingApi.start(id),
    onSuccess: () => {
      setNotice('Meeting started.');
      refresh();
    },
  });

  const end = useMutation({
    mutationFn: (id: number) => meetingApi.end(id),
    onSuccess: () => {
      setNotice('Meeting ended. Attendance has been worked out.');
      refresh();
    },
  });

  const cancel = useMutation({
    mutationFn: (input: { id: number; reason: string }) =>
      meetingApi.cancel(input.id, input.reason),
    onSuccess: () => {
      setNotice('Meeting cancelled and everyone told.');
      setCancelling(null);
      setReason('');
      refresh();
    },
  });

  const busy = publish.isPending || start.isPending || end.isPending || cancel.isPending;

  const columns: Column<Meeting>[] = [
    {
      key: 'title',
      header: 'Meeting',
      render: (row) => (
        <div className="min-w-0">
          <Link
            to={ROUTES.meetingDetail(row.id)}
            className="block truncate font-medium text-primary hover:underline"
          >
            {row.title}
          </Link>
          <p className="truncate text-body-sm text-on-surface-variant">
            {row.meetingCode} · {PROVIDER_LABEL[row.provider] ?? row.provider}
          </p>
        </div>
      ),
    },
    {
      key: 'when',
      header: 'When',
      render: (row) => (
        <span className="whitespace-nowrap tabular-nums">{whenLabel(row.scheduledStartAt)}</span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <StatusChip
          label={MEETING_STATUS_LABEL[row.status] ?? row.status}
          tone={MEETING_STATUS_TONE[row.status] ?? 'neutral'}
        />
      ),
    },
    {
      key: 'turnout',
      header: 'Opened link',
      render: (row) =>
        row.status === 'DRAFT' ? (
          <span className="text-on-surface-variant">Not published</span>
        ) : (
          <span className="tabular-nums">
            {row.joinedCount} of {row.invitedCount}
          </span>
        ),
    },
    {
      key: 'actions',
      header: '',
      className: 'text-right',
      headerClassName: 'text-right',
      render: (row) => (
        <div className="flex justify-end gap-2">
          {row.status === 'DRAFT' ? (
            <button
              type="button"
              className="btn-primary"
              disabled={busy}
              onClick={() => {
                setNotice(null);
                publish.mutate(row.id);
              }}
            >
              Publish
            </button>
          ) : null}
          {row.canStart ? (
            <button
              type="button"
              className="btn-primary"
              disabled={busy}
              onClick={() => {
                setNotice(null);
                start.mutate(row.id);
              }}
            >
              Start
            </button>
          ) : null}
          {row.canEnd ? (
            <button
              type="button"
              className="btn-secondary"
              disabled={busy}
              onClick={() => {
                setNotice(null);
                end.mutate(row.id);
              }}
            >
              End
            </button>
          ) : null}
          {row.canEdit ? (
            <button
              type="button"
              className="btn-ghost"
              onClick={() => navigate(ROUTES.meetingEdit(row.id))}
            >
              Edit
            </button>
          ) : null}
          {row.canCancel ? (
            <button
              type="button"
              className="btn-ghost"
              onClick={() => {
                setNotice(null);
                setCancelling(row);
              }}
            >
              Cancel
            </button>
          ) : null}
          <Link to={ROUTES.meetingAttendance(row.id)} className="btn-ghost">
            Attendance
          </Link>
        </div>
      ),
    },
  ];

  const mutationError = publish.error ?? start.error ?? end.error ?? cancel.error;
  const failure = mutationError ? describeFailure(mutationError) : null;

  return (
    <div className="flex flex-col gap-lg">
      <PageHeader
        title="Manage meetings"
        description="Schedule a meeting, publish it to freeze the guest list, then end it to record who came."
        actions={
          <Link to={ROUTES.meetingNew} className="btn-primary inline-flex items-center gap-2">
            <Icon name="add" />
            New meeting
          </Link>
        }
      />

      {notice ? <Alert variant="success">{notice}</Alert> : null}
      {failure ? (
        <Alert variant="error" title={failure.title}>
          {failure.message}
        </Alert>
      ) : null}

      <div className="max-w-xs">
        <SelectField
          label="Status"
          options={STATUS_OPTIONS}
          value={status}
          onChange={(event) => {
            setStatus(event.target.value);
            setPage(0);
          }}
        />
      </div>

      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        rowKey={(row) => row.id}
        loading={isLoading}
        error={error instanceof ApiError ? error.message : error ? 'Could not load meetings.' : null}
        emptyTitle="No meetings"
        emptyMessage="Create one and publish it to invite your team."
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

      <Modal
        open={cancelling !== null}
        onClose={() => setCancelling(null)}
        title="Cancel this meeting?"
        description={cancelling ? cancelling.title : undefined}
        footer={
          <div className="flex justify-end gap-sm">
            <button type="button" className="btn-ghost" onClick={() => setCancelling(null)}>
              Keep it
            </button>
            <button
              type="button"
              className="btn-primary inline-flex items-center gap-2"
              disabled={!reason.trim() || cancel.isPending}
              onClick={() =>
                cancelling && cancel.mutate({ id: cancelling.id, reason: reason.trim() })
              }
            >
              {cancel.isPending ? <Spinner className="h-4 w-4" /> : null}
              Cancel meeting
            </button>
          </div>
        }
      >
        <label className="field-label" htmlFor="cancel-reason">
          Why?
        </label>
        <textarea
          id="cancel-reason"
          className="field-input"
          rows={3}
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="Everyone invited will see this."
        />
      </Modal>
    </div>
  );
};
