import { useEffect, useState } from 'react';

/** Delays propagating a fast-changing value, so typing does not fire a request per keystroke. */
export const useDebounce = <T,>(value: T, delayMs = 300): T => {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(timer);
  }, [value, delayMs]);

  return debounced;
};
