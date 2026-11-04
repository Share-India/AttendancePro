import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Icon } from '../components/common/Icon';
import { ROUTES } from '../constants/routes';
import { useAuth } from '../features/auth/useAuth';
import { NotificationBell } from '../features/notification/NotificationBell';
import { useAuthStore } from '../store/authStore';

interface TopbarProps {
  onToggleSidebar: () => void;
}

export const Topbar = ({ onToggleSidebar }: TopbarProps) => {
  const user = useAuthStore((state) => state.user);
  const { logout } = useAuth();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!menuOpen) return;

    const onPointerDown = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false);
    };

    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [menuOpen]);

  return (
    // pad-safe-top: the bar sits at the very top, so it is what the notch
    // would cover. box-content keeps the inset additive rather than eating
    // into the 64px the bar is supposed to be.
    <header className="pad-safe-top pad-safe-x sticky top-0 z-30 box-content flex h-16 items-center gap-md border-b border-outline-variant/60 bg-surface-container-lowest px-margin-mobile md:px-margin-desktop">
      <button
        type="button"
        onClick={onToggleSidebar}
        className="rounded-DEFAULT p-2 text-on-surface-variant transition-colors hover:bg-surface-container-low lg:hidden"
        aria-label="Toggle navigation"
      >
        <Icon name="menu" className="text-[24px]" />
      </button>

      <div className="ml-auto flex items-center gap-xs">
        <NotificationBell />

        <div className="relative" ref={menuRef}>
          <button
            type="button"
            onClick={() => setMenuOpen((open) => !open)}
            className="flex items-center gap-2 rounded-full p-1 transition-colors hover:bg-surface-container-low"
            aria-haspopup="menu"
            aria-expanded={menuOpen}
          >
            <span className="flex h-9 w-9 items-center justify-center rounded-full bg-primary-fixed text-label-md text-on-primary-fixed">
              {(user?.fullName ?? '?').charAt(0).toUpperCase()}
            </span>
            <Icon name="expand_more" className="hidden text-[18px] text-on-surface-variant sm:block" />
          </button>

          {menuOpen && (
            <div
              role="menu"
              className="absolute right-0 mt-2 w-64 animate-fade-in overflow-hidden rounded-md border border-outline-variant/60 bg-surface-container-lowest shadow-overlay"
            >
              <div className="border-b border-outline-variant/60 px-md py-md">
                <p className="truncate text-body-md font-semibold text-on-surface">{user?.fullName}</p>
                <p className="truncate text-body-sm text-on-surface-variant">{user?.email}</p>
                <div className="mt-sm flex flex-wrap gap-1">
                  {user?.roles.map((role) => (
                    <span
                      key={role}
                      className="rounded-full bg-secondary-container px-2 py-0.5 text-body-sm text-on-secondary-container"
                    >
                      {role.replace('_', ' ')}
                    </span>
                  ))}
                </div>
              </div>

              <Link
                to={ROUTES.profile}
                role="menuitem"
                onClick={() => setMenuOpen(false)}
                className="flex items-center gap-3 px-md py-2.5 text-body-md text-on-surface transition-colors hover:bg-surface-container-low"
              >
                <Icon name="person" className="text-[20px] text-on-surface-variant" />
                My Profile
              </Link>

              <Link
                to={ROUTES.changePassword}
                role="menuitem"
                onClick={() => setMenuOpen(false)}
                className="flex items-center gap-3 px-md py-2.5 text-body-md text-on-surface transition-colors hover:bg-surface-container-low"
              >
                <Icon name="lock_reset" className="text-[20px] text-on-surface-variant" />
                Change Password
              </Link>

              <button
                type="button"
                role="menuitem"
                onClick={() => {
                  setMenuOpen(false);
                  void logout();
                }}
                className="flex w-full items-center gap-3 border-t border-outline-variant/60 px-md py-2.5 text-body-md text-error transition-colors hover:bg-error-container/40"
              >
                <Icon name="logout" className="text-[20px]" />
                Sign out
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
};
