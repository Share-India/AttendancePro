import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Icon } from '../../components/common/Icon';
import { Spinner } from '../../components/common/Spinner';
import { ROUTES } from '../../constants/routes';
import { notificationApi } from './notificationApi';
import {
  notificationIcon,
  notificationTone,
  timeAgo,
  type Notification,
} from '../../types/notification';

const TONE_CLASS: Record<string, string> = {
  success: 'bg-success-container text-on-success-container',
  error: 'bg-error-container text-on-error-container',
  warning: 'bg-warning-container text-on-warning-container',
  info: 'bg-secondary-container text-on-secondary-fixed',
};

/**
 * The bell, its badge, and the menu behind it.
 *
 * <p>The count is polled rather than pushed. A websocket would be better and is
 * the obvious next step, but a poll is honest about what it is: the number can
 * be up to a minute stale, and nothing here pretends otherwise by animating a
 * change that has not happened.
 */
export const NotificationBell = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);

  const unread = useQuery({
    queryKey: ['notifications', 'unread-count'],
    queryFn: notificationApi.unreadCount,
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });

  const list = useQuery({
    queryKey: ['notifications', 'list'],
    queryFn: () => notificationApi.list({ page: 0, size: 8 }),
    // Only fetched when the menu is actually opened: a list nobody has looked
    // at is not worth a request every minute.
    enabled: open,
  });

  const markRead = useMutation({
    mutationFn: (id: number) => notificationApi.markRead(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notifications'] });
    },
  });

  const markAllRead = useMutation({
    mutationFn: () => notificationApi.markAllRead(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['notifications'] });
    },
  });

  useEffect(() => {
    if (!open) return undefined;

    const onPointerDown = (event: MouseEvent) => {
      if (panelRef.current && !panelRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };

    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const openNotification = (notification: Notification) => {
    if (!notification.read) {
      markRead.mutate(notification.id);
    }
    setOpen(false);
    if (notification.link) {
      navigate(notification.link);
    }
  };

  const count = unread.data ?? 0;

  return (
    <div className="relative" ref={panelRef}>
      <button
        type="button"
        onClick={() => setOpen((current) => !current)}
        className="relative rounded-full p-2 text-on-surface-variant transition-colors hover:bg-surface-container-low"
        aria-label={count > 0 ? `Notifications, ${count} unread` : 'Notifications'}
        aria-haspopup="menu"
        aria-expanded={open}
      >
        <Icon name="notifications" className="text-[22px]" />
        {count > 0 && (
          <span
            className="absolute right-0.5 top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-error px-1 text-[10px] font-semibold tabular-nums text-on-error"
            aria-hidden="true"
          >
            {count > 99 ? '99+' : count}
          </span>
        )}
      </button>

      {open && (
        <div
          role="menu"
          // On a phone the panel is pinned to the viewport rather than to
          // the bell. Anchored to the button it ran off the left edge: the
          // avatar sits to the bell's right, so "right-0 plus 100vw-2rem of
          // width" started roughly 28px past the screen. Fixed insets cannot
          // drift whatever ends up beside it.
          className="fixed inset-x-margin-mobile top-[calc(env(safe-area-inset-top)+4.5rem)] z-50
                     overflow-hidden rounded-lg border border-outline-variant
                     bg-surface-container-lowest shadow-lg
                     sm:absolute sm:inset-x-auto sm:right-0 sm:top-auto sm:mt-2
                     sm:w-[min(24rem,calc(100vw-2rem))]"
        >
          <div className="flex items-center justify-between border-b border-outline-variant px-md py-sm">
            <p className="text-title-sm text-on-surface">Notifications</p>
            {count > 0 && (
              <button
                type="button"
                onClick={() => markAllRead.mutate()}
                disabled={markAllRead.isPending}
                className="text-body-sm text-primary underline underline-offset-2"
              >
                Mark all read
              </button>
            )}
          </div>

          <div className="max-h-[24rem] overflow-y-auto">
            {list.isLoading && (
              <div className="flex justify-center p-lg">
                <Spinner className="h-5 w-5" />
              </div>
            )}

            {!list.isLoading && (list.data?.content.length ?? 0) === 0 && (
              <div className="flex flex-col items-center gap-xs px-md py-xl text-center">
                <Icon name="notifications_off" className="text-[28px] text-outline" />
                <p className="text-body-md text-on-surface">Nothing yet</p>
                <p className="text-body-sm text-on-surface-variant">
                  Approvals and decisions on your requests show up here.
                </p>
              </div>
            )}

            <ul>
              {list.data?.content.map((notification) => {
                const tone = notificationTone(notification.type);
                return (
                  <li key={notification.id}>
                    <button
                      type="button"
                      onClick={() => openNotification(notification)}
                      className={`flex w-full gap-sm border-b border-outline-variant px-md py-sm text-left transition-colors last:border-b-0 hover:bg-surface-container-low ${
                        notification.read ? '' : 'bg-primary-fixed/40'
                      }`}
                    >
                      <span
                        className={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full ${TONE_CLASS[tone]}`}
                        aria-hidden="true"
                      >
                        <Icon name={notificationIcon(notification.type)} className="text-[18px]" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="flex items-baseline justify-between gap-sm">
                          <span
                            className={`truncate text-body-md ${
                              notification.read ? 'text-on-surface-variant' : 'text-on-surface'
                            }`}
                          >
                            {notification.title}
                          </span>
                          <span className="shrink-0 text-body-sm text-on-surface-variant">
                            {timeAgo(notification.createdAt)}
                          </span>
                        </span>
                        {notification.body && (
                          <span className="mt-0.5 line-clamp-2 block text-body-sm text-on-surface-variant">
                            {notification.body}
                          </span>
                        )}
                      </span>
                      {!notification.read && (
                        <span
                          className="mt-2 h-2 w-2 shrink-0 rounded-full bg-primary"
                          aria-label="Unread"
                        />
                      )}
                    </button>
                  </li>
                );
              })}
            </ul>
          </div>

          <div className="border-t border-outline-variant px-md py-sm">
            <button
              type="button"
              onClick={() => {
                setOpen(false);
                navigate(ROUTES.notifications);
              }}
              className="text-body-sm text-primary underline underline-offset-2"
            >
              See all notifications
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
