import { cn } from '../../utils/cn';

interface IconProps {
  name: string;
  className?: string;
  filled?: boolean;
  /** Decorative by default; pass a label when the icon is the only content. */
  label?: string;
}

/** Material Symbols, loaded once in index.html. */
export const Icon = ({ name, className, filled = false, label }: IconProps) => (
  <span
    className={cn('material-symbols-outlined leading-none', className)}
    style={filled ? { fontVariationSettings: "'FILL' 1" } : undefined}
    aria-hidden={label ? undefined : true}
    aria-label={label}
    role={label ? 'img' : undefined}
  >
    {name}
  </span>
);
