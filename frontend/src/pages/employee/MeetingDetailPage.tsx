import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';

import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { ROUTES } from '../../constants/routes';
import { useLiveTimer, formatDuration } from '../../features/attendance/useLiveTimer';
import { meetingApi, openMeeting } from '../../features/meeting/meetingApi';
import { ApiError } from '../../types/api';
import {
  MEETING_STATUS_LABEL,
  MEETING_STATUS_TONE,
  PARTICIPANT_STATUS_LABEL,
  PARTICIPANT_STATUS_TONE,
  PROVIDER_LABEL,
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
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    hour: '2-digit',
    minute: '2-digit',
  });

export const MeetingDetailPage = () => {
  const { id } = useParams<{ id: string }>();
  const meetingId = Number(id);
  const [notice, setNotice] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const { data: meeting, isLoading, error } = useQuery({
    queryKey: ['meeting', 'detail', meetingId],
    queryFn: () => meetingApi.get(meetingId),
  });

  // The live view is driven by the server's clock, not the browser's, for the
  // same reason the punch card is: a device with a wrong clock must not be
  // able to show a duration the server would disagree with.
  const { data: status } = useQuery({
    queryKey: ['meeting', 'status', meetingId],
    queryFn: () => meetingApi.status(meetingId),
    refetchInterval: 30_000,
    refetchOnWindowFocus: true,
    enabled: meeting?.status === 'OPEN',
  });

  const elapsed = useLiveTimer(
    status?.actualStartedAt ?? null,
    status?.serverTime ?? null,
    0,
    status?.status === 'OPEN',
  );

  const join = useMutation({
    mutationFn: () => openMeeting(meetingId),
    onSuccess: () => {
      setNotice('Your attendance is recorded. The meeting opened in a new tab.');
      queryClient.invalidateQueries({ queryKey: ['meeting'] });
    },
  });

  if (isLoading) {
    return (
      <div className="card flex items-center justify-center p-xl">
        <Spinner />
      </div>
    );
  }

  if (error || !meeting) {
    return (
      <Alert variant="error" title="Could not load the meeting">
        {error instanceof ApiError ? error.message : 'Try again in a moment.'}
      </Alert>
    );
  }

  const failure = join.error ? describeFailure(join.error) : null;

  return (
    <div className="flex flex-col gap-lg">
      <PageHeader
        title={meeting.title}
        description={`${meeting.meetingCode} · hosted by ${meeting.hostName}`}
        actions={
          meeting.canJoin ? (
            <button
              type="button"
              className="btn-primary inline-flex items-center gap-2"
              disabled={join.isPending}
              onClick={() => {
                setNotice(null);
                join.mutate();
              }}
            >
              {join.isPending ? <Spinner className="h-4 w-4" /> : <Icon name="videocam" />}
              Join meeting
            </button>
          ) : null
        }
      />

      {notice ? <Alert variant="success">{notice}</Alert> : null}
      {failure ? (
        <Alert variant="error" title={failure.title}>
          {failure.message}
        </Alert>
      ) : null}
      {meeting.status === 'CANCELLED' && meeting.cancellationReason ? (
        <Alert variant="warning" title="This meeting was cancelled">
          {meeting.cancellationReason}
        </Alert>
      ) : null}

      <div className="grid gap-lg lg:grid-cols-[minmax(0,1fr)_minmax(0,320px)]">
        <section className="card flex flex-col gap-md p-lg">
          <div className="flex flex-wrap items-center gap-sm">
            <StatusChip
              label={MEETING_STATUS_LABEL[meeting.status] ?? meeting.status}
              tone={MEETING_STATUS_TONE[meeting.status] ?? 'neutral'}
            />
            <span className="text-body-sm text-on-surface-variant">
              {PROVIDER_LABEL[meeting.provider] ?? meeting.provider}
            </span>
          </div>

          <div>
            <p className="field-label">When</p>
            <p className="text-body-lg">{whenLabel(meeting.scheduledStartAt)}</p>
          </div>

          {meeting.status === 'OPEN' && status?.actualStartedAt ? (
            <div>
              <p className="field-label">Running for</p>
              <p className="font-mono text-headline-md tabular-nums">{formatDuration(elapsed)}</p>
            </div>
          ) : null}

          {meeting.agenda ? (
            <div>
              <p className="field-label">Agenda</p>
              <p className="whitespace-pre-wrap text-body-md">{meeting.agenda}</p>
            </div>
          ) : null}
        </section>

        <aside className="card flex flex-col gap-md p-lg">
          <div>
            <p className="field-label">You</p>
            {meeting.myStatus ? (
              <StatusChip
                label={PARTICIPANT_STATUS_LABEL[meeting.myStatus] ?? meeting.myStatus}
                tone={PARTICIPANT_STATUS_TONE[meeting.myStatus] ?? 'neutral'}
              />
            ) : (
              <p className="text-body-md text-on-surface-variant">Not on the list</p>
            )}
          </div>

          <div>
            <p className="field-label">Opened the link</p>
            <p className="text-headline-md tabular-nums">
              {meeting.joinedCount}
              <span className="text-body-md text-on-surface-variant"> of {meeting.invitedCount}</span>
            </p>
            <p className="text-body-sm text-on-surface-variant">
              Counted from people opening the link here, not from the meeting itself.
            </p>
          </div>

          {meeting.canEdit || meeting.canEnd ? (
            <Link to={ROUTES.meetingAttendance(meeting.id)} className="btn-secondary text-center">
              See who joined
            </Link>
          ) : null}
        </aside>
      </div>
    </div>
  );
};
