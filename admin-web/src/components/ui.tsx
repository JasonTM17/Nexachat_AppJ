import { useEffect, useRef, type ReactNode } from 'react';
import { useI18n } from '../i18n';

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
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    ref.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);
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
        <h2>{title}</h2>
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
  children,
}: {
  title: string;
  requireReason?: boolean;
  maxReasonLength?: number;
  onConfirm: (reason: string) => void;
  onCancel: () => void;
  confirmDanger?: boolean;
  children?: ReactNode;
}) {
  const { t } = useI18n();
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const submit = () => {
    const reason = reasonRef.current?.value.trim() ?? '';
    if (requireReason && !reason) {
      reasonRef.current?.focus();
      return;
    }
    onConfirm(reason);
  };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onCancel]);
  return (
    <div className="dialog-backdrop">
      <div className="dialog" role="alertdialog" aria-modal="true" aria-label={title}>
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
            />
          </div>
        )}
        <div className="dialog-actions">
          <button className="btn ghost" onClick={onCancel}>
            {t('common.cancel')}
          </button>
          <button
            className={`btn ${confirmDanger ? 'danger' : 'primary'}`}
            onClick={submit}
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
    <nav className="pager" aria-label="Pagination">
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
  return d.toLocaleString(undefined, {
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
