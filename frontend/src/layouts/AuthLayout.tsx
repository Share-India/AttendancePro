import type { ReactNode } from 'react';
import { AppLogo } from '../components/common/AppLogo';

interface AuthLayoutProps {
  title: string;
  subtitle: string;
  children: ReactNode;
}

/**
 * Split public layout from the corporate_login mockup: brand panel on the left,
 * form on the right. The panel is hidden below md, where a compact brand bar
 * takes its place so the form gets the whole viewport.
 */
export const AuthLayout = ({ title, subtitle, children }: AuthLayoutProps) => (
  <div className="flex min-h-screen flex-col antialiased md:flex-row">
    {/* Brand panel */}
    <aside className="relative hidden h-screen overflow-hidden bg-primary md:flex md:w-1/2 lg:w-3/5">
      <div
        className="absolute inset-0 opacity-30"
        style={{
          backgroundImage:
            'radial-gradient(circle at 20% 20%, rgba(182,196,255,0.35) 0, transparent 45%),' +
            'radial-gradient(circle at 80% 70%, rgba(78,222,163,0.22) 0, transparent 40%)',
        }}
        aria-hidden="true"
      />
      {/* Faint grid: the plan-view language the geofence screens use elsewhere. */}
      <div
        className="absolute inset-0 opacity-[0.07]"
        style={{
          backgroundImage:
            'linear-gradient(to right, #fff 1px, transparent 1px), linear-gradient(to bottom, #fff 1px, transparent 1px)',
          backgroundSize: '48px 48px',
        }}
        aria-hidden="true"
      />

      <div className="relative z-10 flex h-full w-full flex-col justify-between p-margin-desktop">
        <AppLogo tone="light" size={40} labelClassName="text-headline-md text-white" />

        {/*
          The corporate logotype is navy and red on white, so it is placed on
          a white card rather than dropped straight onto this panel, where the
          navy half of it would all but disappear.

          flex-1 hands this row whatever vertical space the header, headline
          and stats leave over, so the card centres in the panel's open middle
          instead of being pushed to one end of it by justify-between.
        */}
        <div className="flex flex-1 items-center justify-center py-lg">
          <div className="rounded-lg bg-white p-lg shadow-raised">
            <img
              src="/brand/shareindia-logo.png"
              alt="Share India"
              className="h-32 w-auto select-none lg:h-40"
            />
          </div>
        </div>

        <div className="mx-auto mb-xl max-w-lg text-center">
          <h1 className="mb-sm text-display-lg text-white">Empowering your workforce.</h1>
          <p className="text-body-lg text-primary-fixed">
            Secure, seamless access to your enterprise HR portal. Streamline your workday with our
            intuitive management tools.
          </p>
        </div>

        <dl className="grid grid-cols-3 gap-md border-t border-white/15 pt-lg text-center">
          {[
            { label: 'Geofenced', value: 'Punch' },
            { label: 'Shift-aware', value: 'Hours' },
            { label: 'Multi-level', value: 'Approvals' },
          ].map((item) => (
            <div key={item.label}>
              <dt className="text-body-sm text-primary-fixed/70">{item.label}</dt>
              <dd className="text-headline-md text-white">{item.value}</dd>
            </div>
          ))}
        </dl>
      </div>
    </aside>

    {/* Form panel */}
    <main className="relative flex h-screen w-full flex-col overflow-y-auto bg-surface md:z-20 md:w-1/2 md:shadow-panel lg:w-2/5">
      <div className="flex items-center justify-center gap-sm bg-primary p-lg text-white shadow-sm md:hidden">
        <AppLogo tone="light" size={28} labelClassName="text-headline-md" />
      </div>

      {/*
        The corporate logotype, for the phone layout only — on wider screens it
        is in the brand panel, which is hidden here. Without it a phone shows
        the product mark and nothing saying whose portal this is.

        On a white card like the panel's, though for a different reason: the
        file is not transparent, so against this off-white surface it otherwise
        shows as a white rectangle with hard edges. The card makes that shape
        deliberate instead of looking like a rendering fault.
      */}
      <div className="flex justify-center px-margin-mobile pt-lg md:hidden">
        <div className="rounded-lg bg-white px-lg py-md shadow-raised">
          <img
            src="/brand/shareindia-logo.png"
            alt="Share India"
            className="h-16 w-auto select-none"
          />
        </div>
      </div>

      <div className="mx-auto flex w-full max-w-[480px] flex-1 flex-col justify-center px-margin-mobile py-xl md:px-margin-desktop">
        <header className="mb-lg text-center md:text-left">
          <h2 className="mb-xs text-headline-lg-mobile text-on-surface md:text-headline-lg">{title}</h2>
          <p className="text-body-md text-on-surface-variant">{subtitle}</p>
        </header>

        {children}
      </div>

      <footer className="mt-auto flex flex-col items-center justify-center gap-md border-t border-surface-variant p-lg text-center md:flex-row">
        <a href="mailto:support@company.com" className="text-body-sm text-on-surface-variant transition-colors hover:text-primary">
          Contact Support
        </a>
        <span className="hidden text-[12px] text-surface-variant md:block">•</span>
        <a href="/privacy" className="text-body-sm text-on-surface-variant transition-colors hover:text-primary">
          Privacy Policy
        </a>
        <span className="hidden text-[12px] text-surface-variant md:block">•</span>
        <span className="text-body-sm text-outline">© {new Date().getFullYear()} Attendance Pro</span>
      </footer>
    </main>
  </div>
);
