import { useQuery } from '@tanstack/react-query';
import { masterApi } from './masterApi';

/**
 * Master data changes rarely and is needed by almost every form, so it is
 * cached for longer than the default and shared across screens by query key.
 */
const MASTER_STALE_TIME = 5 * 60 * 1000;

export const useDepartments = (activeOnly = true) =>
  useQuery({
    queryKey: ['master', 'departments', activeOnly],
    queryFn: () => masterApi.departments.list(activeOnly),
    staleTime: MASTER_STALE_TIME,
  });

export const useDesignations = (activeOnly = true) =>
  useQuery({
    queryKey: ['master', 'designations', activeOnly],
    queryFn: () => masterApi.designations.list(activeOnly),
    staleTime: MASTER_STALE_TIME,
  });

export const useOffices = (activeOnly = true) =>
  useQuery({
    queryKey: ['master', 'offices', activeOnly],
    queryFn: () => masterApi.offices.list(activeOnly),
    staleTime: MASTER_STALE_TIME,
  });

export const useShifts = (activeOnly = true) =>
  useQuery({
    queryKey: ['master', 'shifts', activeOnly],
    queryFn: () => masterApi.shifts.list(activeOnly),
    staleTime: MASTER_STALE_TIME,
  });
