import { Navigate, Outlet } from 'react-router-dom';
import { FullPageLoader } from '../components/common/Spinner';
import { ROUTES } from '../constants/routes';
import { useAuthStore } from '../store/authStore';

/** Keeps a signed-in user off the login and password-reset screens. */
export const PublicOnlyRoute = () => {
  const { user, bootstrapped } = useAuthStore();

  if (!bootstrapped) {
    return <FullPageLoader message="Loading" />;
  }

  return user ? <Navigate to={ROUTES.dashboard} replace /> : <Outlet />;
};
