import { useCallback, useEffect, useRef } from 'react';

/**
 * Debounces a save: only the last value scheduled within [delayMs] is saved. A save still pending when the component
 * unmounts is flushed rather than dropped, so leaving the page right after an edit (a drag, then "Open sequence") keeps it.
 * The returned function is stable.
 */
export function useDebouncedSave<T>(save: (value: T) => void, delayMs: number): (value: T) => void {
  const saveRef = useRef(save);
  saveRef.current = save;
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const pending = useRef<{ value: T } | null>(null);

  const flush = useCallback(() => {
    clearTimeout(timer.current);
    const next = pending.current;
    pending.current = null;
    if (next) saveRef.current(next.value);
  }, []);

  useEffect(() => flush, [flush]);

  return useCallback(
    (value: T) => {
      clearTimeout(timer.current);
      pending.current = { value };
      timer.current = setTimeout(flush, delayMs);
    },
    [flush, delayMs],
  );
}
