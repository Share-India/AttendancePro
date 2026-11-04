import { Link } from 'react-router-dom';
import { Icon } from '../../components/common/Icon';
import { ROUTES } from '../../constants/routes';

export const NotFoundPage = () => (
  <div className="flex min-h-screen flex-col items-center justify-center gap-md px-margin-mobile text-center">
    <Icon name="explore_off" className="text-[56px] text-outline-variant" />
    <h1 className="text-headline-lg text-on-surface">Page not found</h1>
    <p className="max-w-md text-body-md text-on-surface-variant">
      That address does not match anything in the app. It may have moved, or the link may be
      incomplete.
    </p>
    <Link to={ROUTES.dashboard} className="btn-primary mt-sm">
      <Icon name="arrow_back" className="text-[18px]" />
      Back to dashboard
    </Link>
  </div>
);
