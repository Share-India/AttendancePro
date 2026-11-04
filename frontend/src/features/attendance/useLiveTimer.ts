import { useEffect, useRef, useState } from 'react';

/**
 * A seconds-resolution timer anchored to the server's clock.
 *
 * The server tells us how many minutes have been worked and what time it
 * thinks it is. We record the gap between that and the browser's clock once,
 * then count locally against the corrected time.
 *
 * This matters: reading the browser clock directly would let a device whose
 * time is wrong — or deliberately set forward — display hours the server will
 * never agree with. The number that counts is always the server's; this only
 * animates the seconds between polls.
 */
export const useLiveTimer = (
  startedAt: string | null,
  serverTime: string | null,
  /** Minutes already excluded from the elapsed span, such as completed breaks. */
  excludedMinutes = 0,
  running = true,
): number => {
  const [elapsedSeconds, setElapsedSeconds] = useState(0);

  // Difference between the browser's clock and the server's, measured once per
  // poll rather than assumed to be zero.
  const skewMs = useRef(0);

  useEffect(() => {
    if (serverTime) {
      skewMs.current = Date.now() - new Date(serverTime).getTime();
    }
  }, [serverTime]);

  useEffect(() => {
    if (!startedAt || !running) {
      setElapsedSeconds(0);
      return;
    }

    const compute = () => {
      const correctedNow = Date.now() - skewMs.current;
      const started = new Date(startedAt).getTime();
      const grossSeconds = Math.floor((correctedNow - started) / 1000);
      setElapsedSeconds(Math.max(0, grossSeconds - excludedMinutes * 60));
    };

    compute();
    const interval = window.setInterval(compute, 1000);
    return () => window.clearInterval(interval);
  }, [startedAt, running, excludedMinutes]);

  return elapsedSeconds;
};

/** Formats a second count as HH:MM:SS with fixed width, for a stable display. */
export const formatDuration = (totalSeconds: number): string => {
  const safe = Math.max(0, totalSeconds);
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const seconds = safe % 60;

  const pad = (value: number) => value.toString().padStart(2, '0');
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
};

/** Formats a minute count as "8h 25m", matching the backend's own formatting. */
export const formatMinutes = (minutes: number): string => {
  if (minutes <= 0) return '0m';
  const hours = Math.floor(minutes / 60);
  const remainder = minutes % 60;
  if (hours === 0) return `${remainder}m`;
  return remainder === 0 ? `${hours}h` : `${hours}h ${remainder}m`;
};
