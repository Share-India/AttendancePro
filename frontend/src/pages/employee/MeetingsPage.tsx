import { useState } from 'react';
import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import { Link } from 'react-router-dom';

import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { DataTable, type Column } from '../../components/table/DataTable';
import { ROUTES } from '../../constants/routes';
import { meetingApi, openMeeting } from '../../features/meeting/meetingApi';
import { ApiError } from '../../types/api';
import {
  MEETING_STATUS_LABEL,
  MEETING_STATUS_TONE,
  PARTICIPANT_STATUS_LABEL,
  PARTICIPANT_STATUS_TONE,
  PROVIDER_LABEL,
  type Meeting,
} from '../../types/meeting';

const describeFailure = (error: unknown): { title: string; message: string } => {
  if (!(error instanceof ApiError)) {
    return { title: 'Could not open the meeting', message: 'Something went wrong. Try again.' };
  }
  switch (error.errorCode) {
    case 'ACCESS_DENIED':
      return { title: 'Not on the list', message: error.message };
    case 'OUTSIDE_PUNCH_WINDOW':
      return { title: 'Not open right now', message: error.message };
    case 'POLICY_VIOLATION':
      return { title: 'Cannot join', message: error.message };
    default:
      return { title: 'Could not open the meeting', message: error.message };
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

const timeLabel = (iso: string | null): string =>
  iso ? new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) : '—';

export const MeetingsPage = () => {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [notice, setNotice] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const { data, isLoading, error } = useQuery({
    queryKey: ['meeting', 'my', page, size],
    queryFn: () => meetingApi.mine({ page, size }),
    placeholderData: keepPreviousData,
    refetchInterval: 60_000,
  });

  const join = useMutation({
    mutationFn: (id: number) => openMeeting(id),
    onSuccess: () => {
      setNotice('Your attendance is recorded. The meeting opened in a new tab.');
      queryClient.invalidateQueries({ queryKey: ['meeting'] });
    },
  });

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
            {PROVIDER_LABEL[row.provider] ?? row.provider} · {row.hostName}
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
      header: 'Meeting',
      render: (row) => (
        <StatusChip
          label={MEETING_STATUS_LABEL[row.status] ?? row.status}
          tone={MEETING_STATUS_TONE[row.status] ?? 'neutral'}
        />
      ),
    },
    {
      key: 'mine',
      header: 'You',
      render: (row) =>
        row.myStatus ? (
          <div className="flex flex-col gap-1">
            <StatusChip
              label={PARTICIPANT_STATUS_LABEL[row.myStatus] ?? row.myStatus}
              tone={PARTICIPANT_STATUS_TONE[row.myStatus] ?? 'neutral'}
            />
            {row.myJoinedAt ? (
              <span className="text-body-sm tabular-nums text-on-surface-variant">
                {timeLabel(row.myJoinedAt)}
              </span>
            ) : null}
          </div>
        ) : (
          <span className="text-on-surface-variant">—</span>
        ),
    },
    {
      key: 'action',
      header: '',
      className: 'text-right',
      headerClassName: 'text-right',
      render: (row) =>
        row.canJoin ? (
          <button
            type="button"
            className="btn-primary inline-flex items-center gap-2"
            disabled={join.isPending}
            onClick={(event) => {
              event.stopPropagation();
              setNotice(null);
              join.mutate(row.id);
            }}
          >
            {join.isPending && join.variables === row.id ? (
              <Spinner className="h-4 w-4" />
            ) : (
              <Icon name="videocam" />
            )}
            Join
          </button>
        ) : null,
    },
  ];

  const failure = join.error ? describeFailure(join.error) : null;

  return (
    <div className="flex flex-col gap-lg">
      <PageHeader
        title="Meetings"
        description="Join through this page so your attendance is recorded."
      />

      {notice ? <Alert variant="success">{notice}</Alert> : null}
      {failure ? (
        <Alert variant="error" title={failure.title}>
          {failure.message}
        </Alert>
      ) : null}

      <Alert variant="info" title="How attendance is recorded">
        Opening the link from this page is what marks you as present. Joining from a calendar
        invite or a pasted link will not be seen.
      </Alert>

      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        rowKey={(row) => row.id}
        loading={isLoading}
        error={
          error instanceof ApiError ? error.message : error ? 'Could not load your meetings.' : null
        }
        emptyTitle="No meetings yet"
        emptyMessage="Meetings you are invited to will appear here."
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
    </div>
  );
};
