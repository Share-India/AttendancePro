import { Link } from 'react-router-dom';
import { Icon } from '../../components/common/Icon';
import { ROUTES } from '../../constants/routes';

export const ForbiddenPage = () => (
  <div className="flex min-h-[60vh] flex-col items-center justify-center gap-md text-center">
    <Icon name="lock" className="text-[56px] text-outline-variant" />
    <h1 className="text-headline-lg text-on-surface">You do not have access to this page</h1>
    <p className="max-w-md text-body-md text-on-surface-variant">
      Your role does not include the permission this screen needs. If you think that is wrong, ask
      your HR administrator to review your access.
    </p>
    <Link to={ROUTES.dashboard} className="btn-primary mt-sm">
      <Icon name="arrow_back" className="text-[18px]" />
      Back to dashboard
    </Link>
  </div>
);
