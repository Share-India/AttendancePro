import { apiClient, unwrap } from '../../api/axiosClient';
import { endpoints } from '../../api/endpoints';
import type { ApiResponse, PageResponse } from '../../types/api';
import type {
  AttendanceTrend,
  BulkImportResult,
  DashboardSummary,
  TeamMemberDay,
} from '../../types/dashboard';

interface RosterParams {
  date?: string;
  dayState?: string;
  departmentId?: number;
  officeId?: number;
  search?: string;
  page?: number;
  size?: number;
}

export const dashboardApi = {
  summary: async (params: { date?: string; departmentId?: number; officeId?: number }):
    Promise<DashboardSummary> =>
    unwrap(await apiClient.get<ApiResponse<DashboardSummary>>(endpoints.dashboard.summary, { params })),

  roster: async (params: RosterParams): Promise<PageResponse<TeamMemberDay>> =>
    unwrap(await apiClient.get<ApiResponse<PageResponse<TeamMemberDay>>>(
      endpoints.dashboard.roster, { params })),

  trend: async (params: { fromDate?: string; toDate?: string }): Promise<AttendanceTrend> =>
    unwrap(await apiClient.get<ApiResponse<AttendanceTrend>>(endpoints.dashboard.trend, { params })),
};

export const employeeImportApi = {
  /** The template is CSV text, not JSON, so it bypasses the usual unwrap. */
  template: async (): Promise<string> => {
    const response = await apiClient.get<string>(endpoints.employees.bulkImportTemplate, {
      responseType: 'text',
    });
    return response.data;
  },

  upload: async (file: File): Promise<BulkImportResult> => {
    const form = new FormData();
    form.append('file', file);
    return unwrap(await apiClient.post<ApiResponse<BulkImportResult>>(
      endpoints.employees.bulkImport, form,
      { headers: { 'Content-Type': 'multipart/form-data' } }));
  },
};
