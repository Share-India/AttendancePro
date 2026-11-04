import { useMemo } from 'react';
import { NavLink } from 'react-router-dom';
import { AppLogo } from '../components/common/AppLogo';
import { Icon } from '../components/common/Icon';
import { ROUTES } from '../constants/routes';
import { useAuthStore } from '../store/authStore';
import { cn } from '../utils/cn';
import { MENU, filterMenu, type MenuItem } from './menuConfig';

interface SidebarProps {
  open: boolean;
  onNavigate: () => void;
}

const initialsOf = (name: string): string =>
  name
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? '')
    .join('');

const NavRow = ({
  item,
  depth = 0,
  onNavigate,
}: {
  item: MenuItem;
  depth?: number;
  onNavigate: () => void;
}) => (
  <NavLink
    to={item.to}
    end={item.to === ROUTES.dashboard}
    onClick={onNavigate}
    className={({ isActive }) =>
      cn(
        'group relative flex items-center gap-3 rounded-DEFAULT py-2.5 pr-3 transition-colors',
        depth === 0 ? 'pl-4 text-body-md' : 'pl-11 text-body-sm',
        isActive
          ? 'bg-secondary-container font-semibold text-primary'
          : 'text-on-surface-variant hover:bg-surface-container-low hover:text-on-surface',
      )
    }
  >
    {({ isActive }) => (
      <>
        {/* Active state is a 4px primary bar on the left edge, per the design system. */}
        {isActive && depth === 0 && (
          <span className="absolute inset-y-1 left-0 w-1 rounded-r-full bg-primary" aria-hidden="true" />
        )}
        {depth === 0 && <Icon name={item.icon} className="shrink-0 text-[22px]" filled={isActive} />}
        <span className="truncate">{item.label}</span>
      </>
    )}
  </NavLink>
);

export const Sidebar = ({ open, onNavigate }: SidebarProps) => {
  const user = useAuthStore((state) => state.user);
  const hasAnyPermission = useAuthStore((state) => state.hasAnyPermission);

  const items = useMemo(() => filterMenu(MENU, hasAnyPermission), [hasAnyPermission]);

  return (
    <aside
      className={cn(
        // The drawer spans the full height on a phone, so it owns both
        // insets: the notch above and the home indicator below.
        'pad-safe-top pad-safe-bottom fixed inset-y-0 left-0 z-40 flex w-[260px] flex-col overflow-y-auto overscroll-contain border-r border-outline-variant/60 bg-surface transition-transform duration-200 lg:static lg:translate-x-0',
        open ? 'translate-x-0' : '-translate-x-full',
      )}
      aria-label="Main navigation"
    >
      <div className="flex px-lg py-lg">
        <AppLogo size={32} labelClassName="text-headline-md text-primary" />
      </div>

      <div className="flex flex-col items-center gap-sm px-lg pb-lg text-center">
        {user?.profilePhotoUrl ? (
          <img
            src={user.profilePhotoUrl}
            alt=""
            className="h-20 w-20 rounded-full border-2 border-surface-container-lowest object-cover shadow-raised"
          />
        ) : (
          <div
            className="flex h-20 w-20 items-center justify-center rounded-full bg-primary-fixed text-headline-lg text-on-primary-fixed"
            aria-hidden="true"
          >
            {initialsOf(user?.fullName ?? '')}
          </div>
        )}
        <div className="min-w-0">
          <p className="truncate text-headline-md text-on-surface">{user?.fullName}</p>
          <p className="truncate text-body-sm text-on-surface-variant">
            {user?.designation ?? user?.employeeCode ?? '—'}
          </p>
        </div>
      </div>

      <div className="px-lg pb-md">
        {/* Punching lives on the dashboard, where the live timer and the day's
            state are visible; this is a shortcut to it, not a second control. */}
        <NavLink to={ROUTES.dashboard} onClick={onNavigate} className="btn-primary w-full">
          <Icon name="fingerprint" className="text-[18px]" />
          Punch In / Out
        </NavLink>
      </div>

      <nav className="flex-1 space-y-px overflow-y-auto px-md pb-lg">
        {items.map((item) => (
          <div key={item.to} className="space-y-px">
            <NavRow item={item} onNavigate={onNavigate} />
            {item.children?.map((child) => (
              <NavRow key={child.to} item={child} depth={1} onNavigate={onNavigate} />
            ))}
          </div>
        ))}
      </nav>

      <div className="border-t border-outline-variant/60 px-lg py-md">
        <p className="truncate text-body-sm text-outline">{user?.office ?? 'No office assigned'}</p>
        <p className="truncate text-body-sm text-outline">{user?.employeeCode}</p>
      </div>
    </aside>
  );
};
