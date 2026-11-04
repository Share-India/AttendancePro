import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { notificationApi } from '../../features/notification/notificationApi';
import { ApiError } from '../../types/api';
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

export const NotificationsPage = () => {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [page, setPage] = useState(0);

  const { data, isLoading, error } = useQuery({
    queryKey: ['notifications', 'page', unreadOnly, page],
    queryFn: () => notificationApi.list({ unreadOnly, page, size: 20 }),
    placeholderData: keepPreviousData,
  });

  const markRead = useMutation({
    mutationFn: (id: number) => notificationApi.markRead(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  });

  const markAllRead = useMutation({
    mutationFn: () => notificationApi.markAllRead(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  });

  const open = (notification: Notification) => {
    if (!notification.read) {
      markRead.mutate(notification.id);
    }
    if (notification.link) {
      navigate(notification.link);
    }
  };

  const rows = data?.content ?? [];

  return (
    <div className="mx-auto max-w-[880px]">
      <PageHeader
        title="Notifications"
        description="Approvals waiting on you, and decisions on what you asked for."
        actions={
          <button
            type="button"
            className="btn-secondary"
            onClick={() => markAllRead.mutate()}
            disabled={markAllRead.isPending}
          >
            {markAllRead.isPending ? (
              <Spinner className="h-4 w-4" />
            ) : (
              <Icon name="done_all" className="text-[18px]" />
            )}
            Mark all read
          </button>
        }
      />

      <div className="mb-lg flex gap-xs" role="tablist" aria-label="Filter">
        {[
          { label: 'All', value: false },
          { label: 'Unread', value: true },
        ].map((tab) => (
          <button
            key={tab.label}
            type="button"
            role="tab"
            aria-selected={unreadOnly === tab.value}
            onClick={() => {
              setUnreadOnly(tab.value);
              setPage(0);
            }}
            className={`rounded-full px-md py-1.5 text-label-lg transition-colors ${
              unreadOnly === tab.value
                ? 'bg-primary text-on-primary'
                : 'bg-surface-container text-on-surface-variant hover:bg-surface-container-high'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {error && (
        <Alert variant="error" title="Could not load notifications" className="mb-lg">
          {error instanceof ApiError ? error.message : 'Please try again.'}
        </Alert>
      )}

      {isLoading && (
        <div className="flex justify-center p-xl">
          <Spinner className="h-6 w-6" />
        </div>
      )}

      {!isLoading && rows.length === 0 && (
        <div className="card flex flex-col items-center gap-xs p-xl text-center">
          <Icon name="notifications_off" className="text-[32px] text-outline" />
          <p className="text-title-sm text-on-surface">
            {unreadOnly ? 'Nothing unread' : 'Nothing yet'}
          </p>
          <p className="text-body-md text-on-surface-variant">
            Approvals and decisions on your requests show up here.
          </p>
        </div>
      )}

      <ul className="flex flex-col gap-sm">
        {rows.map((notification) => {
          const tone = notificationTone(notification.type);
          return (
            <li key={notification.id}>
              <button
                type="button"
                onClick={() => open(notification)}
                className={`card flex w-full gap-md p-md text-left transition-colors hover:bg-surface-container-low ${
                  notification.read ? '' : 'border-l-4 border-l-primary'
                }`}
              >
                <span
                  className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-full ${TONE_CLASS[tone]}`}
                  aria-hidden="true"
                >
                  <Icon name={notificationIcon(notification.type)} className="text-[20px]" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="flex flex-wrap items-baseline justify-between gap-sm">
                    <span
                      className={`text-body-lg ${
                        notification.read ? 'text-on-surface-variant' : 'text-on-surface'
                      }`}
                    >
                      {notification.title}
                    </span>
                    <span className="text-body-sm text-on-surface-variant">
                      {timeAgo(notification.createdAt)}
                    </span>
                  </span>
                  {notification.body && (
                    <span className="mt-1 block text-body-md text-on-surface-variant">
                      {notification.body}
                    </span>
                  )}
                  {notification.actorName && (
                    <span className="mt-1 block text-body-sm text-on-surface-variant">
                      by {notification.actorName}
                    </span>
                  )}
                </span>
                {notification.link && (
                  <Icon name="chevron_right" className="mt-1 text-[20px] text-outline" />
                )}
              </button>
            </li>
          );
        })}
      </ul>

      {data && data.totalPages > 1 && (
        <div className="mt-lg flex items-center justify-center gap-md">
          <button
            type="button"
            className="btn-secondary"
            disabled={data.first}
            onClick={() => setPage((current) => Math.max(0, current - 1))}
          >
            <Icon name="chevron_left" className="text-[18px]" />
            Newer
          </button>
          <span className="text-body-sm tabular-nums text-on-surface-variant">
            {data.page + 1} / {data.totalPages}
          </span>
          <button
            type="button"
            className="btn-secondary"
            disabled={data.last}
            onClick={() => setPage((current) => current + 1)}
          >
            Older
            <Icon name="chevron_right" className="text-[18px]" />
          </button>
        </div>
      )}
    </div>
  );
};
