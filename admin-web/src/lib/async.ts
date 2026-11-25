import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

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
// from any but the newest call, plus `cancel()` which invalidates every call
// still in flight (used when a consumer dismisses the surface the response
// would populate, e.g. a closed detail drawer must not reopen itself).
export interface LatestRequest {
  <T>(fn: Promise<T>, apply: (value: T | null, error?: unknown) => void): void;
  cancel: () => void;
}

export function useLatestRequest(): LatestRequest {
  const seqRef = useRef(0);
  // Memoized: consumers put the returned function into useCallback/useEffect
  // dependency lists — a fresh identity each render would refire the effect
  // (and its network call) on every render.
  const latest = useCallback(
    <T,>(fn: Promise<T>, apply: (value: T | null, error?: unknown) => void) => {
      const seq = ++seqRef.current;
      void fn.then(
        (value) => {
          if (seq === seqRef.current) apply(value);
        },
        (error) => {
          // Keep the rejection reason available instead of collapsing every
          // failure to an untyped null; consumers can render status/code-aware
          // copy while the null value still signals "no fresh data".
          if (seq === seqRef.current) apply(null, error);
        },
      );
    },
    [],
  );
  const cancel = useCallback(() => {
    seqRef.current += 1;
  }, []);
  // Same object identity every render (both callbacks are stable), so
  // attaching `cancel` cannot destabilize dependency lists.
  return useMemo(() => Object.assign(latest, { cancel }), [latest, cancel]);
}

// Fixed-cadence polling for live queues: ticks are skipped while the tab is
// hidden, and returning to the tab refreshes immediately. The caller remains
// responsible for the initial load and for serializing overlapping responses
// (normally with useLatestRequest above).
export function useVisiblePolling(load: () => void, intervalMs: number) {
  useEffect(() => {
    const id = setInterval(() => {
      if (document.visibilityState === 'visible') load();
    }, intervalMs);
    const onVisibility = () => {
      if (document.visibilityState === 'visible') load();
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      clearInterval(id);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [load, intervalMs]);
}
