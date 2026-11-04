import { useId, useState } from 'react';
import type { TrendPoint } from '../../types/dashboard';

/**
 * Daily attendance as stacked bars.
 *
 * <p>Non-working days are drawn as a flat hatched band rather than omitted. A
 * chart that silently skips weekends draws one continuous line through the gap
 * and invites the reader to see a slump that is really just Saturday.
 *
 * <p>Each bar is measured against that day's own expected headcount, not
 * against the tallest day in the range, so a day when half the team is on leave
 * does not read as a collapse.
 */
export const AttendanceTrendChart = ({ points }: { points: TrendPoint[] }) => {
  const gradientId = useId();
  const [hovered, setHovered] = useState<number | null>(null);

  if (points.length === 0) {
    return (
      <p className="py-xl text-center text-body-md text-on-surface-variant">
        No days in this range.
      </p>
    );
  }

  const height = 160;
  const gap = 2;
  const barWidth = Math.max(4, 100 / points.length - gap / 4);

  const active = hovered != null ? points[hovered] : null;

  return (
    <div className="relative">
      <svg
        viewBox={`0 0 100 ${height}`}
        preserveAspectRatio="none"
        className="h-40 w-full"
        role="img"
        aria-label={`Attendance from ${points[0].date} to ${points[points.length - 1].date}`}
      >
        <defs>
          <pattern
            id={gradientId}
            width="4"
            height="4"
            patternUnits="userSpaceOnUse"
            patternTransform="rotate(45)"
          >
            <line x1="0" y1="0" x2="0" y2="4" className="stroke-outline-variant" strokeWidth="1.5" />
          </pattern>
        </defs>

        {points.map((point, index) => {
          const x = (index * 100) / points.length;
          const width = barWidth;

          if (!point.working) {
            // A day nobody was expected. Shown, but visibly not a working day.
            return (
              <rect
                key={point.date}
                x={x}
                y={height - 12}
                width={width}
                height={12}
                fill={`url(#${gradientId})`}
                onMouseEnter={() => setHovered(index)}
                onMouseLeave={() => setHovered(null)}
              />
            );
          }

          const total = Math.max(point.expected, 1);
          const scale = (value: number) => (value / total) * (height - 16);

          const presentHeight = scale(point.present);
          const wfhHeight = scale(point.workingFromHome);
          const absentHeight = scale(point.absent);

          let y = height;

          const bars = [
            { key: 'absent', value: absentHeight, className: 'fill-error/70' },
            { key: 'wfh', value: wfhHeight, className: 'fill-secondary' },
            { key: 'present', value: presentHeight, className: 'fill-primary' },
          ].map((segment) => {
            y -= segment.value;
            return { ...segment, y };
          });

          return (
            <g
              key={point.date}
              onMouseEnter={() => setHovered(index)}
              onMouseLeave={() => setHovered(null)}
            >
              {/* A full-height invisible target, so hovering anywhere in the
                  column works rather than only on the short bar itself. */}
              <rect x={x} y={0} width={width} height={height} fill="transparent" />
              {bars.map((segment) => (
                <rect
                  key={segment.key}
                  x={x}
                  y={segment.y}
                  width={width}
                  height={Math.max(segment.value, 0)}
                  className={segment.className}
                />
              ))}
            </g>
          );
        })}
      </svg>

      <div className="mt-sm flex flex-wrap items-center gap-md text-body-sm text-on-surface-variant">
        <span className="flex items-center gap-1">
          <span className="h-2.5 w-2.5 rounded-sm bg-primary" aria-hidden="true" />
          In office
        </span>
        <span className="flex items-center gap-1">
          <span className="h-2.5 w-2.5 rounded-sm bg-secondary" aria-hidden="true" />
          From home
        </span>
        <span className="flex items-center gap-1">
          <span className="h-2.5 w-2.5 rounded-sm bg-error/70" aria-hidden="true" />
          Absent
        </span>
        <span className="flex items-center gap-1">
          <span
            className="h-2.5 w-2.5 rounded-sm border border-outline-variant"
            aria-hidden="true"
          />
          Non-working day
        </span>

        <span className="ml-auto tabular-nums" aria-live="polite">
          {active
            ? `${new Date(active.date).toLocaleDateString(undefined, {
                weekday: 'short',
                day: 'numeric',
                month: 'short',
              })} — ${
                active.working
                  ? `${active.present + active.workingFromHome} of ${active.expected} in, ${active.absent} absent`
                  : 'not a working day'
              }`
            : `${points.length} days`}
        </span>
      </div>
    </div>
  );
};
