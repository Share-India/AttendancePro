import { useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Icon } from '../../components/common/Icon';
import { PageHeader } from '../../components/common/PageHeader';
import { StatusChip } from '../../components/common/StatusChip';
import { DataTable, type Column } from '../../components/table/DataTable';
import { attendanceApi } from '../../features/attendance/attendanceApi';
import { ApiError } from '../../types/api';
import { STATUS_TONE, humaniseStatus, type AttendanceRecord } from '../../types/attendance';

type RangePreset = 'today' | 'week' | 'month' | 'custom';

const isoDate = (date: Date): string => date.toISOString().slice(0, 10);

const rangeFor = (preset: RangePreset): { from: string; to: string } => {
  const today = new Date();

  switch (preset) {
    case 'today':
      return { from: isoDate(today), to: isoDate(today) };
    case 'week': {
      // Week starts Monday, matching the ISO day numbering the backend uses.
      const monday = new Date(today);
      const offset = (today.getDay() + 6) % 7;
      monday.setDate(today.getDate() - offset);
      return { from: isoDate(monday), to: isoDate(today) };
    }
    case 'month':
    default: {
      const first = new Date(today.getFullYear(), today.getMonth(), 1);
      return { from: isoDate(first), to: isoDate(today) };
    }
  }
};

const timeOf = (value: string | null): string =>
  value ? new Date(value).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) : '—';

export const MyAttendancePage = () => {
  const [preset, setPreset] = useState<RangePreset>('month');
  const [custom, setCustom] = useState(rangeFor('month'));
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(31);

  const range = useMemo(() => (preset === 'custom' ? custom : rangeFor(preset)), [preset, custom]);

  const { data, isLoading, error } = useQuery({
    queryKey: ['attendance', 'history', range, page, size],
    queryFn: () => attendanceApi.history({ fromDate: range.from, toDate: range.to, page, size }),
    placeholderData: keepPreviousData,
  });

  const columns: Column<AttendanceRecord>[] = [
    {
      key: 'date',
      header: 'Date',
      className: 'whitespace-nowrap',
      render: (row) => (
        <div>
          <p className="tabular-nums text-on-surface">
            {new Date(row.attendanceDate).toLocaleDateString(undefined, {
              day: '2-digit',
              month: 'short',
            })}
          </p>
          <p className="text-body-sm text-on-surface-variant">
            {row.dayOfWeek.charAt(0) + row.dayOfWeek.slice(1, 3).toLowerCase()}
          </p>
        </div>
      ),
    },
    {
      key: 'punchIn',
      header: 'In',
      className: 'tabular-nums whitespace-nowrap',
      render: (row) => timeOf(row.punchInTime),
    },
    {
      key: 'punchOut',
      header: 'Out',
      className: 'tabular-nums whitespace-nowrap',
      render: (row) => timeOf(row.punchOutTime),
    },
    {
      key: 'worked',
      header: 'Worked',
      className: 'tabular-nums whitespace-nowrap',
      render: (row) => (row.workingMinutes > 0 ? row.workingHours : '—'),
    },
    {
      key: 'break',
      header: 'Break',
      className: 'tabular-nums whitespace-nowrap',
      // Dropped on phones: it reads "—" on most days, and eight labelled
      // lines per card turns nine days into a long scroll. Still in the
      // table on anything wider.
      hideOnMobile: true,
      render: (row) => (row.breakMinutes > 0 ? `${row.breakMinutes}m` : '—'),
    },
    {
      key: 'late',
      header: 'Late / Early',
      className: 'whitespace-nowrap',
      render: (row) => (
        <div className="flex flex-col gap-0.5">
          {row.lateMinutes > 0 && (
            <span className="text-body-sm text-warning">{row.lateMinutes}m late</span>
          )}
          {row.earlyExitMinutes > 0 && (
            <span className="text-body-sm text-warning">{row.earlyExitMinutes}m early</span>
          )}
          {row.lateMinutes === 0 && row.earlyExitMinutes === 0 && (
            <span className="text-outline">—</span>
          )}
        </div>
      ),
    },
    {
      key: 'location',
      header: 'Location',
      // The longest value on the row and the least scanned. Somebody
      // checking their week on a phone wants times and status.
      hideOnMobile: true,
      render: (row) =>
        row.punchInLocation ? (
          <span className="text-body-sm text-on-surface-variant">{row.punchInLocation}</span>
        ) : (
          <span className="text-outline">—</span>
        ),
    },
    {
      key: 'status',
      header: 'Status',
      render: (row) => (
        <div className="flex flex-wrap items-center gap-1">
          <StatusChip
            label={humaniseStatus(row.attendanceStatus)}
            tone={STATUS_TONE[row.attendanceStatus] ?? 'neutral'}
          />
          {row.regularized && <StatusChip label="Regularized" tone="info" />}
          {row.suspicious && <StatusChip label="Flagged" tone="error" />}
        </div>
      ),
    },
  ];

  const presets: Array<{ value: RangePreset; label: string }> = [
    { value: 'today', label: 'Today' },
    { value: 'week', label: 'This week' },
    { value: 'month', label: 'This month' },
    { value: 'custom', label: 'Custom' },
  ];

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title="My Attendance"
        description="Every day recorded for you, with the location each punch was verified against."
      />

      <div className="card mb-lg flex flex-col gap-md p-md lg:flex-row lg:items-end lg:justify-between">
        <div
          className="flex flex-wrap gap-1"
          role="group"
          aria-label="Date range"
        >
          {presets.map((option) => (
            <button
              key={option.value}
              type="button"
              onClick={() => {
                setPreset(option.value);
                setPage(0);
              }}
              className={
                preset === option.value
                  ? 'rounded-DEFAULT bg-secondary-container px-3 py-2 text-label-md text-primary'
                  : 'rounded-DEFAULT px-3 py-2 text-label-md text-on-surface-variant transition-colors hover:bg-surface-container-low'
              }
            >
              {option.label}
            </button>
          ))}
        </div>

        {preset === 'custom' && (
          <div className="flex flex-wrap items-end gap-md">
            <div>
              <label htmlFor="from-date" className="field-label">From</label>
              <input
                id="from-date"
                type="date"
                value={custom.from}
                max={custom.to}
                onChange={(event) => {
                  setCustom((current) => ({ ...current, from: event.target.value }));
                  setPage(0);
                }}
                className="field-input"
              />
            </div>
            <div>
              <label htmlFor="to-date" className="field-label">To</label>
              <input
                id="to-date"
                type="date"
                value={custom.to}
                min={custom.from}
                onChange={(event) => {
                  setCustom((current) => ({ ...current, to: event.target.value }));
                  setPage(0);
                }}
                className="field-input"
              />
            </div>
          </div>
        )}

        <p className="flex items-center gap-2 text-body-sm text-on-surface-variant">
          <Icon name="date_range" className="text-[18px]" />
          {new Date(range.from).toLocaleDateString()} – {new Date(range.to).toLocaleDateString()}
        </p>
      </div>

      <DataTable
        columns={columns}
        rows={data?.content ?? []}
        rowKey={(row) => row.id}
        loading={isLoading}
        error={error instanceof ApiError ? error.message : error ? 'Could not load your attendance.' : null}
        emptyTitle="No attendance in this range"
        emptyMessage="Pick a different range, or punch in to start today."
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
