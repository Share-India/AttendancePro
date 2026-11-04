import { useCallback, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { setSessionExpiredHandler } from '../../api/axiosClient';
import { queryClient } from '../../api/queryClient';
import { ROUTES } from '../../constants/routes';
import { useAuthStore } from '../../store/authStore';
import type { LoginRequest } from '../../types/auth';
import { authApi } from './authApi';

export const useAuth = () => {
  const navigate = useNavigate();
  const { user, bootstrapped, setSession, clearSession, hasPermission, hasAnyPermission, hasRole } =
    useAuthStore();

  const loginMutation = useMutation({
    mutationFn: (payload: LoginRequest) => authApi.login(payload),
    onSuccess: (response) => {
      setSession(response.user, response.accessToken);
      navigate(response.mustChangePassword ? ROUTES.changePassword : ROUTES.dashboard, {
        replace: true,
      });
    },
  });

  const logout = useCallback(async () => {
    await authApi.logout();
    clearSession();
    queryClient.clear();
    navigate(ROUTES.login, { replace: true });
  }, [clearSession, navigate]);

  return {
    user,
    bootstrapped,
    isAuthenticated: user !== null,
    login: loginMutation.mutateAsync,
    isLoggingIn: loginMutation.isPending,
    loginError: loginMutation.error,
    resetLoginError: loginMutation.reset,
    logout,
    hasPermission,
    hasAnyPermission,
    hasRole,
  };
};

/**
 * Restores the session on a hard reload.
 *
 * The access token was in memory and is gone, but the HttpOnly refresh cookie
 * survives — so we exchange it for a new access token and reload the profile.
 * Failure here is the normal "not signed in" case, not an error worth showing.
 */
export const useAuthBootstrap = (): boolean => {
  const { setSession, clearSession, bootstrapped } = useAuthStore();
  const navigate = useNavigate();
  const started = useRef(false);

  useEffect(() => {
    setSessionExpiredHandler(() => {
      clearSession();
      queryClient.clear();
      navigate(ROUTES.login, { replace: true });
    });
  }, [clearSession, navigate]);

  useEffect(() => {
    if (started.current) return;
    started.current = true;

    (async () => {
      try {
        const refreshed = await authApi.refresh();
        const profile = await authApi.me();
        setSession(profile, refreshed.accessToken);
      } catch {
        clearSession();
      }
    })();
  }, [setSession, clearSession]);

  return bootstrapped;
};
