import { create } from 'zustand';
import { setAccessToken } from '../api/axiosClient';
import type { CurrentUser } from '../types/auth';

/**
 * Session state only. Server data belongs to TanStack Query — keeping a second
 * copy here is the usual reason a dashboard disagrees with the table below it.
 *
 * `bootstrapped` distinguishes "we have not checked yet" from "checked, not
 * signed in", so a hard reload does not flash the login page before the silent
 * refresh completes.
 */
interface AuthState {
  user: CurrentUser | null;
  bootstrapped: boolean;

  setSession: (user: CurrentUser, accessToken: string) => void;
  setUser: (user: CurrentUser) => void;
  clearSession: () => void;
  markBootstrapped: () => void;

  hasPermission: (permission: string) => boolean;
  hasAnyPermission: (permissions: string[]) => boolean;
  hasRole: (role: string) => boolean;
}

export const useAuthStore = create<AuthState>((set, get) => ({
  user: null,
  bootstrapped: false,

  setSession: (user, accessToken) => {
    setAccessToken(accessToken);
    set({ user, bootstrapped: true });
  },

  setUser: (user) => set({ user }),

  clearSession: () => {
    setAccessToken(null);
    set({ user: null, bootstrapped: true });
  },

  markBootstrapped: () => set({ bootstrapped: true }),

  hasPermission: (permission) => get().user?.permissions.includes(permission) ?? false,

  hasAnyPermission: (permissions) => {
    const held = get().user?.permissions;
    if (!held) return false;
    return permissions.some((permission) => held.includes(permission));
  },

  hasRole: (role) => get().user?.roles.includes(role) ?? false,
}));

export const useIsAuthenticated = (): boolean => useAuthStore((state) => state.user !== null);
