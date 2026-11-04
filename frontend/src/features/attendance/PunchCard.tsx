import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import {
  GeolocationFailure,
  describeDevice,
  getCurrentPosition,
} from '../../services/geolocationService';
import { ApiError } from '../../types/api';
import {
  STATUS_TONE,
  humaniseStatus,
  type GeofenceRejection,
} from '../../types/attendance';
import { attendanceApi } from './attendanceApi';
import { formatDuration, formatMinutes, useLiveTimer } from './useLiveTimer';

/** Turns any punch failure into a title and a next step. */
const describeFailure = (error: unknown): { title: string; message: string; variant: 'error' | 'warning' } => {
  if (error instanceof GeolocationFailure) {
    return { title: error.message, message: error.guidance, variant: 'warning' };
  }

  if (!(error instanceof ApiError)) {
    return {
      title: 'Could not record your punch',
      message: 'Something went wrong. Please try again.',
      variant: 'error',
    };
  }

  switch (error.errorCode) {
    case 'OUTSIDE_GEOFENCE': {
      const detail = error.details as GeofenceRejection | undefined;
      const distance = detail?.distanceMeters;
      const radius = detail?.allowedRadiusMeters;
      const place = detail?.nearestLocation ?? detail?.nearestOffice;

      return {
        title: 'You are too far from the office',
        message:
          distance != null && radius != null
            ? `You are ${Math.round(distance)} m from ${place ?? 'your office'}, ` +
              `and punches are accepted within ${radius} m. Move closer and try again.`
            : error.message,
        variant: 'warning',
      };
    }
    case 'INSUFFICIENT_LOCATION_ACCURACY':
      return { title: 'Your location is not precise enough', message: error.message, variant: 'warning' };
    case 'ALREADY_PUNCHED_IN':
      return { title: 'Already punched in', message: 'Your day has already started.', variant: 'warning' };
    case 'ALREADY_PUNCHED_OUT':
      return { title: 'Already punched out', message: 'Your day is already complete.', variant: 'warning' };
    case 'NOT_PUNCHED_IN':
      return { title: 'Not punched in', message: 'Punch in before punching out.', variant: 'warning' };
    case 'NO_SHIFT_ASSIGNED':
      return { title: 'No shift assigned', message: error.message, variant: 'error' };
    case 'OUTSIDE_PUNCH_WINDOW':
      return { title: 'Too early to punch in', message: error.message, variant: 'warning' };
    case 'WFH_NOT_APPROVED':
      return { title: 'Work from home not approved', message: error.message, variant: 'warning' };
    default:
      return { title: 'Could not record your punch', message: error.message, variant: 'error' };
  }
};

