export interface Notification {
  id: number;
  type: string;
  category: 'LEAVE' | 'ATTENDANCE' | 'REQUEST' | 'APPROVAL' | 'SYSTEM';
  title: string;
  body: string | null;
  /** App-relative path, or null when there is nothing to open. */
  link: string | null;
  entityType: string | null;
  entityId: string | null;
  actorName: string | null;
  read: boolean;
  readAt: string | null;
  createdAt: string;
}

/**
 * The icon each kind of notification carries.
 *
 * <p>Keyed by type with a fallback, so a type added on the server shows a
 * sensible default rather than an empty square.
 */
export const NOTIFICATION_ICON: Record<string, string> = {
  LEAVE_APPROVAL_REQUESTED: 'pending_actions',
  REGULARIZATION_APPROVAL_REQUESTED: 'pending_actions',
  WFH_APPROVAL_REQUESTED: 'pending_actions',
  LEAVE_APPROVED: 'check_circle',
  REGULARIZATION_APPROVED: 'check_circle',
  WFH_APPROVED: 'check_circle',
  LEAVE_REJECTED: 'cancel',
  REGULARIZATION_REJECTED: 'cancel',
  WFH_REJECTED: 'cancel',
  REQUEST_WITHDRAWN: 'undo',
  ATTENDANCE_MISSING_PUNCH_OUT: 'timer_off',
  ATTENDANCE_MARKED_ABSENT: 'person_off',
  SYSTEM_ANNOUNCEMENT: 'campaign',
};

export const notificationIcon = (type: string): string =>
  NOTIFICATION_ICON[type] ?? 'notifications';

/** Approvals and rejections read very differently; colour follows meaning. */
export const notificationTone = (type: string): 'success' | 'error' | 'warning' | 'info' => {
  if (type.endsWith('_APPROVED')) return 'success';
  if (type.endsWith('_REJECTED')) return 'error';
  if (type.endsWith('_REQUESTED')) return 'warning';
  return 'info';
};

/**
 * How long ago, in words.
 *
 * <p>A notification list is scanned, not read. "3h ago" answers the question
 * people actually have — "is this still relevant?" — which a full timestamp
 * makes them work out for themselves.
 */
export const timeAgo = (iso: string): string => {
  const seconds = Math.floor((Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return 'just now';
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d ago`;
  return new Date(iso).toLocaleDateString();
};
