import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';

import { Alert } from '../../components/common/Alert';
import { PageHeader } from '../../components/common/PageHeader';
import { StatusChip } from '../../components/common/StatusChip';
import { DataTable, type Column } from '../../components/table/DataTable';
import { meetingApi } from '../../features/meeting/meetingApi';
import { ApiError } from '../../types/api';
import {
  EXCUSE_LABEL,
  PARTICIPANT_STATUS_LABEL,
  PARTICIPANT_STATUS_TONE,
  type MeetingParticipant,
} from '../../types/meeting';

const timeLabel = (iso: string | null): string =>
  iso ? new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) : '—';

export const MeetingAttendancePage = () => {
  const { id } = useParams<{ id: string }>();
  const meetingId = Number(id);

  const meeting = useQuery({
    queryKey: ['meeting', 'detail', meetingId],
    queryFn: () => meetingApi.get(meetingId),
  });

  const { data, isLoading, error } = useQuery({
    queryKey: ['meeting', 'participants', meetingId],
    queryFn: () => meetingApi.participants(meetingId),
  });

  const columns: Column<MeetingParticipant>[] = [
    {
      key: 'employee',
      header: 'Employee',
      render: (row) => (
        <div className="min-w-0">
          <p className="truncate font-medium">{row.employeeName}</p>
          <p className="truncate text-body-sm text-on-surface-variant">
            {row.employeeCode}
            {row.departmentName ? ` · ${row.departmentName}` : ''}
          </p>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Outcome',
      render: (row) => (
        <div className="flex flex-col gap-1">
          <StatusChip
            label={PARTICIPANT_STATUS_LABEL[row.status] ?? row.status}
            tone={PARTICIPANT_STATUS_TONE[row.status] ?? 'neutral'}
          />
          {row.excuseReason ? (
            <span className="text-body-sm text-on-surface-variant">
              {EXCUSE_LABEL[row.excuseReason] ?? row.excuseReason}
            </span>
          ) : null}
        </div>
      ),
    },
    {
      key: 'joinedAt',
      header: 'Link opened',
      render: (row) => <span className="tabular-nums">{timeLabel(row.firstJoinedAt)}</span>,
    },
    {
      key: 'clicks',
      header: 'Times opened',
      render: (row) => (
        <span className="tabular-nums">{row.clickCount > 0 ? row.clickCount : '—'}</span>
      ),
    },
    {
      key: 'minutes',
      header: 'Approx. minutes',
      // Loose null check on purpose: the API omits null fields rather than
      // sending them, so this arrives as undefined and a === null test would
      // render the string "~undefined".
      render: (row) => (
        <span className="tabular-nums">
          {row.attendedMinutesEstimate == null ? '—' : `~${row.attendedMinutesEstimate}`}
        </span>
      ),
    },
  ];

  const rows = data ?? [];
  const joined = rows.filter((r) => r.status === 'JOINED' || r.status === 'JOINED_LATE').length;
  const excused = rows.filter((r) => r.status === 'EXCUSED').length;
  const missed = rows.filter((r) => r.status === 'NOT_JOINED').length;

  return (
    <div className="flex flex-col gap-lg">
      <PageHeader
        title="Who joined"
        description={meeting.data ? `${meeting.data.title} · ${meeting.data.meetingCode}` : undefined}
      />

      <Alert variant="info" title="What these numbers mean">
        This records who opened the join link from inside this application. Somebody who dialled in
        from a calendar invite will show as not having opened it, and the minutes shown are the
        meeting&rsquo;s end time minus when they opened the link — an estimate, not a measurement.
      </Alert>

      <div className="grid gap-md sm:grid-cols-4">
        {[
          { label: 'Invited', value: rows.length },
          { label: 'Opened the link', value: joined },
          { label: 'Excused', value: excused },
          { label: 'Did not open', value: missed },
        ].map((stat) => (
          <div key={stat.label} className="card p-lg">
            <p className="field-label">{stat.label}</p>
            <p className="text-headline-md tabular-nums">{stat.value}</p>
          </div>
        ))}
      </div>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(row) => row.id}
        loading={isLoading}
        error={
          error instanceof ApiError ? error.message : error ? 'Could not load the roster.' : null
        }
        emptyTitle="Nobody on the list"
        emptyMessage="Publish the meeting to freeze its guest list."
        page={0}
        size={rows.length || 20}
        totalElements={rows.length}
        totalPages={1}
        onPageChange={() => undefined}
      />
    </div>
  );
};
