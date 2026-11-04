import { apiClient, unwrap } from '../../api/axiosClient';
import { endpoints } from '../../api/endpoints';
import type { ApiResponse, PageResponse } from '../../types/api';
import type { Notification } from '../../types/notification';

export const notificationApi = {
  list: async (params: { unreadOnly?: boolean; page?: number; size?: number }):
    Promise<PageResponse<Notification>> =>
    unwrap(await apiClient.get<ApiResponse<PageResponse<Notification>>>(
      endpoints.notifications.base, { params })),

  unreadCount: async (): Promise<number> => {
    const data = await unwrap(
      await apiClient.get<ApiResponse<{ unread: number }>>(endpoints.notifications.unreadCount));
    return data.unread;
  },

  markRead: async (id: number): Promise<Notification> =>
    unwrap(await apiClient.patch<ApiResponse<Notification>>(endpoints.notifications.read(id))),

  markAllRead: async (): Promise<number> => {
    const data = await unwrap(
      await apiClient.patch<ApiResponse<{ marked: number }>>(endpoints.notifications.readAll));
    return data.marked;
  },
};
