import { apiClient, unwrap } from '../../api/axiosClient';
import { endpoints } from '../../api/endpoints';
import type { ApiResponse, PageResponse } from '../../types/api';
import type {
  BulkImportResult,
  EmployeeCreationResult,
  EmployeeDetail,
  EmployeeSearchParams,
  EmployeeSummary,
  ShiftAssignment,
} from '../../types/employee';

export const employeeApi = {
  search: async (params: EmployeeSearchParams): Promise<PageResponse<EmployeeSummary>> => {
    const response = await apiClient.get<ApiResponse<PageResponse<EmployeeSummary>>>(
      endpoints.employees.base,
      { params },
    );
    return unwrap(response);
  },

  findById: async (id: number): Promise<EmployeeDetail> => {
    const response = await apiClient.get<ApiResponse<EmployeeDetail>>(endpoints.employees.byId(id));
    return unwrap(response);
  },

  me: async (): Promise<EmployeeDetail> => {
    const response = await apiClient.get<ApiResponse<EmployeeDetail>>(endpoints.employees.me);
    return unwrap(response);
  },

  updateMe: async (payload: Record<string, unknown>): Promise<EmployeeDetail> => {
    const response = await apiClient.put<ApiResponse<EmployeeDetail>>(endpoints.employees.me, payload);
    return unwrap(response);
  },

  create: async (payload: Record<string, unknown>): Promise<EmployeeCreationResult> => {
    const response = await apiClient.post<ApiResponse<EmployeeCreationResult>>(
      endpoints.employees.base,
      payload,
    );
    return unwrap(response);
  },

  update: async (id: number, payload: Record<string, unknown>): Promise<EmployeeDetail> => {
    const response = await apiClient.put<ApiResponse<EmployeeDetail>>(
      endpoints.employees.byId(id),
      payload,
    );
    return unwrap(response);
  },

  deactivate: async (id: number, reason?: string): Promise<void> => {
    await apiClient.patch(endpoints.employees.deactivate(id), null, { params: { reason } });
  },

  reactivate: async (id: number): Promise<void> => {
    await apiClient.patch(endpoints.employees.reactivate(id));
  },

  assignManager: async (id: number, payload: Record<string, unknown>): Promise<EmployeeDetail> => {
    const response = await apiClient.put<ApiResponse<EmployeeDetail>>(
      endpoints.employees.manager(id),
      payload,
    );
    return unwrap(response);
  },

  assignShift: async (id: number, payload: Record<string, unknown>): Promise<ShiftAssignment> => {
    const response = await apiClient.put<ApiResponse<ShiftAssignment>>(
      endpoints.employees.shift(id),
      payload,
    );
    return unwrap(response);
  },

  assignRoles: async (id: number, roleCodes: string[]): Promise<EmployeeDetail> => {
    const response = await apiClient.put<ApiResponse<EmployeeDetail>>(endpoints.employees.roles(id), {
      roleCodes,
    });
    return unwrap(response);
  },

  bulkImport: async (file: File): Promise<BulkImportResult> => {
    const form = new FormData();
    form.append('file', file);
    const response = await apiClient.post<ApiResponse<BulkImportResult>>(
      endpoints.employees.bulkImport,
      form,
      { headers: { 'Content-Type': 'multipart/form-data' } },
    );
    return unwrap(response);
  },
};
