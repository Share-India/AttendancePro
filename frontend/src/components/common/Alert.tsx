import type { ReactNode } from 'react';
import { cn } from '../../utils/cn';
import { Icon } from './Icon';

type Variant = 'error' | 'success' | 'warning' | 'info';

const VARIANTS: Record<Variant, { wrapper: string; icon: string; glyph: string }> = {
  error: {
    wrapper: 'bg-error-container border-error/30 text-on-error-container',
    icon: 'text-error',
    glyph: 'error',
  },
  success: {
    wrapper: 'bg-success-container border-success/30 text-on-success-container',
    icon: 'text-success',
    glyph: 'check_circle',
  },
  warning: {
    wrapper: 'bg-warning-container border-warning/30 text-on-warning-container',
    icon: 'text-warning',
    glyph: 'warning',
  },
  info: {
    wrapper: 'bg-primary-fixed border-primary/20 text-on-primary-fixed',
    icon: 'text-primary',
    glyph: 'info',
  },
};

interface AlertProps {
  variant?: Variant;
  title?: string;
  children: ReactNode;
  className?: string;
}

export const Alert = ({ variant = 'info', title, children, className }: AlertProps) => {
  const styles = VARIANTS[variant];
  return (
    <div
      role={variant === 'error' ? 'alert' : 'status'}
      className={cn('flex gap-sm rounded-DEFAULT border p-md animate-fade-in', styles.wrapper, className)}
    >
      <Icon name={styles.glyph} className={cn('text-[20px] shrink-0 mt-px', styles.icon)} />
      <div className="min-w-0">
        {title && <p className="text-label-md mb-unit">{title}</p>}
        <p className="text-body-md">{children}</p>
      </div>
    </div>
  );
};
