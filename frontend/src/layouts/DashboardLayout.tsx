import { useState } from 'react';
import { Outlet } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { Topbar } from './Topbar';

export const DashboardLayout = () => {
  const [sidebarOpen, setSidebarOpen] = useState(false);

  return (
    <div className="flex min-h-screen bg-background">
      <Sidebar open={sidebarOpen} onNavigate={() => setSidebarOpen(false)} />

      {/* Scrim closes the drawer on small screens. */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 z-30 bg-inverse-surface/40 lg:hidden"
          onClick={() => setSidebarOpen(false)}
          aria-hidden="true"
        />
      )}

      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar onToggleSidebar={() => setSidebarOpen((open) => !open)} />
        {/*
          pad-safe-bottom keeps the last row clear of the iPhone home
          indicator, and pad-safe-x of the rounded corners in landscape.
          Both are zero everywhere else.
        */}
        <main className="pad-safe-bottom pad-safe-x flex-1 px-margin-mobile py-lg md:px-margin-desktop">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
