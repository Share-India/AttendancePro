import { apiClient, unwrap } from '../../api/axiosClient';
import { endpoints } from '../../api/endpoints';
import type { ApiResponse } from '../../types/api';
import type { Department, Designation, Holiday, Office, OfficeLocation, Shift } from '../../types/master';

const list = async <T>(url: string, activeOnly = true): Promise<T[]> => {
  const response = await apiClient.get<ApiResponse<T[]>>(url, { params: { activeOnly } });
  return unwrap(response);
};

export const masterApi = {
  holidays: {
    list: async (year: number | null, activeOnly = true): Promise<Holiday[]> => {
      const response = await apiClient.get<ApiResponse<Holiday[]>>(endpoints.admin.holidays, {
        // year omitted rather than sent as null: the endpoint treats an
        // absent year as "every year", and a null would serialise as "null".
        params: year === null ? { activeOnly } : { year, activeOnly },
      });
      return unwrap(response);
    },
    create: async (payload: Record<string, unknown>) =>
      unwrap(await apiClient.post<ApiResponse<Holiday>>(endpoints.admin.holidays, payload)),
    update: async (id: number, payload: Record<string, unknown>) =>
      unwrap(await apiClient.put<ApiResponse<Holiday>>(endpoints.admin.holidayById(id), payload)),
    withdraw: async (id: number) => {
      await apiClient.delete(endpoints.admin.holidayById(id));
    },
  },

  departments: {
    list: (activeOnly = true) => list<Department>(endpoints.admin.departments, activeOnly),
    create: async (payload: Record<string, unknown>) =>
      unwrap(await apiClient.post<ApiResponse<Department>>(endpoints.admin.departments, payload)),
    update: async (id: number, payload: Record<string, unknown>) =>
      unwrap(await apiClient.put<ApiResponse<Department>>(endpoints.admin.departmentById(id), payload)),
    deactivate: async (id: number) => {
      await apiClient.delete(endpoints.admin.departmentById(id));
    },
  },

  designations: {
    list: (activeOnly = true) => list<Designation>(endpoints.admin.designations, activeOnly),
    create: async (payload: Record<string, unknown>) =>
      unwrap(await apiClient.post<ApiResponse<Designation>>(endpoints.admin.designations, payload)),
    update: async (id: number, payload: Record<string, unknown>) =>
      unwrap(await apiClient.put<ApiResponse<Designation>>(endpoints.admin.designationById(id), payload)),
    deactivate: async (id: number) => {
      await apiClient.delete(endpoints.admin.designationById(id));
    },
  },

  offices: {
    list: (activeOnly = true) => list<Office>(endpoints.admin.offices, activeOnly),
    create: async (payload: Record<string, unknown>) =>
      unwrap(await apiClient.post<ApiResponse<Office>>(endpoints.admin.offices, payload)),
    update: async (id: number, payload: Record<string, unknown>) =>
      unwrap(await apiClient.put<ApiResponse<Office>>(endpoints.admin.officeById(id), payload)),
    deactivate: async (id: number) => {
      await apiClient.delete(endpoints.admin.officeById(id));
    },
    addLocation: async (officeId: number, payload: Record<string, unknown>) =>
      unwrap(
        await apiClient.post<ApiResponse<OfficeLocation>>(
          endpoints.admin.officeLocations(officeId),
          payload,
        ),
      ),
    updateLocation: async (officeId: number, locationId: number, payload: Record<string, unknown>) =>
      unwrap(
        await apiClient.put<ApiResponse<OfficeLocation>>(
          endpoints.admin.officeLocationById(officeId, locationId),
          payload,
        ),
      ),
    deactivateLocation: async (officeId: number, locationId: number) => {
      await apiClient.delete(endpoints.admin.officeLocationById(officeId, locationId));
    },
  },

  shifts: {
    list: (activeOnly = true) => list<Shift>(endpoints.admin.shifts, activeOnly),
    create: async (payload: Record<string, unknown>) =>
      unwrap(await apiClient.post<ApiResponse<Shift>>(endpoints.admin.shifts, payload)),
    update: async (id: number, payload: Record<string, unknown>) =>
      unwrap(await apiClient.put<ApiResponse<Shift>>(endpoints.admin.shiftById(id), payload)),
    deactivate: async (id: number) => {
      await apiClient.delete(endpoints.admin.shiftById(id));
    },
  },
};
