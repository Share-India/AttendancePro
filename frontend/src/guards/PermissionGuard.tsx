import { Navigate, Outlet } from 'react-router-dom';
import { ROUTES } from '../constants/routes';
import { useAuthStore } from '../store/authStore';

interface PermissionGuardProps {
  /** The user needs at least one of these. */
  anyOf: string[];
}

/**
 * Presentation-level gate. The API enforces the same permission independently —
 * this only avoids showing a screen that would fail on every request.
 */
export const PermissionGuard = ({ anyOf }: PermissionGuardProps) => {
  const hasAnyPermission = useAuthStore((state) => state.hasAnyPermission);
  return hasAnyPermission(anyOf) ? <Outlet /> : <Navigate to={ROUTES.forbidden} replace />;
};
