import { cn } from '../../utils/cn';

type Tone = 'success' | 'warning' | 'error' | 'neutral' | 'info';

const TONES: Record<Tone, string> = {
  success: 'bg-success-container text-on-success-container',
  warning: 'bg-warning-container text-on-warning-container',
  error: 'bg-error-container text-on-error-container',
  info: 'bg-secondary-container text-on-secondary-fixed',
  neutral: 'bg-surface-container-high text-on-surface-variant',
};

const DOTS: Record<Tone, string> = {
  success: 'bg-success',
  warning: 'bg-warning',
  error: 'bg-error',
  info: 'bg-primary',
  neutral: 'bg-outline',
};

/**
 * A leading dot carries the same signal as the colour, so the state is still
 * readable without colour vision.
 */
export const StatusChip = ({
  label,
  tone = 'neutral',
  className,
}: {
  label: string;
  tone?: Tone;
  className?: string;
}) => (
  <span
    className={cn(
      'inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-body-sm font-medium whitespace-nowrap',
      TONES[tone],
      className,
    )}
  >
    <span className={cn('h-1.5 w-1.5 rounded-full', DOTS[tone])} aria-hidden="true" />
    {label}
  </span>
);

export const employmentStatusTone = (status: string): Tone => {
  switch (status) {
    case 'ACTIVE':
      return 'success';
    case 'ON_NOTICE':
      return 'warning';
    case 'SUSPENDED':
      return 'error';
    case 'EXITED':
      return 'neutral';
    default:
      return 'neutral';
  }
};
