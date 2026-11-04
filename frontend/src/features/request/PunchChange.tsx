import { Icon } from '../../components/common/Icon';
import type { Regularization } from '../../types/request';

const timeOnly = (value: string | null): string =>
  value ? new Date(value).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '—';

/**
 * What the correction actually changes, one line per punch.
 *
 * <p>Only the times the request asks for are shown. Showing the punch-in
 * change on a "forgot to punch out" request displays the field nobody is
 * arguing about, and a bare "3:13 PM → 7:00 AM" reads as a backwards range
 * rather than a before and after — hence the label on each row.
 */
export const PunchChange = ({ request }: { request: Regularization }) => {
  const rows: { label: string; before: string | null; after: string }[] = [];

  if (request.requestedPunchInTime) {
    rows.push({
      label: 'in',
      before: request.creatingMissingDay ? null : timeOnly(request.originalPunchInTime),
      after: timeOnly(request.requestedPunchInTime),
    });
  }
  if (request.requestedPunchOutTime) {
    rows.push({
      label: 'out',
      before: request.creatingMissingDay ? null : timeOnly(request.originalPunchOutTime),
      after: timeOnly(request.requestedPunchOutTime),
    });
  }

  if (rows.length === 0) {
    return <span className="text-body-sm text-on-surface-variant">Times unchanged</span>;
  }

  return (
    <div className="flex flex-col gap-0.5 text-body-sm">
      {rows.map((row) => (
        <span key={row.label} className="flex items-center gap-1">
          <span className="w-6 shrink-0 text-on-surface-variant">{row.label}</span>
          <span className="text-on-surface-variant line-through">
            {row.before ?? 'not recorded'}
          </span>
          <Icon name="arrow_forward" className="text-[14px] text-outline" />
          <span className="text-on-surface">{row.after}</span>
        </span>
      ))}
    </div>
  );
};
