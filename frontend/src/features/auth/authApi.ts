import { apiClient, setAccessToken, unwrap } from '../../api/axiosClient';
import { endpoints } from '../../api/endpoints';
import type { ApiResponse } from '../../types/api';
import type { CurrentUser, LoginRequest, LoginResponse, TokenRefreshResponse } from '../../types/auth';

export const authApi = {
  login: async (payload: LoginRequest): Promise<LoginResponse> => {
    const response = await apiClient.post<ApiResponse<LoginResponse>>(endpoints.auth.login, payload, { headers: { 'Content-Type': 'application/json' } });
    return unwrap(response);
  },

  /**
   * Used on hard reload: the HttpOnly cookie is the only surviving credential.
   *
   * The new token is installed here rather than by the caller. Leaving that to
   * the caller meant the very next request went out unauthenticated - which is
   * exactly what broke session restore.
   */
  refresh: async (): Promise<TokenRefreshResponse> => {
    const response = await apiClient.post<ApiResponse<TokenRefreshResponse>>(endpoints.auth.refresh, {});
    const result = unwrap(response);
    setAccessToken(result.accessToken);
    return result;
  },

  me: async (): Promise<CurrentUser> => {
    const response = await apiClient.get<ApiResponse<CurrentUser>>(endpoints.auth.me);
    return unwrap(response);
  },

  logout: async (): Promise<void> => {
    try {
      await apiClient.post(endpoints.auth.logout, {});
    } finally {
      // Sign-out must clear the client even if the server call fails.
      setAccessToken(null);
    }
  },

  forgotPassword: async (email: string): Promise<string> => {
    const response = await apiClient.post<ApiResponse<void>>(endpoints.auth.forgotPassword, { email });
    return response.data.message;
  },

  resetPassword: async (payload: {
    token: string;
    newPassword: string;
    confirmPassword: string;
  }): Promise<void> => {
    await apiClient.post<ApiResponse<void>>(endpoints.auth.resetPassword, payload);
  },

  changePassword: async (payload: {
    currentPassword: string;
    newPassword: string;
    confirmPassword: string;
  }): Promise<void> => {
    await apiClient.post<ApiResponse<void>>(endpoints.auth.changePassword, payload);
  },
};
