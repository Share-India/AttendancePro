import { useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';

import { Alert } from '../../components/common/Alert';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { TextField } from '../../components/form/TextField';
import { DataTable, type Column } from '../../components/table/DataTable';
import { PERMISSIONS } from '../../constants/permissions';
import { ROUTES } from '../../constants/routes';
import { attendanceApi } from '../../features/attendance/attendanceApi';
import { useAuth } from '../../features/auth/useAuth';
import { employeeApi } from '../../features/employee/employeeApi';
import { ApiError } from '../../types/api';
import type { AttendanceRecord } from '../../types/attendance';

const STATUS_TONE: Record<string, 'neutral' | 'info' | 'success' | 'warning' | 'error'> = {
  PRESENT: 'success',
  WORK_FROM_HOME: 'success',
  HALF_DAY: 'warning',
  LATE: 'warning',
  MISSING_PUNCH_OUT: 'warning',
  ON_LEAVE: 'info',
  HOLIDAY: 'neutral',
  WEEKLY_OFF: 'neutral',
  ABSENT: 'error',
};

const timeLabel = (iso: string | null): string =>
  iso ? new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) : '—';

const dateLabel = (iso: string): string =>
  new Date(iso).toLocaleDateString(undefined, {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
  });

/** datetime-local wants local wall time; the API wants an instant. */
const toInstant = (local: string): string | null =>
  local ? new Date(local).toISOString() : null;

const toLocalInput = (iso: string | null): string => {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
};

const describeFailure = (error: unknown): { title: string; message: string } => {
  if (!(error instanceof ApiError)) {
    return { title: 'That did not work', message: 'Something went wrong. Try again.' };
  }
  switch (error.errorCode) {
    case 'POLICY_VIOLATION':
      return { title: 'Not allowed', message: error.message };
    case 'INVALID_DATE_RANGE':
    case 'VALIDATION_ERROR':
      return { title: 'Check the times', message: error.message };
    case 'NO_SHIFT_ASSIGNED':
      return { title: 'No shift on that day', message: error.message };
    case 'ACCESS_DENIED':
      return { title: 'Not yours to change', message: error.message };
    default:
      return { title: 'That did not work', message: error.message };
  }
};

