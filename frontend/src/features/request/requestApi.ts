import { apiClient, unwrap } from '../../api/axiosClient';
import { endpoints } from '../../api/endpoints';
import type { ApiResponse, PageResponse } from '../../types/api';
import type { Regularization, WorkFromHomeRequest } from '../../types/request';

type ListParams = { status?: string; page?: number; size?: number };

export const regularizationApi = {
  apply: async (payload: Record<string, unknown>): Promise<Regularization> =>
    unwrap(await apiClient.post<ApiResponse<Regularization>>(endpoints.regularizations.base, payload)),

  myRequests: async (params: ListParams): Promise<PageResponse<Regularization>> =>
    unwrap(await apiClient.get<ApiResponse<PageResponse<Regularization>>>(
      endpoints.regularizations.my, { params })),

  pendingApprovals: async (params: ListParams): Promise<PageResponse<Regularization>> =>
    unwrap(await apiClient.get<ApiResponse<PageResponse<Regularization>>>(
      endpoints.regularizations.pendingApprovals, { params })),

  approve: async (id: number, comments?: string): Promise<Regularization> =>
    unwrap(await apiClient.put<ApiResponse<Regularization>>(
      endpoints.regularizations.approve(id), { comments })),

  reject: async (id: number, comments: string): Promise<Regularization> =>
    unwrap(await apiClient.put<ApiResponse<Regularization>>(
      endpoints.regularizations.reject(id), { comments })),

  withdraw: async (id: number, reason?: string): Promise<Regularization> =>
    unwrap(await apiClient.put<ApiResponse<Regularization>>(
      endpoints.regularizations.withdraw(id), null, { params: { reason } })),
};

export const wfhApi = {
  apply: async (payload: Record<string, unknown>): Promise<WorkFromHomeRequest> =>
    unwrap(await apiClient.post<ApiResponse<WorkFromHomeRequest>>(endpoints.wfh.base, payload)),

  myRequests: async (params: ListParams): Promise<PageResponse<WorkFromHomeRequest>> =>
    unwrap(await apiClient.get<ApiResponse<PageResponse<WorkFromHomeRequest>>>(
      endpoints.wfh.my, { params })),

  pendingApprovals: async (params: ListParams): Promise<PageResponse<WorkFromHomeRequest>> =>
    unwrap(await apiClient.get<ApiResponse<PageResponse<WorkFromHomeRequest>>>(
      endpoints.wfh.pendingApprovals, { params })),

  approve: async (id: number, comments?: string): Promise<WorkFromHomeRequest> =>
    unwrap(await apiClient.put<ApiResponse<WorkFromHomeRequest>>(
      endpoints.wfh.approve(id), { comments })),

  reject: async (id: number, comments: string): Promise<WorkFromHomeRequest> =>
    unwrap(await apiClient.put<ApiResponse<WorkFromHomeRequest>>(
      endpoints.wfh.reject(id), { comments })),

  withdraw: async (id: number, reason?: string): Promise<WorkFromHomeRequest> =>
    unwrap(await apiClient.put<ApiResponse<WorkFromHomeRequest>>(
      endpoints.wfh.withdraw(id), null, { params: { reason } })),

  cancel: async (id: number, reason?: string): Promise<WorkFromHomeRequest> =>
    unwrap(await apiClient.put<ApiResponse<WorkFromHomeRequest>>(
      endpoints.wfh.cancel(id), null, { params: { reason } })),
};
