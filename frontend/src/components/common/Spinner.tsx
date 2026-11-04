import { cn } from '../../utils/cn';

export const Spinner = ({ className }: { className?: string }) => (
  <svg
    className={cn('animate-spin', className)}
    viewBox="0 0 24 24"
    fill="none"
    aria-hidden="true"
  >
    <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="3" />
    <path
      className="opacity-90"
      fill="currentColor"
      d="M4 12a8 8 0 0 1 8-8v3a5 5 0 0 0-5 5H4z"
    />
  </svg>
);

export const FullPageLoader = ({ message = 'Loading' }: { message?: string }) => (
  <div className="flex min-h-screen flex-col items-center justify-center gap-md bg-background">
    <Spinner className="h-8 w-8 text-primary" />
    <p className="text-body-md text-on-surface-variant">{message}</p>
  </div>
);