export const EmployeeAttendancePage = () => {
  const { id } = useParams<{ id: string }>();
  const employeeId = Number(id);
  const { hasPermission } = useAuth();
  const queryClient = useQueryClient();

  const canOverride = hasPermission(PERMISSIONS.ATTENDANCE_OVERRIDE);

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(31);
  const [editing, setEditing] = useState<AttendanceRecord | null>(null);
  const [punchIn, setPunchIn] = useState('');
  const [punchOut, setPunchOut] = useState('');
  const [clearOut, setClearOut] = useState(false);
  const [reason, setReason] = useState('');
  const [notice, setNotice] = useState<string | null>(null);

  const employee = useQuery({
    queryKey: ['employee', 'detail', employeeId],
    queryFn: () => employeeApi.findById(employeeId),
  });

  const history = useQuery({
    queryKey: ['attendance', 'employee', employeeId, page, size],
    queryFn: () => attendanceApi.employeeHistory(employeeId, { page, size }),
    placeholderData: keepPreviousData,
  });

  const override = useMutation({
    mutationFn: () =>
      attendanceApi.override(employeeId, editing!.attendanceDate, {
        punchInTime: toInstant(punchIn),
        punchOutTime: clearOut ? null : toInstant(punchOut),
        clearPunchOut: clearOut,
        reason: reason.trim(),
      }),
    onSuccess: () => {
      setNotice(
        `Times updated for ${dateLabel(editing!.attendanceDate)}. ` +
          'The change is in the audit trail and visible on the employee’s own record.',
      );
      closeEditor();
      queryClient.invalidateQueries({ queryKey: ['attendance'] });
    },
  });

  const openEditor = (row: AttendanceRecord) => {
    setEditing(row);
    setPunchIn(toLocalInput(row.punchInTime));
    setPunchOut(toLocalInput(row.punchOutTime));
    setClearOut(false);
    setReason('');
    setNotice(null);
  };

  const closeEditor = () => {
    setEditing(null);
    setReason('');
  };

  const columns: Column<AttendanceRecord>[] = [
    {
      key: 'date',
      header: 'Date',
      render: (row) => (
        <div className="min-w-0">
          <p className="whitespace-nowrap font-medium">{dateLabel(row.attendanceDate)}</p>
          {row.regularized ? (
            <p className="text-body-sm text-on-surface-variant">Corrected</p>
          ) : null}
        </div>
      ),
    },
    { key: 'in', header: 'In', render: (row) => <span className="tabular-nums">{timeLabel(row.punchInTime)}</span> },
    { key: 'out', header: 'Out', render: (row) => <span className="tabular-nums">{timeLabel(row.punchOutTime)}</span> },
    { key: 'worked', header: 'Worked', render: (row) => <span className="tabular-nums">{row.workingHours}</span> },
    {
      key: 'late',
      header: 'Late / early',
      hideOnMobile: true,
      render: (row) => (
        <span className="tabular-nums">
          {row.lateMinutes > 0 ? `${row.lateMinutes}m late` : ''}
          {row.lateMinutes > 0 && row.earlyExitMinutes > 0 ? ' · ' : ''}
          {row.earlyExitMinutes > 0 ? `${row.earlyExitMinutes}m early` : ''}
          {row.lateMinutes === 0 && row.earlyExitMinutes === 0 ? '—' : ''}
        </span>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <div className="flex flex-col gap-1">
          <StatusChip
            label={row.attendanceStatus.replace(/_/g, ' ')}
            tone={STATUS_TONE[row.attendanceStatus] ?? 'neutral'}
          />
          {/*
            No "set by an administrator" marker. A day corrected here is
            presented as an ordinary regularization, by decision of the system
            owner; the "Corrected" flag on the date column is the same one a
            requested-and-approved correction carries. The audit log is where
            the distinction lives, and only SUPER_ADMIN can read it.
          */}
        </div>
      ),
    },
  ];

  if (canOverride) {
    columns.push({
      key: 'actions',
      header: '',
      className: 'text-right',
      headerClassName: 'text-right',
      render: (row) => (
        <div className="flex justify-end">
          <button type="button" className="btn-secondary" onClick={() => openEditor(row)}>
            Change times
          </button>
        </div>
      ),
    });
  }

  const failure = override.error ? describeFailure(override.error) : null;

  return (
    <div className="flex flex-col gap-lg">
      <PageHeader
        title={employee.data ? employee.data.fullName : 'Attendance'}
        description={
          employee.data
            ? `${employee.data.employeeCode} · attendance history`
            : undefined
        }
        actions={
          <Link to={ROUTES.employeeDetail(employeeId)} className="btn-ghost">
            Back to profile
          </Link>
        }
      />

      {notice ? <Alert variant="success">{notice}</Alert> : null}

      {canOverride ? (
        <Alert variant="warning" title="You can set these times directly">
          Changing a time here skips the regularization request and its approval, so nobody else
          checks it. The day is then shown everywhere as an ordinary corrected day. Your reason and
          the previous values go to the audit trail, which only you can read, and the original
          punches are kept. Prefer a regularization where one is possible.
        </Alert>
      ) : null}

      <DataTable
        columns={columns}
        rows={history.data?.content ?? []}
        rowKey={(row) => row.id}
        loading={history.isLoading}
        error={
          history.error instanceof ApiError
            ? history.error.message
            : history.error
              ? 'Could not load this history.'
              : null
        }
        emptyTitle="No attendance yet"
        emptyMessage="Nothing has been recorded for this employee."
        page={history.data?.page ?? 0}
        size={size}
        totalElements={history.data?.totalElements ?? 0}
        totalPages={history.data?.totalPages ?? 0}
        onPageChange={setPage}
        onSizeChange={(next) => {
          setSize(next);
          setPage(0);
        }}
      />

      <Modal
        open={editing !== null}
        onClose={closeEditor}
        title="Set the punch times"
        description={editing ? dateLabel(editing.attendanceDate) : undefined}
        footer={
          <div className="flex justify-end gap-sm">
            <button type="button" className="btn-ghost" onClick={closeEditor}>
              Cancel
            </button>
            <button
              type="button"
              className="btn-primary inline-flex items-center gap-2"
              disabled={!reason.trim() || override.isPending}
              onClick={() => override.mutate()}
            >
              {override.isPending ? <Spinner className="h-4 w-4" /> : null}
              Save and record it
            </button>
          </div>
        }
      >
        {editing ? (
          <div className="flex flex-col gap-md">
            {failure ? (
              <Alert variant="error" title={failure.title}>
                {failure.message}
              </Alert>
            ) : null}

            <div className="rounded-md bg-surface-variant p-sm text-body-sm">
              <p className="field-label">Recorded now</p>
              <p className="tabular-nums">
                In {timeLabel(editing.punchInTime)} · Out {timeLabel(editing.punchOutTime)} ·{' '}
                {editing.workingHours}
              </p>
            </div>

            <div className="grid gap-md sm:grid-cols-2">
              <TextField
                label="Punch in"
                type="datetime-local"
                value={punchIn}
                onChange={(event) => setPunchIn(event.target.value)}
              />
              <TextField
                label="Punch out"
                type="datetime-local"
                value={punchOut}
                disabled={clearOut}
                onChange={(event) => setPunchOut(event.target.value)}
              />
            </div>

            <label className="flex items-start gap-sm text-body-md">
              <input
                type="checkbox"
                className="mt-1"
                checked={clearOut}
                onChange={(event) => setClearOut(event.target.checked)}
              />
              <span>
                Remove the punch-out
                <span className="block text-body-sm text-on-surface-variant">
                  Reopens the day. The worked total and the early-exit figure are cleared with it.
                </span>
              </span>
            </label>

            <div>
              <label className="field-label" htmlFor="override-reason">
                Why are you changing this?
              </label>
              <textarea
                id="override-reason"
                className="field-input"
                rows={3}
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                placeholder="Device offline all day; confirmed against the door log."
              />
              <p className="mt-1 text-body-sm text-on-surface-variant">
                Required. Stored on the audit record and shown to the employee.
              </p>
            </div>
          </div>
        ) : null}
      </Modal>
    </div>
  );
};
