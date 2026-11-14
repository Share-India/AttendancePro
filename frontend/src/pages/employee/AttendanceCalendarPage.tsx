import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { Modal } from '../../components/common/Modal';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { StatusChip } from '../../components/common/StatusChip';
import { attendanceApi } from '../../features/attendance/attendanceApi';
import { formatMinutes } from '../../features/attendance/useLiveTimer';
import { ApiError } from '../../types/api';
import { STATUS_TONE, humaniseStatus, type CalendarDay } from '../../types/attendance';

/**
 * Colour alone never carries the state — each cell also shows its status text,
 * so the calendar is readable without colour vision.
 */
const CELL_STYLES: Record<string, string> = {
  PRESENT: 'bg-success-container text-on-success-container border-success/30',
  WFH: 'bg-success-container text-on-success-container border-success/30',
  REGULARIZED: 'bg-success-container text-on-success-container border-success/30',
  HALF_DAY: 'bg-warning-container text-on-warning-container border-warning/30',
  INSUFFICIENT_HOURS: 'bg-warning-container text-on-warning-container border-warning/30',
  MISSING_PUNCH_OUT: 'bg-warning-container text-on-warning-container border-warning/30',
  ABSENT: 'bg-error-container text-on-error-container border-error/30',
  ON_LEAVE: 'bg-secondary-container text-on-secondary-fixed border-primary/20',
  HALF_DAY_LEAVE: 'bg-secondary-container text-on-secondary-fixed border-primary/20',
  PUNCHED_IN: 'bg-primary-fixed text-on-primary-fixed border-primary/30',
  ON_BREAK: 'bg-warning-container text-on-warning-container border-warning/30',
  HOLIDAY: 'bg-surface-container-high text-on-surface-variant border-outline-variant/50',
  WEEKLY_OFF: 'bg-surface-container text-on-surface-variant border-outline-variant/40',
  UPCOMING: 'bg-surface-container-lowest text-outline border-outline-variant/30 border-dashed',
  // Before this system started recording. Styled like a future day on
  // purpose: in both cases there is no punch and nothing was missed.
  NOT_TRACKED: 'bg-surface-container-lowest text-outline border-outline-variant/30 border-dashed',
};

const LEGEND = [
  { status: 'PRESENT', label: 'Present' },
  { status: 'HALF_DAY', label: 'Half day' },
  { status: 'ABSENT', label: 'Absent' },
  { status: 'ON_LEAVE', label: 'Leave' },
  { status: 'HOLIDAY', label: 'Holiday' },
  { status: 'WEEKLY_OFF', label: 'Weekly off' },
];

