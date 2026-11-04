import axios, {
  AxiosError,
  type AxiosInstance,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios';
import { ApiError, type ApiResponse } from '../types/api';

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080/api/v1';

/**
 * The access token lives in memory only.
 *
 * Not localStorage: anything that can run a script on the page can read that,
 * and a stolen token is a valid session. The refresh token is an HttpOnly
 * cookie the browser sends automatically, which no script can read either.
 * The cost is a refresh on hard reload, which is what the bootstrap flow does.
 */
let accessToken: string | null = null;
let onSessionExpired: (() => void) | null = null;

export const setAccessToken = (token: string | null): void => {
  accessToken = token;
};

export const getAccessToken = (): string | null => accessToken;

export const setSessionExpiredHandler = (handler: () => void): void => {
  onSessionExpired = handler;
};

export const apiClient: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 20_000,
  withCredentials: true, // sends the refresh cookie
  headers: { 'Content-Type': 'application/json' },
});

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  return config;
});

// ---------------------------------------------------------------------------
// Refresh-and-retry
//
// Several requests can fail with 401 at the same moment. Only the first one
// refreshes; the rest wait on that same promise and replay afterwards, so a
// page with six queries does not fire six rotations and trip the API's own
// reuse detection.
// ---------------------------------------------------------------------------

let refreshPromise: Promise<string> | null = null;

const refreshAccessToken = async (): Promise<string> => {
  const response = await axios.post<ApiResponse<{ accessToken: string }>>(
    `${BASE_URL}/auth/refresh`,
    {},
    { withCredentials: true },
  );
  const token = response.data.data?.accessToken;
  if (!token) {
    throw new Error('Refresh response contained no access token');
  }
  setAccessToken(token);
  return token;
};

interface RetriableRequest extends InternalAxiosRequestConfig {
  _retried?: boolean;
}

apiClient.interceptors.response.use(
  (response: AxiosResponse) => response,
  async (error: AxiosError<ApiResponse<unknown>>) => {
    const original = error.config as RetriableRequest | undefined;
    const status = error.response?.status;
    const body = error.response?.data;
    const code = body?.errorCode;

    const isExpiredToken = status === 401 && (code === 'TOKEN_EXPIRED' || code === 'TOKEN_MISSING');

    // Only the endpoints that would loop are excluded. An earlier version
    // excluded everything under /auth/, which also caught /auth/me - a normal
    // protected endpoint that must be allowed to retry after a refresh.
    const NON_RETRYABLE = ['/auth/login', '/auth/refresh', '/auth/logout'];
    const isAuthEndpoint = NON_RETRYABLE.some((path) => original?.url?.includes(path));

    if (isExpiredToken && original && !original._retried && !isAuthEndpoint) {
      original._retried = true;
      try {
        refreshPromise = refreshPromise ?? refreshAccessToken();
        const token = await refreshPromise;
        refreshPromise = null;
        original.headers.Authorization = `Bearer ${token}`;
        return apiClient(original);
      } catch {
        refreshPromise = null;
        setAccessToken(null);
        onSessionExpired?.();
        throw new ApiError('TOKEN_EXPIRED', 'Your session has expired. Please sign in again.', 401);
      }
    }

    // Reuse detection or a dead refresh token means every session is gone.
    if (status === 401 && (code === 'REFRESH_TOKEN_REUSED' || code === 'REFRESH_TOKEN_EXPIRED')) {
      setAccessToken(null);
      onSessionExpired?.();
    }

    if (body) {
      throw new ApiError(
        body.errorCode ?? 'INTERNAL_ERROR',
        body.message ?? 'Something went wrong.',
        status ?? 0,
        body.data,
        body.traceId,
      );
    }

    if (error.code === 'ECONNABORTED') {
      throw new ApiError('INTERNAL_ERROR', 'The request timed out. Please try again.', 0);
    }

    throw new ApiError(
      'INTERNAL_ERROR',
      'Cannot reach the server. Check your connection and try again.',
      status ?? 0,
    );
  },
);

/** Unwraps the envelope so callers work with the payload, not the wrapper. */
export const unwrap = <T>(response: AxiosResponse<ApiResponse<T>>): T => {
  const { data } = response;
  if (!data.success) {
    throw new ApiError(data.errorCode ?? 'INTERNAL_ERROR', data.message, response.status, data.data, data.traceId);
  }
  return data.data as T;
};
