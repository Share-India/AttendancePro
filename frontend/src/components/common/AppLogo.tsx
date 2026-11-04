import { cn } from '../../utils/cn';

/**
 * Colours per surface. The mark is drawn, not imported, so it can switch
 * palette without a second asset and stay sharp at any size.
 *
 * <p>Both pairs come from the theme: the ring is the primary navy or plain
 * white, and the tick is the green the auth panel's own gradient already
 * uses. The brighter mint would wash out on white, and the deeper green
 * would disappear into the navy ring, so each surface gets the one that
 * separates from its ring.
 */
const TONES = {
  /** For dark surfaces — the auth panel, the mobile brand bar. */
  light: { ring: '#ffffff', tick: '#4edea3' },
  /** For light surfaces — the sidebar, the privacy notice. */
  dark: { ring: '#00236f', tick: '#27c38a' },
} as const;

interface AppLogoProps {
  tone?: keyof typeof TONES;
  /** Edge length of the mark in px. The wordmark is sized by the caller. */
  size?: number;
  /** Drop the wordmark and render the mark alone. */
  markOnly?: boolean;
  /** Classes for the wordmark, so each surface keeps its own type scale. */
  labelClassName?: string;
  className?: string;
}

/**
 * The Attendance Pro logo: a ring broken at the top right, with a tick
 * leaving through the gap.
 *
 * <p>The ring is the working day and the tick is the punch that closes it —
 * which is the whole product in one shape. Two strokes is deliberate: this
 * also renders at 16px in a browser tab, where anything more becomes mud.
 *
 * <p>This is the product's own identity, not Share India's corporate mark —
 * that one appears as the full logotype on the auth panel, endorsing the
 * product rather than standing in for it.
 */
export const AppLogo = ({
  tone = 'dark',
  size = 32,
  markOnly = false,
  labelClassName,
  className,
}: AppLogoProps) => {
  const { ring, tick } = TONES[tone];

  return (
    <span className={cn('flex items-center gap-sm', className)}>
      <svg
        viewBox="0 0 48 48"
        width={size}
        height={size}
        className="shrink-0"
        style={{ width: size, height: size }}
        role="img"
        aria-label={markOnly ? 'Attendance Pro' : undefined}
        aria-hidden={markOnly ? undefined : true}
      >
        {/* 300° arc, leaving a gap centred on the upper right. */}
        <path
          d="M41.39 19.34A18 18 0 1 1 28.66 6.61"
          fill="none"
          stroke={ring}
          strokeWidth="5"
          strokeLinecap="round"
        />
        {/* The long arm ends mid-gap, so the tick reads as passing through
            the ring rather than being boxed inside it. */}
        <path
          d="M14.5 24.5 21 31 38.5 9.5"
          fill="none"
          stroke={tick}
          strokeWidth="5"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
      {!markOnly && <span className={labelClassName}>Attendance Pro</span>}
    </span>
  );
};