export const PunchCard = () => {
  const queryClient = useQueryClient();
  const [failure, setFailure] = useState<ReturnType<typeof describeFailure> | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [locating, setLocating] = useState(false);

  const { data: status, isLoading } = useQuery({
    queryKey: ['attendance', 'status'],
    queryFn: attendanceApi.status,
    // The server owns the minute count; this keeps it honest without the
    // client ever deriving elapsed time on its own.
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });

  const { data: breakTypes } = useQuery({
    queryKey: ['attendance', 'breakTypes'],
    queryFn: attendanceApi.breakTypes,
    staleTime: 10 * 60 * 1000,
  });

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['attendance'] });
    queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  };

  const withPosition = async (action: (payload: Record<string, unknown>) => Promise<unknown>) => {
    setFailure(null);
    setNotice(null);
    setLocating(true);
    try {
      const position = await getCurrentPosition();
      await action({
        latitude: position.latitude,
        longitude: position.longitude,
        accuracyMeters: position.accuracyMeters,
        capturedAt: position.capturedAt,
        workMode: 'OFFICE',
        deviceInfo: describeDevice(),
      });
    } finally {
      setLocating(false);
    }
  };

  const punchIn = useMutation({
    mutationFn: () => withPosition((payload) => attendanceApi.punchIn(payload)),
    onSuccess: () => {
      setNotice('Punched in.');
      refresh();
    },
    onError: (error) => setFailure(describeFailure(error)),
  });

  const punchOut = useMutation({
    mutationFn: () => withPosition((payload) => attendanceApi.punchOut(payload)),
    onSuccess: () => {
      setNotice('Punched out.');
      refresh();
    },
    onError: (error) => setFailure(describeFailure(error)),
  });

  const startBreak = useMutation({
    mutationFn: (code: string) => attendanceApi.startBreak(code),
    onSuccess: () => {
      setFailure(null);
      refresh();
    },
    onError: (error) => setFailure(describeFailure(error)),
  });

  const endBreak = useMutation({
    mutationFn: () => attendanceApi.endBreak(),
    onSuccess: () => {
      setFailure(null);
      refresh();
    },
    onError: (error) => setFailure(describeFailure(error)),
  });

  const busy =
    locating || punchIn.isPending || punchOut.isPending || startBreak.isPending || endBreak.isPending;

  // While on break the work timer freezes and the break timer runs instead.
  const workSeconds = useLiveTimer(
    status?.punchInTime ?? null,
    status?.serverTime ?? null,
    status?.breakMinutes ?? 0,
    Boolean(status?.punchInTime) && !status?.punchOutTime && !status?.onBreak,
  );

  const breakSeconds = useLiveTimer(
    status?.currentBreakStartedAt ?? null,
    status?.serverTime ?? null,
    0,
    Boolean(status?.onBreak),
  );

  if (isLoading || !status) {
    return (
      <section className="card flex items-center justify-center p-lg" style={{ minHeight: 280 }}>
        <Spinner className="h-8 w-8 text-primary" />
      </section>
    );
  }

  const settled = Boolean(status.punchOutTime);
  const displaySeconds = status.onBreak ? breakSeconds : workSeconds;

  return (
    <section className="card p-lg">
      <div className="mb-md flex flex-wrap items-start justify-between gap-md">
        <div>
          <h2 className="text-headline-md text-on-surface">Time Tracking</h2>
          <div className="mt-sm flex flex-wrap items-center gap-2">
            <StatusChip
              label={humaniseStatus(status.status)}
              tone={STATUS_TONE[status.status] ?? 'neutral'}
            />
            {status.lateMinutes > 0 && (
              <StatusChip label={`${status.lateMinutes} min late`} tone="warning" />
            )}
            {status.suspicious && <StatusChip label="Flagged for review" tone="error" />}
          </div>
        </div>

        {status.punchInTime && (
          <div className="text-right">
            <p className="text-body-sm text-on-surface-variant">Working since</p>
            <p className="font-mono text-headline-md tabular-nums text-primary">
              {new Date(status.punchInTime).toLocaleTimeString(undefined, {
                hour: '2-digit',
                minute: '2-digit',
              })}
            </p>
          </div>
        )}
      </div>

      {failure && (
        <Alert variant={failure.variant} title={failure.title} className="mb-md">
          {failure.message}
        </Alert>
      )}

      {notice && !failure && (
        <Alert variant="success" className="mb-md">
          {notice}
        </Alert>
      )}

      {!failure && !notice && status.blockedReason && (
        <Alert variant="info" className="mb-md">
          {status.blockedReason}
        </Alert>
      )}

      <div className="border-y border-outline-variant/60 py-xl text-center">
        <p className="text-label-md uppercase text-on-surface-variant">
          {status.onBreak ? `${status.currentBreakType ?? 'Break'} duration` : 'Duration'}
        </p>
        <p
          className={`mt-sm font-mono text-[44px] leading-none tabular-nums ${
            status.onBreak ? 'text-warning' : status.punchInTime ? 'text-on-surface' : 'text-outline-variant'
          }`}
        >
          {formatDuration(displaySeconds)}
        </p>

        <dl className="mt-lg flex flex-wrap items-center justify-center gap-x-xl gap-y-sm text-center">
          <div>
            <dt className="text-body-sm text-on-surface-variant">Worked</dt>
            <dd className="text-body-lg tabular-nums text-on-surface">
              {formatMinutes(status.workedMinutes)}
            </dd>
          </div>
          <div>
            <dt className="text-body-sm text-on-surface-variant">Break</dt>
            <dd className="text-body-lg tabular-nums text-on-surface">
              {formatMinutes(status.breakMinutes)}
            </dd>
          </div>
          <div>
            <dt className="text-body-sm text-on-surface-variant">Expected</dt>
            <dd className="text-body-lg tabular-nums text-on-surface">
              {formatMinutes(status.expectedWorkMinutes)}
            </dd>
          </div>
          {status.overtimeMinutes > 0 && (
            <div>
              <dt className="text-body-sm text-on-surface-variant">Overtime</dt>
              <dd className="text-body-lg tabular-nums text-success">
                {formatMinutes(status.overtimeMinutes)}
              </dd>
            </div>
          )}
        </dl>
      </div>

      <div className="mt-md flex flex-col gap-md sm:flex-row sm:items-center sm:justify-between">
        <p className="flex items-center gap-2 text-body-md text-on-surface-variant">
          <Icon name="location_on" className="text-[18px]" />
          {status.punchInLocation
            ? `${status.punchInLocation}${settled ? '' : ' · verified'}`
            : status.shift
              ? `${status.shift.name} · ${status.shift.startTime}–${status.shift.endTime}`
              : 'No shift assigned'}
        </p>

        <div className="flex flex-wrap gap-sm">
          {status.canEndBreak && (
            <button
              type="button"
              className="btn-secondary"
              onClick={() => endBreak.mutate()}
              disabled={busy}
            >
              <Icon name="play_arrow" className="text-[18px]" />
              End break
            </button>
          )}

          {status.canStartBreak && (
            <BreakMenu
              options={breakTypes ?? []}
              disabled={busy}
              onSelect={(code) => startBreak.mutate(code)}
            />
          )}

          {status.canPunchOut && (
            <button
              type="button"
              className="btn-primary"
              onClick={() => punchOut.mutate()}
              disabled={busy}
            >
              {busy ? <Spinner className="h-4 w-4" /> : <Icon name="logout" className="text-[18px]" />}
              Punch Out
            </button>
          )}

          {status.canPunchIn && (
            <button
              type="button"
              className="btn-primary"
              onClick={() => punchIn.mutate()}
              disabled={busy}
            >
              {busy ? <Spinner className="h-4 w-4" /> : <Icon name="fingerprint" className="text-[18px]" />}
              {locating ? 'Finding your location' : 'Punch In'}
            </button>
          )}

          {settled && (
            <span className="inline-flex items-center gap-2 text-body-md text-success">
              <Icon name="check_circle" className="text-[18px]" />
              Day complete
            </span>
          )}
        </div>
      </div>
    </section>
  );
};

