import { apiClient, unwrap } from '../../api/axiosClient';
import { endpoints } from '../../api/endpoints';
import type { ApiResponse } from '../../types/api';
import type { ReportResult, ReportSummary } from '../../types/report';

export interface ReportParams {
  fromDate?: string;
  toDate?: string;
  employeeIds?: number[];
  departmentId?: number;
  officeId?: number;
  status?: string;
  page?: number;
  size?: number;
}

export const reportApi = {
  catalogue: async (): Promise<ReportSummary[]> =>
    unwrap(await apiClient.get<ApiResponse<ReportSummary[]>>(endpoints.reports.catalogue)),

  run: async (type: string, params: ReportParams): Promise<ReportResult> =>
    unwrap(await apiClient.get<ApiResponse<ReportResult>>(endpoints.reports.run(type), { params })),

  /**
   * Downloads an export.
   *
   * <p>Fetched as a blob and saved from memory rather than linked to directly:
   * the endpoint needs the bearer token, and a plain anchor would send the
   * browser off without it and land on a 401 page instead of a file.
   */
  download: async (type: string, format: string, params: ReportParams): Promise<void> => {
    const response = await apiClient.get(endpoints.reports.export(type), {
      params: {
        ...params,
        format,
        // So exported times read in the day the reader actually lived.
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
      },
      responseType: 'blob',
    });

    // Readable only because the server lists Content-Disposition in
    // Access-Control-Expose-Headers; without that a cross-origin response hides
    // it and every download saves under a guessed name.
    const disposition = String(response.headers['content-disposition'] ?? '');
    const match = disposition.match(/filename="?([^"]+)"?/);
    const extension = format === 'EXCEL' ? 'xlsx' : format.toLowerCase();
    const fallback = `${type.toLowerCase().replace(/_/g, '-')}`
      + `_${params.fromDate ?? ''}_to_${params.toDate ?? ''}.${extension}`;
    const fileName = match ? match[1] : fallback;

    const url = URL.createObjectURL(response.data as Blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = fileName;
    anchor.click();
    URL.revokeObjectURL(url);
  },
};
