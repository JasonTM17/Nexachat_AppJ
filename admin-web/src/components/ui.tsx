import { useCallback, useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import { ApiException } from '../api';
import { useI18n } from '../i18n';
import { timeLocale } from '../lib/locale';

export function Loading() {
  const { t } = useI18n();
  return <div className="loading" role="status">{t('common.loading')}</div>;
}

export function Empty({
  title,
  hint,
  onClearFilters,
}: {
  title?: string;
  hint?: string;
  onClearFilters?: () => void;
}) {
  const { t } = useI18n();
  return (
    <div className="empty">
      <div className="state-icon" aria-hidden="true">◌</div>
      <p className="state-title">{title ?? t('common.empty')}</p>
      <p className="state-hint">{hint ?? t('common.empty.hint')}</p>
      {onClearFilters && (
        <button className="btn ghost" onClick={onClearFilters}>
          {t('common.filters.clear')}
        </button>
      )}
    </div>
  );
}

// Concise, status/code-aware copy for API failures. Server detail text is
// deliberately not echoed verbatim: it can be technical, localized differently,
// or too long for an inline moderation banner.
export function useApiErrorMessage() {
  const { t } = useI18n();
  return useCallback(
    (error: unknown) => {
      if (!(error instanceof ApiException)) return t('common.error.network');
      if (error.code === 'TIMEOUT') return t('common.error.timeout');
      const detail = error.code && error.code !== 'UNKNOWN' ? ` (${error.code})` : '';
      if (error.status === 0) return t('common.error.network');
      if (error.status === 400) return t('common.error.badRequest', { detail });
      if (error.status === 401) return t('common.error.unauthorized');
      if (error.status === 403) return t('common.error.forbidden');
      if (error.status === 404) return t('common.error.notFound');
      if (error.status === 409) return t('common.error.conflict');
      if (error.status === 429) return t('common.error.rateLimited');
      if (error.status >= 500) return t('common.error.server', { status: error.status });
      return t('common.error.status', { status: error.status, detail });
    },
    [t],
  );
}

export function ErrorBlock({
  onRetry,
  hint,
  error,
}: {
  onRetry?: () => void;
  hint?: string;
  error?: unknown;
}) {
  const { t } = useI18n();
  const formatError = useApiErrorMessage();
  return (
    <div className="error-block" role="alert">
      <div className="state-icon" aria-hidden="true">!</div>
      <p className="state-title">{t('common.error')}</p>
      <p className="state-hint">
        {error ? formatError(error) : (hint ?? t('common.error.hint'))}
      </p>
      {onRetry && (
        <button className="btn ghost" onClick={onRetry}>
          {t('common.retry')}
        </button>
      )}
    </div>
  );
}

export function Chip({
  tone,
  children,
  label,
}: {
  tone?: string;
  children: ReactNode;
  label?: string;
}) {
  return (
    <span
      className={`chip${tone ? ` ${tone}` : ''}`}
      title={label}
      // aria-label is only valid with a role; role="img" makes the label the
      // accessible name instead of the bare chip text (e.g. "+9").
      {...(label ? { role: 'img', 'aria-label': label } : {})}
    >
      {children}
    </span>
  );
}

// Layers register here on mount so both Escape and the Tab focus trap are
// owned by only the topmost layer (e.g. a ConfirmDialog over a Drawer must not
// dismiss the Drawer or pull focus back into it).
const modalStack: { close: () => void }[] = [];

const FOCUSABLE =
  'a[href],button:not([disabled]),textarea,input,select,[tabindex]:not([tabindex="-1"])';

function isVisible(element: HTMLElement): boolean {
  return element.offsetParent !== null || element.getClientRects().length > 0;
}

function useModalLayer(
  open: boolean,
  close: () => void,
  ref: RefObject<HTMLElement | null>,
) {
  // Callers pass inline closures, so `close` changes identity every render — keep
  // it in a ref and register once per open-cycle, or a re-render would re-push
  // the layer on top of a dialog stacked above it.
  const closeRef = useRef(close);
  closeRef.current = close;
  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    const layerElement = ref.current;
    const layer = { close: () => closeRef.current() };
    modalStack.push(layer);
    const isTop = () => modalStack[modalStack.length - 1] === layer;
    const onKey = (e: KeyboardEvent) => {
      if (!isTop()) return;
      if (e.key === 'Escape') {
        e.stopPropagation();
        // Do NOT pop before close(): if close() is a guarded no-op (e.g. busy
        // dialog), the layer must stay on top or the next Escape reaches the
        // layer underneath while this one is still rendered. The unmount
        // cleanup below removes the layer once close() actually fires.
        layer.close();
        return;
      }
      if (e.key !== 'Tab') return;
      const el = ref.current;
      if (!el) return;
      const items = Array.from(el.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(isVisible);
      if (items.length === 0) {
        // A busy dialog can have every control disabled. Even then Tab must
        // not fall through to the underlying drawer/page.
        e.preventDefault();
        el.focus();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      if (!el.contains(document.activeElement)) {
        e.preventDefault();
        first.focus();
      } else if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKey, true);
    return () => {
      document.removeEventListener('keydown', onKey, true);
      const i = modalStack.indexOf(layer);
      if (i !== -1) modalStack.splice(i, 1);
      // Hand focus back to the opener only when focus is still inside the
      // closing layer; otherwise a programmatic focus move elsewhere wins.
      if (layerElement?.contains(document.activeElement)) previous?.focus?.();
    };
  }, [open, ref]);
}

export function Drawer({
  title,
  sub,
  onClose,
  children,
}: {
  title: string;
  sub?: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const { t } = useI18n();
  const ref = useRef<HTMLDivElement>(null);
  useModalLayer(true, onClose, ref);
  useEffect(() => {
    ref.current?.focus();
  }, []);
  return (
    <>
      <div className="drawer-backdrop" onClick={onClose} />
      <div
        className="drawer"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        tabIndex={-1}
        ref={ref}
      >
        <div className="drawer-head">
          <h2>{title}</h2>
          <button
            className="btn ghost sm drawer-close"
            onClick={onClose}
            aria-label={t('common.close')}
          >
            ✕
          </button>
        </div>
        {sub && <div className="sub">{sub}</div>}
        {children}
      </div>
    </>
  );
}

export function ConfirmDialog({
  title,
  requireReason = false,
  allowReason = false,
  onConfirm,
  onCancel,
  confirmDanger,
  maxReasonLength,
  busy,
  children,
}: {
  title: string;
  requireReason?: boolean;
  // Renders the reason textarea without making it mandatory (for reversible
  // actions whose audit reason is useful but optional).
  allowReason?: boolean;
  maxReasonLength?: number;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
  confirmDanger?: boolean;
  busy?: boolean;
  children?: ReactNode;
}) {
  const { t } = useI18n();
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const [reasonError, setReasonError] = useState(false);
  const busyRef = useRef(busy);
  busyRef.current = busy;
  const submit = () => {
    if (busyRef.current) return;
    const reason = reasonRef.current?.value.trim() ?? '';
    if (requireReason && !reason) {
      setReasonError(true);
      reasonRef.current?.focus();
      return;
    }
    onConfirm(reason);
  };
  useEffect(() => {
    // With a reason textarea the field takes focus; without one there is no
    // field at all, so land focus on the dialog container (mirroring the
    // Drawer) — otherwise focus never enters the dialog.
    if (reasonRef.current) reasonRef.current.focus();
    else dialogRef.current?.focus();
  }, []);
  useModalLayer(
    true,
    () => {
      if (!busyRef.current) onCancel();
    },
    dialogRef,
  );
  return (
    <div className="dialog-backdrop">
      <div
        className="dialog"
        role="alertdialog"
        aria-modal="true"
        aria-label={title}
        aria-busy={busy || undefined}
        tabIndex={-1}
        ref={dialogRef}
      >
        <h3>{title}</h3>
        {children}
        {(requireReason || allowReason) && (
          <div className="field">
            <label htmlFor="confirm-reason">
              {t('common.reason')}{requireReason ? ' *' : ''}
            </label>
            <textarea
              id="confirm-reason"
              className="textarea"
              rows={2}
              ref={reasonRef}
              maxLength={maxReasonLength}
              autoFocus
              required={requireReason}
              disabled={busy}
              aria-invalid={reasonError || undefined}
              aria-describedby={reasonError ? 'confirm-reason-error' : undefined}
              onInput={() => reasonError && setReasonError(false)}
            />
            {reasonError && (
              <div className="form-error" id="confirm-reason-error" role="alert">
                {t('common.reason.required')}
              </div>
            )}
          </div>
        )}
        <div className="dialog-actions">
          <button className="btn ghost" onClick={onCancel} disabled={busy}>
            {t('common.cancel')}
          </button>
          <button
            className={`btn ${confirmDanger ? 'danger' : 'primary'}`}
            onClick={submit}
            disabled={busy}
          >
            {t('common.confirm')}
          </button>
        </div>
      </div>
    </div>
  );
}

// Small reusable mutation acknowledgement: visible, transient, and polite for
// screen readers without taking focus away from the operator's current task.
export function useToast(durationMs = 4_000) {
  const [message, setMessage] = useState<string | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const show = useCallback(
    (next: string) => {
      if (timerRef.current) clearTimeout(timerRef.current);
      setMessage(next);
      timerRef.current = setTimeout(() => setMessage(null), durationMs);
    },
    [durationMs],
  );
  useEffect(
    () => () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    },
    [],
  );
  return { message, show };
}

export function Toast({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <div className="toast" role="status">
      {message}
    </div>
  );
}

// Standard live-queue header used by polling pages: an explicit refresh
// affordance, last-success clock, and a stale marker that never discards the
// last good data underneath it.
export function RefreshControl({
  updatedAt,
  onRefresh,
  refreshing = false,
  stale = false,
}: {
  updatedAt: Date | null;
  onRefresh: () => void;
  refreshing?: boolean;
  stale?: boolean;
}) {
  const { t } = useI18n();
  return (
    <div className="page-head">
      <div className="page-head-meta">
        {updatedAt && <span>{t('common.updatedAt', { time: fmtClock(updatedAt) })}</span>}
        {stale && <span role="alert"><Chip tone="high">{t('common.stale')}</Chip></span>}
      </div>
      <button className="btn ghost sm" onClick={onRefresh} disabled={refreshing}>
        {t('common.refresh')}
      </button>
    </div>
  );
}

export function Pager({
  page,
  totalPages,
  onPage,
}: {
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}) {
  const { t } = useI18n();
  if (totalPages <= 1) return null;
  return (
    <nav className="pager" aria-label={t('common.pagination')}>
      <button
        className="btn ghost sm"
        disabled={page <= 0}
        onClick={() => onPage(page - 1)}
      >
        {t('common.page.prev')}
      </button>
      <span>
        {page + 1} / {totalPages}
      </span>
      <button
        className="btn ghost sm"
        disabled={page >= totalPages - 1}
        onClick={() => onPage(page + 1)}
      >
        {t('common.page.next')}
      </button>
    </nav>
  );
}

// Locale-aware grouping for KPI numbers (1,234 / 1.234 depending on lang).
export function fmtNum(value: number): string {
  return value.toLocaleString(timeLocale());
}

export function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  // Moderation actions are audited across year and timezone boundaries; the
  // short old stamp ("Nov 17, 08:00") was ambiguous in both dimensions.
  return d.toLocaleString(timeLocale(), {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZoneName: 'short',
  });
}

// Clock-only formatter on the app-synced display locale, so stamps never
// mix browser-locale output with table times in the same screen.
export function fmtClock(d: Date): string {
  return d.toLocaleTimeString(timeLocale(), {
    hour: '2-digit',
    minute: '2-digit',
  });
}

// Truncate a serialized JSON string at a token boundary (never mid-token)
// and append an ellipsis; callers expose the full value via `title`.
export function truncateJson(value: string, max = 80): string {
  if (value.length <= max) return value;
  const head = value.slice(0, max);
  const cut = Math.max(head.lastIndexOf(','), head.lastIndexOf('{'), head.lastIndexOf('['));
  const base = cut > 8 ? head.slice(0, cut) : head;
  return `${base} …`;
}

export function fmtBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = bytes;
  let i = -1;
  do {
    v /= 1024;
    i++;
  } while (v >= 1024 && i < units.length - 1);
  return `${v.toFixed(1)} ${units[i]}`;
}