/** Break types as a small menu, so the choice is explicit rather than assumed. */
const BreakMenu = ({
  options,
  disabled,
  onSelect,
}: {
  options: Array<{ code: string; name: string; maxMinutes: number }>;
  disabled: boolean;
  onSelect: (code: string) => void;
}) => {
  const [open, setOpen] = useState(false);

  if (options.length === 0) {
    return null;
  }

  return (
    <div className="relative">
      <button
        type="button"
        className="btn-secondary"
        onClick={() => setOpen((value) => !value)}
        disabled={disabled}
        aria-haspopup="menu"
        aria-expanded={open}
      >
        <Icon name="pause" className="text-[18px]" />
        Start break
        <Icon name="expand_more" className="text-[16px]" />
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 z-20 mt-1 w-56 animate-fade-in overflow-hidden rounded-md border border-outline-variant/60 bg-surface-container-lowest shadow-overlay"
        >
          {options.map((option) => (
            <button
              key={option.code}
              type="button"
              role="menuitem"
              onClick={() => {
                setOpen(false);
                onSelect(option.code);
              }}
              className="flex w-full items-center justify-between px-md py-2.5 text-left text-body-md text-on-surface transition-colors hover:bg-surface-container-low"
            >
              {option.name}
              <span className="text-body-sm text-on-surface-variant">{option.maxMinutes} min</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
};
