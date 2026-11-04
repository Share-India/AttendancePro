import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { FullPageLoader } from '../components/common/Spinner';
import { ROUTES } from '../constants/routes';
import { useAuthStore } from '../store/authStore';

/**
 * Waits for the bootstrap refresh before deciding. Without that check a hard
 * reload would bounce a signed-in user to the login page for a moment.
 */
export const ProtectedRoute = () => {
  const { user, bootstrapped } = useAuthStore();
  const location = useLocation();

  if (!bootstrapped) {
    return <FullPageLoader message="Restoring your session" />;
  }

  if (!user) {
    return <Navigate to={ROUTES.login} state={{ from: location.pathname }} replace />;
  }

  // An admin-created account cannot go anywhere until its password is rotated.
  if (user.mustChangePassword && location.pathname !== ROUTES.changePassword) {
    return <Navigate to={ROUTES.changePassword} replace />;
  }

  return <Outlet />;
};
