import { useEffect, useRef, useState, type ReactNode, type RefObject } from 'react';
import { useI18n } from '../i18n';
import { timeLocale } from '../lib/locale';

export function Loading() {
  const { t } = useI18n();
  return <div className="loading" role="status">{t('common.loading')}</div>;
}

export function Empty({
  hint,
  onClearFilters,
}: {
  hint?: string;
  onClearFilters?: () => void;
}) {
  const { t } = useI18n();
  return (
    <div className="empty">
      <div className="state-icon" aria-hidden="true">◌</div>
      <p className="state-title">{t('common.empty')}</p>
      <p className="state-hint">{hint ?? t('common.empty.hint')}</p>
      {onClearFilters && (
        <button className="btn ghost" onClick={onClearFilters}>
          {t('common.filters.clear')}
        </button>
      )}
    </div>
  );
}

export function ErrorBlock({
  onRetry,
  hint,
}: {
  onRetry?: () => void;
  hint?: string;
}) {
  const { t } = useI18n();
  return (
    <div className="error-block" role="alert">
      <div className="state-icon" aria-hidden="true">!</div>
      <p className="state-title">{t('common.error')}</p>
      <p className="state-hint">{hint ?? t('common.error.hint')}</p>
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
    <span className={`chip${tone ? ` ${tone}` : ''}`} title={label} aria-label={label}>
      {children}
    </span>
  );
}

// Layers register here on mount so the document-level Escape handler can close
// only the topmost one (e.g. a ConfirmDialog over a Drawer must not dismiss both).
const modalStack: { close: () => void }[] = [];

function useEscapeToClose(open: boolean, close: () => void) {
  // Callers pass inline closures, so `close` changes identity every render — keep
  // it in a ref and register once per open-cycle, or a re-render would re-push
  // the layer on top of a dialog stacked above it.
  const closeRef = useRef(close);
  closeRef.current = close;
  useEffect(() => {
    if (!open) return;
    const layer = { close: () => closeRef.current() };
    modalStack.push(layer);
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      const top = modalStack[modalStack.length - 1];
      if (top !== layer) return;
      e.stopPropagation();
      // Do NOT pop before close(): if close() is a guarded no-op (e.g. busy
      // dialog), the layer must stay on top or the next Escape reaches the
      // layer underneath while this one is still rendered. The unmount
      // cleanup below removes the layer once close() actually fires.
      layer.close();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      const i = modalStack.indexOf(layer);
      if (i !== -1) modalStack.splice(i, 1);
    };
  }, [open]);
}

const FOCUSABLE =
  'a[href],button:not([disabled]),textarea,input,select,[tabindex]:not([tabindex="-1"])';

// Keep Tab cycling inside the layer and hand focus back to whatever opened it.
function useModalFocus(ref: RefObject<HTMLElement | null>) {
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const previous = document.activeElement as HTMLElement | null;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Tab') return;
      const items = Array.from(el.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
        (n) => n.offsetParent !== null,
      );
      if (items.length === 0) return;
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
      previous?.focus?.();
    };
  }, [ref]);
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
  useEscapeToClose(true, onClose);
  useModalFocus(ref);
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
  requireReason,
  onConfirm,
  onCancel,
  confirmDanger,
  maxReasonLength,
  busy,
  children,
}: {
  title: string;
  requireReason?: boolean;
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
    reasonRef.current?.focus();
  }, []);
  useModalFocus(dialogRef);
  useEscapeToClose(true, () => {
    if (!busyRef.current) onCancel();
  });
  return (
    <div className="dialog-backdrop">
      <div
        className="dialog"
        role="alertdialog"
        aria-modal="true"
        aria-label={title}
        aria-busy={busy || undefined}
        ref={dialogRef}
      >
        <h3>{title}</h3>
        {children}
        {requireReason !== false && (
          <div className="field">
            <label htmlFor="confirm-reason">{t('common.reason')} *</label>
            <textarea
              id="confirm-reason"
              className="textarea"
              rows={2}
              ref={reasonRef}
              maxLength={maxReasonLength}
              autoFocus
              required
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

export function fmtTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  return d.toLocaleString(timeLocale(), {
    month: 'short',
    day: 'numeric',
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
