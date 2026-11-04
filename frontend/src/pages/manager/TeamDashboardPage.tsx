import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { PageHeader } from '../../components/common/PageHeader';
import { StatusChip } from '../../components/common/StatusChip';
import { DataTable, type Column } from '../../components/table/DataTable';
import { ROUTES } from '../../constants/routes';
import { AttendanceTrendChart } from '../../features/dashboard/AttendanceTrendChart';
import { dashboardApi } from '../../features/dashboard/dashboardApi';
import { useDebounce } from '../../hooks/useDebounce';
import { ApiError } from '../../types/api';
import { DAY_STATE_META, type DayState, type TeamMemberDay } from '../../types/dashboard';

const isoToday = (): string => new Date().toISOString().slice(0, 10);

const timeOnly = (value: string | null): string =>
  value ? new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '—';

const formatMinutes = (minutes: number | null): string => {
  if (minutes == null) return '—';
  const hours = Math.floor(minutes / 60);
  return hours > 0 ? `${hours}h ${minutes % 60}m` : `${minutes}m`;
};

/**
 * A count, with the reason it matters underneath.
 *
 * <p>Clicking filters the roster to that state — the number is a question and
 * the list is the answer, so making the two separate screens would be a waste.
 */
const StateCard = ({
  state,
  count,
  hint,
  selected,
  onSelect,
}: {
  state: DayState;
  count: number;
  hint?: string;
  selected: boolean;
  onSelect: () => void;
}) => {
  const meta = DAY_STATE_META[state];
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      // Stacks below sm for the same reason the dashboard's StatCard does:
      // two of these side by side on a phone leave too little width for the
      // icon and the label to share a line.
      className={`card flex flex-col items-start gap-sm p-md text-left transition-colors sm:flex-row sm:items-center sm:gap-md ${
        selected ? 'ring-2 ring-primary' : 'hover:bg-surface-container-low'
      }`}
    >
      <span
        className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-DEFAULT ${
          meta.tone === 'error'
            ? 'bg-error-container'
            : meta.tone === 'warning'
              ? 'bg-warning-container'
              : meta.tone === 'success'
                ? 'bg-success-container'
                : meta.tone === 'info'
                  ? 'bg-secondary-container'
                  : 'bg-surface-container-high'
        }`}
      >
        <Icon
          name={meta.icon}
          className={`text-[22px] ${
            meta.tone === 'error'
              ? 'text-on-error-container'
              : meta.tone === 'warning'
                ? 'text-on-warning-container'
                : meta.tone === 'success'
                  ? 'text-on-success-container'
                  : meta.tone === 'info'
                    ? 'text-on-secondary-fixed'
                    : 'text-on-surface-variant'
          }`}
        />
      </span>
      <div className="min-w-0">
        <p className="truncate text-body-sm text-on-surface-variant">{meta.label}</p>
        <p className="text-headline-md tabular-nums text-on-surface">{count}</p>
        {hint && <p className="truncate text-body-sm text-on-surface-variant">{hint}</p>}
      </div>
    </button>
  );
};

export const TeamDashboardPage = () => {
  const [date, setDate] = useState(isoToday());
  const [stateFilter, setStateFilter] = useState<DayState | null>(null);
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const debouncedSearch = useDebounce(search, 300);

  const summary = useQuery({
    queryKey: ['dashboard', 'summary', date],
    queryFn: () => dashboardApi.summary({ date }),
    placeholderData: keepPreviousData,
  });

  const trend = useQuery({
    queryKey: ['dashboard', 'trend', date],
    queryFn: () =>
      dashboardApi.trend({
        // Four weeks back from the day being viewed, so the chart follows the
        // date picker rather than always describing today.
        fromDate: new Date(new Date(date).getTime() - 27 * 86_400_000).toISOString().slice(0, 10),
        toDate: date,
      }),
  });

  const roster = useQuery({
    queryKey: ['dashboard', 'roster', date, stateFilter, debouncedSearch, page, size],
    queryFn: () =>
      dashboardApi.roster({
        date,
        dayState: stateFilter ?? undefined,
        search: debouncedSearch || undefined,
        page,
        size,
      }),
    placeholderData: keepPreviousData,
  });

  const data = summary.data;
  const isOrg = data?.scope === 'ORG';

  const pickState = (state: DayState) => {
    setStateFilter((current) => (current === state ? null : state));
    setPage(0);
  };

  const columns: Column<TeamMemberDay>[] = useMemo(
    () => [
      {
        key: 'employee',
        header: 'Employee',
        render: (row) => (
          <div className="flex items-center gap-sm">
            <span
              className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-primary-fixed text-body-sm font-semibold text-on-primary-fixed"
              aria-hidden="true"
            >
              {row.employeeName.charAt(0).toUpperCase()}
            </span>
            <div className="min-w-0">
              <Link
                to={ROUTES.employeeDetail(row.employeeId)}
                className="truncate text-on-surface hover:underline"
              >
                {row.employeeName}
              </Link>
              <p className="truncate font-mono text-body-sm text-on-surface-variant">
                {row.employeeCode}
                {row.departmentName ? ` · ${row.departmentName}` : ''}
              </p>
            </div>
          </div>
        ),
      },
      {
        key: 'state',
        header: 'Status',
        render: (row) => {
          const meta = DAY_STATE_META[row.dayState];
          return (
            <div className="flex flex-col gap-1">
              <StatusChip label={meta.label} tone={meta.tone} />
              {row.dayState === 'ON_LEAVE' && row.leaveTypeName && (
                <span className="text-body-sm text-on-surface-variant">
                  {row.leaveTypeName}
                  {row.leaveDayPortion && row.leaveDayPortion !== 'FULL_DAY'
                    ? ' · half day'
                    : ''}
                </span>
              )}
            </div>
          );
        },
      },
      {
        key: 'punches',
        header: 'In / out',
        className: 'whitespace-nowrap tabular-nums',
        render: (row) =>
          row.punchInTime ? (
            <span>
              {timeOnly(row.punchInTime)} – {timeOnly(row.punchOutTime)}
              {row.lateMinutes != null && row.lateMinutes > 0 && (
                <span className="ml-2 text-body-sm text-error">
                  {row.lateMinutes}m late
                </span>
              )}
            </span>
          ) : (
            <span className="text-on-surface-variant">—</span>
          ),
      },
      {
        key: 'worked',
        header: 'Worked',
        className: 'tabular-nums',
        render: (row) => formatMinutes(row.workingMinutes),
      },
      {
        key: 'shift',
        header: 'Shift',
        className: 'whitespace-nowrap tabular-nums',
        render: (row) =>
          row.shiftStartTime ? (
            <span className="text-on-surface-variant">
              {row.shiftStartTime.slice(0, 5)}–{row.shiftEndTime?.slice(0, 5)}
            </span>
          ) : (
            <span className="text-on-surface-variant">No shift</span>
          ),
      },
    ],
    [],
  );

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title={isOrg ? 'Organisation' : 'My Team'}
        description={
          isOrg
            ? 'Everyone, for the day you pick. People away for a good reason are counted as that, never as absent.'
            : 'The people who report to you. Anyone away on approved leave or a weekly off is shown as such, not as an absence.'
        }
        actions={
          <label className="flex items-center gap-sm">
            <span className="text-body-sm text-on-surface-variant">Date</span>
            <input
              type="date"
              value={date}
              max={isoToday()}
              onChange={(event) => {
                setDate(event.target.value);
                setPage(0);
              }}
              className="field-input w-auto tabular-nums"
            />
          </label>
        }
      />

      {summary.isError && (
        <Alert variant="error" title="Could not load the dashboard" className="mb-lg">
          {summary.error instanceof ApiError ? summary.error.message : 'Please try again.'}
        </Alert>
      )}

      {data && (
        <>
          {/* The headline: how many of the people who were meant to work did. */}
          <section className="card mb-lg flex flex-wrap items-center justify-between gap-lg p-lg">
            <div>
              <p className="text-body-sm text-on-surface-variant">
                Attendance on {new Date(data.date).toLocaleDateString(undefined, {
                  weekday: 'long',
                  day: 'numeric',
                  month: 'long',
                })}
              </p>
              <p className="text-display-sm tabular-nums text-on-surface">
                {data.expected === 0 ? '—' : `${data.attendanceRate}%`}
              </p>
              <p className="text-body-sm text-on-surface-variant">
                {data.expected === 0
                  ? 'Nobody was scheduled to work this day.'
                  : `${data.present + data.workingFromHome} of ${data.expected} expected · ${data.headcount} on the books`}
              </p>
            </div>

            <dl className="flex flex-wrap gap-lg">
              <div>
                <dt className="text-body-sm text-on-surface-variant">Currently in</dt>
                <dd className="text-title-lg tabular-nums text-on-surface">{data.currentlyIn}</dd>
              </div>
              <div>
                <dt className="text-body-sm text-on-surface-variant">Already out</dt>
                <dd className="text-title-lg tabular-nums text-on-surface">{data.punchedOut}</dd>
              </div>
              <div>
                <dt className="text-body-sm text-on-surface-variant">Arrived late</dt>
                <dd className="text-title-lg tabular-nums text-on-surface">{data.late}</dd>
              </div>
            </dl>

            {data.pendingApprovals.total > 0 && (
              <div className="flex flex-wrap gap-sm">
                {data.pendingApprovals.leave > 0 && (
                  <Link to={ROUTES.leaveApprovals} className="btn-secondary">
                    <Icon name="event_available" className="text-[18px]" />
                    {data.pendingApprovals.leave} leave
                  </Link>
                )}
                {data.pendingApprovals.regularization + data.pendingApprovals.workFromHome > 0 && (
                  <Link to={ROUTES.requestApprovals} className="btn-secondary">
                    <Icon name="assignment_turned_in" className="text-[18px]" />
                    {data.pendingApprovals.regularization + data.pendingApprovals.workFromHome} requests
                  </Link>
                )}
              </div>
            )}
          </section>

          <section className="mb-lg grid grid-cols-2 gap-md lg:grid-cols-3 xl:grid-cols-6">
            <StateCard
              state="PRESENT"
              count={data.present}
              selected={stateFilter === 'PRESENT'}
              onSelect={() => pickState('PRESENT')}
            />
            <StateCard
              state="WORKING_FROM_HOME"
              count={data.workingFromHome}
              selected={stateFilter === 'WORKING_FROM_HOME'}
              onSelect={() => pickState('WORKING_FROM_HOME')}
            />
            <StateCard
              state="ON_LEAVE"
              count={data.onLeave}
              hint="approved"
              selected={stateFilter === 'ON_LEAVE'}
              onSelect={() => pickState('ON_LEAVE')}
            />
            <StateCard
              state="NOT_YET_IN"
              count={data.notYetIn}
              hint="shift still running"
              selected={stateFilter === 'NOT_YET_IN'}
              onSelect={() => pickState('NOT_YET_IN')}
            />
            <StateCard
              state="ABSENT"
              count={data.absent}
              hint="unexplained"
              selected={stateFilter === 'ABSENT'}
              onSelect={() => pickState('ABSENT')}
            />
            <StateCard
              state={data.holiday > 0 ? 'HOLIDAY' : 'WEEKLY_OFF'}
              count={data.holiday > 0 ? data.holiday : data.weeklyOff}
              selected={stateFilter === (data.holiday > 0 ? 'HOLIDAY' : 'WEEKLY_OFF')}
              onSelect={() => pickState(data.holiday > 0 ? 'HOLIDAY' : 'WEEKLY_OFF')}
            />
          </section>

          <div className="mb-lg grid gap-lg lg:grid-cols-[minmax(0,1fr)_minmax(0,380px)]">
            <section className="card p-lg">
              <h2 className="mb-md text-title-md text-on-surface">Last four weeks</h2>
              {trend.isLoading ? (
                <p className="py-xl text-center text-body-md text-on-surface-variant">Loading…</p>
              ) : (
                <AttendanceTrendChart points={trend.data?.points ?? []} />
              )}
            </section>

            {isOrg && data.byDepartment.length > 0 && (
              <section className="card p-lg">
                <h2 className="mb-md text-title-md text-on-surface">By department</h2>
                <ul className="flex flex-col gap-md">
                  {data.byDepartment.map((group) => {
                    const inWork = group.present + group.workingFromHome;
                    const pct = group.expected === 0 ? 0 : (inWork / group.expected) * 100;
                    return (
                      <li key={group.name}>
                        <div className="flex items-baseline justify-between gap-sm">
                          <span className="truncate text-body-md text-on-surface">{group.name}</span>
                          <span className="shrink-0 text-body-sm tabular-nums text-on-surface-variant">
                            {group.expected === 0 ? 'not working' : `${inWork}/${group.expected}`}
                          </span>
                        </div>
                        <div
                          className="mt-1 h-2 w-full overflow-hidden rounded-full bg-surface-container-high"
                          role="img"
                          aria-label={`${group.name}: ${inWork} of ${group.expected} in`}
                        >
                          <div
                            className={`h-full ${group.absent > 0 ? 'bg-warning' : 'bg-primary'}`}
                            style={{ width: `${pct}%` }}
                          />
                        </div>
                        {group.absent > 0 && (
                          <p className="mt-1 text-body-sm text-error">
                            {group.absent} unaccounted for
                          </p>
                        )}
                      </li>
                    );
                  })}
                </ul>
              </section>
            )}
          </div>
        </>
      )}

      <section>
        <div className="mb-md flex flex-wrap items-center justify-between gap-md">
          <h2 className="text-title-md text-on-surface">
            {stateFilter ? DAY_STATE_META[stateFilter].label : 'Everyone'}
            {stateFilter && (
              <button
                type="button"
                onClick={() => {
                  setStateFilter(null);
                  setPage(0);
                }}
                className="ml-sm text-body-sm text-primary underline underline-offset-2"
              >
                clear filter
              </button>
            )}
          </h2>
          <input
            type="search"
            value={search}
            onChange={(event) => {
              setSearch(event.target.value);
              setPage(0);
            }}
            placeholder="Search name or code"
            className="field-input w-full sm:w-64"
            aria-label="Search the roster"
          />
        </div>

        <DataTable
          columns={columns}
          rows={roster.data?.content ?? []}
          rowKey={(row) => row.employeeId}
          loading={roster.isLoading}
          error={
            roster.error instanceof ApiError
              ? roster.error.message
              : roster.error
                ? 'Could not load the roster.'
                : null
          }
          emptyTitle={stateFilter ? `Nobody is ${DAY_STATE_META[stateFilter].label.toLowerCase()}` : 'Nobody to show'}
          emptyMessage={
            stateFilter
              ? 'Try another status, or clear the filter.'
              : 'Once people are assigned to you they appear here.'
          }
          page={roster.data?.page ?? 0}
          size={size}
          totalElements={roster.data?.totalElements ?? 0}
          totalPages={roster.data?.totalPages ?? 0}
          onPageChange={setPage}
          onSizeChange={(next) => {
            setSize(next);
            setPage(0);
          }}
        />
      </section>
    </div>
  );
};