export const AttendanceCalendarPage = () => {
  const today = new Date();
  const [year, setYear] = useState(today.getFullYear());
  const [month, setMonth] = useState(today.getMonth() + 1);
  const [selected, setSelected] = useState<CalendarDay | null>(null);

  const { data, isLoading, error } = useQuery({
    queryKey: ['attendance', 'calendar', year, month],
    queryFn: () => attendanceApi.calendar(year, month),
    placeholderData: keepPreviousData,
  });

  const shiftMonth = (delta: number) => {
    const date = new Date(year, month - 1 + delta, 1);
    setYear(date.getFullYear());
    setMonth(date.getMonth() + 1);
  };

  const monthLabel = new Date(year, month - 1, 1).toLocaleDateString(undefined, {
    month: 'long',
    year: 'numeric',
  });

  // Blank cells so the first of the month lands under the right weekday,
  // with the grid starting on Monday to match the ISO week.
  const leadingBlanks = data?.days.length
    ? (new Date(data.days[0].date).getDay() + 6) % 7
    : 0;

  return (
    <div className="mx-auto max-w-[1280px]">
      <PageHeader
        title="Attendance Calendar"
        description="A month at a glance. Days nobody worked are still filled in, so a gap always means something."
      />

      <div className="card mb-lg flex flex-wrap items-center justify-between gap-md p-md">
        <div className="flex items-center gap-sm">
          <button
            type="button"
            onClick={() => shiftMonth(-1)}
            className="rounded-DEFAULT p-2 text-on-surface-variant transition-colors hover:bg-surface-container-low"
            aria-label="Previous month"
          >
            <Icon name="chevron_left" className="text-[20px]" />
          </button>
          <p className="min-w-[180px] text-center text-headline-md text-on-surface">{monthLabel}</p>
          <button
            type="button"
            onClick={() => shiftMonth(1)}
            className="rounded-DEFAULT p-2 text-on-surface-variant transition-colors hover:bg-surface-container-low"
            aria-label="Next month"
          >
            <Icon name="chevron_right" className="text-[20px]" />
          </button>
        </div>

        <div className="flex flex-wrap gap-md">
          {LEGEND.map((entry) => (
            <span key={entry.status} className="flex items-center gap-1.5 text-body-sm text-on-surface-variant">
              <span
                className={`h-3 w-3 rounded-sm border ${CELL_STYLES[entry.status] ?? ''}`}
                aria-hidden="true"
              />
              {entry.label}
            </span>
          ))}
        </div>
      </div>

      {error && (
        <Alert variant="error" title="Could not load the calendar" className="mb-lg">
          {error instanceof ApiError ? error.message : 'Please try again in a moment.'}
        </Alert>
      )}

      {data?.summary && (
        <div className="mb-lg grid grid-cols-2 gap-md sm:gap-gutter xl:grid-cols-4">
          {[
            { label: 'Present', value: `${data.summary.presentDays}`, hint: `${data.summary.halfDays} half days` },
            { label: 'Absent', value: `${data.summary.absentDays}`, hint: `${data.summary.lateMarks} late marks` },
            { label: 'Worked', value: data.summary.totalWorkingHours, hint: `${data.summary.averageWorkingHours} average` },
            { label: 'Attendance', value: `${data.summary.attendancePercentage}%`, hint: 'of working days so far' },
          ].map((card) => (
            <div key={card.label} className="card p-md">
              <p className="text-body-sm text-on-surface-variant">{card.label}</p>
              <p className="text-headline-md tabular-nums text-on-surface">{card.value}</p>
              <p className="text-body-sm text-on-surface-variant">{card.hint}</p>
            </div>
          ))}
        </div>
      )}

      <div className="card p-md">
        {isLoading ? (
          <div className="flex justify-center py-xl">
            <Spinner className="h-8 w-8 text-primary" />
          </div>
        ) : (
          <>
            <div className="mb-sm grid grid-cols-7 gap-1">
              {['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((day) => (
                <div key={day} className="py-1 text-center text-label-md uppercase text-on-surface-variant">
                  {/* A column is about 43px on a phone; "Mon" does not fit. */}
                  <span className="sm:hidden">{day.charAt(0)}</span>
                  <span className="hidden sm:inline">{day}</span>
                </div>
              ))}
            </div>

            <div className="grid grid-cols-7 gap-1">
              {Array.from({ length: leadingBlanks }).map((_, index) => (
                <div key={`blank-${index}`} aria-hidden="true" />
              ))}

              {(data?.days ?? []).map((day) => {
                const dayNumber = new Date(day.date).getDate();
                const isToday = day.date === new Date().toISOString().slice(0, 10);

                return (
                  <button
                    key={day.date}
                    type="button"
                    onClick={() => setSelected(day)}
                    className={`flex min-h-[44px] flex-col rounded-DEFAULT border p-1 text-left transition-shadow hover:shadow-raised sm:min-h-[76px] sm:p-1.5 ${
                      CELL_STYLES[day.status] ?? CELL_STYLES.UPCOMING
                    } ${isToday ? 'ring-2 ring-primary ring-offset-1' : ''}`}
                  >
                    <span className="text-body-sm font-semibold tabular-nums">{dayNumber}</span>
                    <span className="mt-auto hidden truncate text-[11px] leading-tight sm:block">
                      {day.holidayName ?? humaniseStatus(day.status)}
                    </span>
                    {day.workingMinutes != null && day.workingMinutes > 0 && (
                      <span className="hidden truncate font-mono text-[11px] tabular-nums opacity-80 sm:block">
                        {day.workingHours}
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          </>
        )}
      </div>

      <Modal
        open={selected !== null}
        onClose={() => setSelected(null)}
        title={
          selected
            ? new Date(selected.date).toLocaleDateString(undefined, {
                weekday: 'long',
                day: 'numeric',
                month: 'long',
                year: 'numeric',
              })
            : ''
        }
        size="sm"
      >
        {selected && (
          <dl className="space-y-md">
            <div className="flex items-center justify-between gap-md">
              <dt className="text-body-sm text-on-surface-variant">Status</dt>
              <dd>
                <StatusChip
                  label={humaniseStatus(selected.status)}
                  tone={STATUS_TONE[selected.status] ?? 'neutral'}
                />
              </dd>
            </div>
            <div className="flex items-center justify-between gap-md">
              <dt className="text-body-sm text-on-surface-variant">Day type</dt>
              <dd className="text-body-md text-on-surface">{humaniseStatus(selected.dayType)}</dd>
            </div>
            {selected.holidayName && (
              <div className="flex items-center justify-between gap-md">
                <dt className="text-body-sm text-on-surface-variant">Holiday</dt>
                <dd className="text-body-md text-on-surface">{selected.holidayName}</dd>
              </div>
            )}
            {selected.workingMinutes != null && (
              <div className="flex items-center justify-between gap-md">
                <dt className="text-body-sm text-on-surface-variant">Worked</dt>
                <dd className="font-mono text-body-md tabular-nums text-on-surface">
                  {formatMinutes(selected.workingMinutes)}
                </dd>
              </div>
            )}
            {selected.lateMinutes != null && selected.lateMinutes > 0 && (
              <div className="flex items-center justify-between gap-md">
                <dt className="text-body-sm text-on-surface-variant">Late by</dt>
                <dd className="text-body-md text-warning">{selected.lateMinutes} minutes</dd>
              </div>
            )}
            {!selected.hasRecord && (
              <Alert variant="info">No punch was recorded for this day.</Alert>
            )}
          </dl>
        )}
      </Modal>
    </div>
  );
};
