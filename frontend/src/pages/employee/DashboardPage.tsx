import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Icon } from '../../components/common/Icon';
import { attendanceApi } from '../../features/attendance/attendanceApi';
import { formatMinutes } from '../../features/attendance/useLiveTimer';
import { PunchCard } from '../../features/attendance/PunchCard';
import { useAuthStore } from '../../store/authStore';

const greetingFor = (hour: number): string => {
  if (hour < 12) return 'Good Morning';
  if (hour < 17) return 'Good Afternoon';
  return 'Good Evening';
};

const StatCard = ({
  icon,
  label,
  value,
  hint,
  pending,
}: {
  icon: string;
  label: string;
  value: string;
  hint?: string;
  pending?: boolean;
}) => (
  // Two of these sit side by side on a phone, which leaves roughly 140px of
  // content width. The icon and the text cannot share that: "Present this
  // month" alone needs more than what is left beside a 44px badge. So the card
  // stacks below sm and only goes side-by-side once there is room for it.
  <div className="card flex flex-col items-start gap-sm p-md sm:flex-row sm:items-center sm:gap-md">
    <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-DEFAULT bg-primary-fixed">
      <Icon name={icon} className="text-[22px] text-primary" />
    </span>
    <div className="min-w-0 w-full">
      <p className="truncate text-body-sm text-on-surface-variant">{label}</p>
      <p className={`text-headline-md tabular-nums ${pending ? 'text-outline' : 'text-on-surface'}`}>
        {value}
      </p>
      {hint && <p className="truncate text-body-sm text-on-surface-variant">{hint}</p>}
    </div>
  </div>
);

export const DashboardPage = () => {
  const user = useAuthStore((state) => state.user);
  const [now, setNow] = useState(new Date());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const period = { year: now.getFullYear(), month: now.getMonth() + 1 };

  const { data: summary } = useQuery({
    queryKey: ['dashboard', 'summary', period.year, period.month],
    queryFn: () => attendanceApi.summary(period.year, period.month),
  });

  return (
    <div className="mx-auto max-w-[1280px]">
      <header className="mb-lg">
        <h1 className="text-display-lg text-on-surface">
          {greetingFor(now.getHours())}, {user?.fullName.split(' ')[0]}!
        </h1>
        <p className="mt-xs text-body-lg text-on-surface-variant">
          {now.toLocaleDateString(undefined, {
            weekday: 'long',
            year: 'numeric',
            month: 'short',
            day: 'numeric',
          })}
          <span className="mx-2 text-outline-variant">|</span>
          <span className="font-mono tabular-nums">
            {now.toLocaleTimeString(undefined, {
              hour: '2-digit',
              minute: '2-digit',
              second: '2-digit',
            })}
          </span>
        </p>
      </header>

      <div className="grid gap-gutter lg:grid-cols-3">
        <div className="lg:col-span-2">
          <PunchCard />
        </div>

        <section className="card p-lg">
          <h2 className="mb-md flex items-center gap-2 text-headline-md text-on-surface">
            <Icon name="badge" className="text-[20px] text-primary" />
            Your details
          </h2>
          <dl className="space-y-md">
            {[
              { label: 'Employee ID', value: user?.employeeCode },
              { label: 'Department', value: user?.department },
              { label: 'Designation', value: user?.designation },
              { label: 'Office', value: user?.office },
              { label: 'Reporting Manager', value: user?.reportingManager },
            ].map((row) => (
              <div key={row.label} className="flex items-baseline justify-between gap-md">
                <dt className="text-body-sm text-on-surface-variant">{row.label}</dt>
                <dd className="min-w-0 truncate text-body-md text-on-surface">{row.value ?? '—'}</dd>
              </div>
            ))}
          </dl>
        </section>
      </div>

      <div className="mt-gutter grid grid-cols-2 gap-md sm:gap-gutter xl:grid-cols-4">
        <StatCard
          icon="how_to_reg"
          label="Present this month"
          value={summary ? `${summary.presentDays}` : '—'}
          hint={summary ? `${summary.absentDays} absent · ${summary.halfDays} half` : undefined}
          pending={!summary}
        />
        <StatCard
          icon="donut_large"
          label="Attendance"
          value={summary ? `${summary.attendancePercentage}%` : '—'}
          hint={summary ? 'of working days so far' : undefined}
          pending={!summary}
        />
        <StatCard
          icon="schedule"
          label="Average day"
          value={summary?.averageWorkingHours ?? '—'}
          hint={summary ? `${summary.totalWorkingHours} total` : undefined}
          pending={!summary}
        />
        <StatCard
          icon="running_with_errors"
          label="Late marks"
          value={summary ? `${summary.lateMarks}` : '—'}
          hint={summary ? `${summary.earlyExits} early exits` : undefined}
          pending={!summary}
        />
      </div>

      {summary && summary.overtimeMinutes > 0 && (
        <p className="mt-lg text-body-sm text-on-surface-variant">
          <Icon name="more_time" className="mr-1 align-[-3px] text-[16px]" />
          {formatMinutes(summary.overtimeMinutes)} of overtime recorded this month.
        </p>
      )}
    </div>
  );
};
