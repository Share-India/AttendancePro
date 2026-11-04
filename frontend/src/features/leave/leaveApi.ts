import { apiClient, unwrap } from '../../api/axiosClient';
import { endpoints } from '../../api/endpoints';
import type { ApiResponse, PageResponse } from '../../types/api';
import type {
  LeaveApplyResult,
  LeaveBalance,
  LeaveRequest,
  LeaveTypeOption,
} from '../../types/leave';

export const leaveApi = {
  types: async (): Promise<LeaveTypeOption[]> =>
    unwrap(await apiClient.get<ApiResponse<LeaveTypeOption[]>>(endpoints.leaves.types)),

  balances: async (): Promise<LeaveBalance[]> =>
    unwrap(await apiClient.get<ApiResponse<LeaveBalance[]>>(endpoints.leaves.balance)),

  apply: async (payload: Record<string, unknown>): Promise<LeaveApplyResult> =>
    unwrap(await apiClient.post<ApiResponse<LeaveApplyResult>>(endpoints.leaves.base, payload)),

  myRequests: async (params: { status?: string; page?: number; size?: number }):
    Promise<PageResponse<LeaveRequest>> =>
    unwrap(await apiClient.get<ApiResponse<PageResponse<LeaveRequest>>>(endpoints.leaves.my, { params })),

  findById: async (id: number): Promise<LeaveRequest> =>
    unwrap(await apiClient.get<ApiResponse<LeaveRequest>>(endpoints.leaves.byId(id))),

  pendingApprovals: async (params: { page?: number; size?: number }):
    Promise<PageResponse<LeaveRequest>> =>
    unwrap(await apiClient.get<ApiResponse<PageResponse<LeaveRequest>>>(
      endpoints.leaves.pendingApprovals, { params })),

  approve: async (id: number, comments?: string): Promise<LeaveRequest> =>
    unwrap(await apiClient.put<ApiResponse<LeaveRequest>>(endpoints.leaves.approve(id), { comments })),

  reject: async (id: number, comments: string): Promise<LeaveRequest> =>
    unwrap(await apiClient.put<ApiResponse<LeaveRequest>>(endpoints.leaves.reject(id), { comments })),

  withdraw: async (id: number, reason?: string): Promise<LeaveRequest> =>
    unwrap(await apiClient.put<ApiResponse<LeaveRequest>>(endpoints.leaves.withdraw(id), { reason })),

  cancel: async (id: number, reason?: string): Promise<LeaveRequest> =>
    unwrap(await apiClient.put<ApiResponse<LeaveRequest>>(endpoints.leaves.cancel(id), { reason })),
};
