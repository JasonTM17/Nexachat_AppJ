import { useCallback, useEffect, useRef, useState } from 'react';

// Debounce a fast-changing value (e.g. a search box) so consumers only react
// once typing settles. The timer resets on every change; `delayMs` of quiet
// passes before the debounced value updates.
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(id);
  }, [value, delayMs]);
  return debounced;
}

// Guard against out-of-order responses: each call gets a sequence number and
// only the latest one may write state. Returns a setter that ignores updates
// from any but the newest call.
export function useLatestRequest(): <T>(fn: Promise<T>, apply: (value: T) => void) => void {
  const seqRef = useRef(0);
  // Memoized: consumers put the returned function into useCallback/useEffect
  // dependency lists — a fresh identity each render would refire the effect
  // (and its network call) on every render.
  return useCallback(<T,>(fn: Promise<T>, apply: (value: T) => void) => {
    const seq = ++seqRef.current;
    void fn.then(
      (value) => {
        if (seq === seqRef.current) apply(value);
      },
      () => {
        if (seq === seqRef.current) apply(null as T);
      },
    );
  }, []);
}
