import { apiClient, unwrap } from '../../api/axiosClient';
import { endpoints } from '../../api/endpoints';
import type { ApiResponse, PageResponse } from '../../types/api';
import type { Meeting, MeetingParticipant, MeetingStatusPayload } from '../../types/meeting';

type ListParams = { page?: number; size?: number; status?: string };

export interface MeetingPayload {
  title: string;
  agenda?: string | null;
  meetingUrl: string;
  scheduledStartAt: string;
  scheduledEndAt: string;
  lateAfterMinutes?: number | null;
  audience?: { scopeType: string; scopeId: number | null }[];
}

export const meetingApi = {
  mine: async (params: ListParams): Promise<PageResponse<Meeting>> =>
    unwrap(await apiClient.get<ApiResponse<PageResponse<Meeting>>>(endpoints.meetings.my, { params })),

  manage: async (params: ListParams): Promise<PageResponse<Meeting>> =>
    unwrap(
      await apiClient.get<ApiResponse<PageResponse<Meeting>>>(endpoints.meetings.manage, { params }),
    ),

  get: async (id: number): Promise<Meeting> =>
    unwrap(await apiClient.get<ApiResponse<Meeting>>(endpoints.meetings.byId(id))),

  status: async (id: number): Promise<MeetingStatusPayload> =>
    unwrap(await apiClient.get<ApiResponse<MeetingStatusPayload>>(endpoints.meetings.status(id))),

  participants: async (id: number): Promise<MeetingParticipant[]> =>
    unwrap(
      await apiClient.get<ApiResponse<MeetingParticipant[]>>(endpoints.meetings.participants(id)),
    ),

  /** The raw link, for the edit form only. Everyone else joins through /join. */
  link: async (id: number): Promise<string> =>
    (
      await unwrap(
        await apiClient.get<ApiResponse<{ meetingUrl: string }>>(endpoints.meetings.link(id)),
      )
    ).meetingUrl,

  create: async (payload: MeetingPayload): Promise<Meeting> =>
    unwrap(await apiClient.post<ApiResponse<Meeting>>(endpoints.meetings.base, payload)),

  update: async (id: number, payload: MeetingPayload): Promise<Meeting> =>
    unwrap(await apiClient.put<ApiResponse<Meeting>>(endpoints.meetings.byId(id), payload)),

  publish: async (id: number): Promise<Meeting> =>
    unwrap(await apiClient.put<ApiResponse<Meeting>>(endpoints.meetings.publish(id), {})),

  start: async (id: number): Promise<Meeting> =>
    unwrap(await apiClient.put<ApiResponse<Meeting>>(endpoints.meetings.start(id), {})),

  end: async (id: number): Promise<Meeting> =>
    unwrap(await apiClient.put<ApiResponse<Meeting>>(endpoints.meetings.end(id), {})),

  cancel: async (id: number, reason: string): Promise<Meeting> =>
    unwrap(await apiClient.put<ApiResponse<Meeting>>(endpoints.meetings.cancel(id), { reason })),

  /** Records the click and returns where to go. The link arrives only here. */
  join: async (id: number): Promise<string> =>
    (
      await unwrap(
        await apiClient.post<ApiResponse<{ meetingUrl: string }>>(endpoints.meetings.join(id), {}),
      )
    ).meetingUrl,
};

/**
 * Opens a tab now, then points it at the meeting once the server has recorded
 * the click.
 *
 * <p>The window has to be opened synchronously inside the click handler.
 * Calling window.open after an await looks to the browser like a popup nobody
 * asked for, and it is blocked — which would leave the person marked as having
 * joined a meeting they never got to.
 */
export const openMeeting = async (id: number): Promise<void> => {
  const tab = window.open('', '_blank', 'noopener,noreferrer');
  try {
    const url = await meetingApi.join(id);
    if (tab) {
      tab.location.href = url;
    } else {
      // Popups blocked entirely: fall back to this tab rather than stranding
      // somebody who has already been recorded as present.
      window.location.href = url;
    }
  } catch (error) {
    tab?.close();
    throw error;
  }
};
