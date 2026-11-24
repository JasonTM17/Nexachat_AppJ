import { useCallback, useEffect, useState } from 'react';
import {
  api,
  feedReportsPath,
  feedResolvePath,
  feedTargetPath,
  type FeedReport,
  type FeedReportStatus,
  type FeedTargetState,
  type Page,
} from '../api';
import { useI18n, type StringKey } from '../i18n';
import { useLatestRequest } from '../lib/async';
import {
  Chip,
  ConfirmDialog,
  Empty,
  ErrorBlock,
  Loading,
  Pager,
  fmtTime,
} from '../components/ui';

const PAGE_SIZE = 25;
const MAX_REASON = 500;
const STATUSES: FeedReportStatus[] = ['OPEN', 'REVIEWED', 'DISMISSED'];

const STATUS_KEY: Record<FeedReportStatus, StringKey> = {
  OPEN: 'feed.status.open',
  REVIEWED: 'feed.status.reviewed',
  DISMISSED: 'feed.status.dismissed',
};

const STATE_KEY: Record<FeedTargetState, StringKey> = {
  VISIBLE: 'feed.state.visible',
  HIDDEN: 'feed.state.hidden',
  DELETED: 'feed.state.deleted',
};

const STATE_TONE: Record<FeedTargetState, string> = {
  VISIBLE: 'success',
  HIDDEN: 'high',
  DELETED: 'low',
};

export default function FeedPage() {
  const { t } = useI18n();
  const [status, setStatus] = useState<FeedReportStatus>('OPEN');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<FeedReport> | null>(null);
  const [failed, setFailed] = useState(false);
  const [actionFailed, setActionFailed] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [hiding, setHiding] = useState<FeedReport | null>(null);
  // Dismiss / mark-reviewed are irreversible one-click status changes, so
  // they go through the same confirm pattern as the reason-guarded hide.
  const [resolving, setResolving] = useState<{
    report: FeedReport;
    next: 'REVIEWED' | 'DISMISSED';
  } | null>(null);
  const latest = useLatestRequest();

  const load = useCallback(() => {
    // A reload (retry, filter or page change, or a post-action refresh) means
    // the failed-action banner no longer describes the view on screen.
    setActionFailed(false);
    setFailed(false);
    // A new list can reuse report ids from a previous view; drop stale
    // per-report expansion so a row never renders open by accident.
    setExpanded(new Set());
    latest(api<Page<FeedReport>>(feedReportsPath(status, page, PAGE_SIZE)), (res) => {
      if (res) {
        setData(res);
        // A mutation (resolve/hide) can shrink the list so the requested page
        // no longer exists; clamp and let the effect refetch once so the
        // pager never reads "page > total".
        const totalPages = Math.max(1, Math.ceil(res.total / PAGE_SIZE));
        if (page > totalPages - 1) setPage(totalPages - 1);
      } else setFailed(true);
    });
  }, [status, page, latest]);

  useEffect(() => {
    load();
  }, [load]);

  const run = async (report: FeedReport, call: () => Promise<unknown>) => {
    setBusy(report.id);
    setActionFailed(false);
    try {
      await call();
      load();
    } catch {
      setActionFailed(true);
    } finally {
      setBusy(null);
    }
  };

  const hide = (report: FeedReport, reason: string) => {
    setHiding(null);
    void run(report, () =>
      api(feedTargetPath(report.targetType, report.targetId, 'hide'), {
        method: 'POST',
        body: { reason },
      }),
    );
  };

  const unhide = (report: FeedReport) =>
    void run(report, () =>
      api(feedTargetPath(report.targetType, report.targetId, 'unhide'), { method: 'POST' }),
    );

  const resolve = (report: FeedReport, next: 'REVIEWED' | 'DISMISSED') =>
    void run(report, () =>
      api(feedResolvePath(report.id), { method: 'POST', body: { status: next } }),
    );

  const toggle = (id: string) => {
    const next = new Set(expanded);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setExpanded(next);
  };

  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <>
      <div className="filters">
        <label className="filter-field">
          <span>{t('feed.status')}</span>
          <select
            className="select"
            aria-label={t('feed.status')}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as FeedReportStatus);
              setPage(0);
              // Keep the previous list while the refetch is in flight (the
              // stale-until-replaced pattern used by Users/Alerts/Audit)
              // instead of flashing the full loading state.
            }}
          >
            {STATUSES.map((s) => (
              <option key={s} value={s}>{t(STATUS_KEY[s])}</option>
            ))}
          </select>
        </label>
      </div>

      {/* An action failure must not hide the queue underneath it: the banner
          names the failed mutation while the rows stay actionable. A list
          failure keeps the full ErrorBlock (there is nothing to show). */}
      {actionFailed && !failed && (
        <div className="form-error" role="alert">
          {t('common.error')}
        </div>
      )}

      {failed && <ErrorBlock onRetry={load} />}

      {failed ? null : !data ? (
        <Loading />
      ) : data.items.length === 0 ? (
        <Empty
          hint={status !== 'OPEN' ? t('common.empty.filtered') : undefined}
          onClearFilters={
            status !== 'OPEN'
              ? () => {
                  setStatus('OPEN');
                  setPage(0);
                }
              : undefined
          }
        />
      ) : (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col" className="col-lo">{t('feed.col.type')}</th>
                  <th scope="col">{t('feed.col.content')}</th>
                  <th scope="col" className="col-lo">{t('feed.col.reason')}</th>
                  <th scope="col" className="col-lo">{t('feed.col.author')}</th>
                  <th scope="col" className="col-lo">{t('feed.col.state')}</th>
                  <th scope="col" className="col-lo">{t('feed.col.reported')}</th>
                  <th scope="col" className="col-actions">{t('feed.col.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((r) => {
                  const open = expanded.has(r.id);
                  const body = r.messageSnapshot ?? r.targetBody;
                  const text = body ?? '—';
                  const targetState: FeedTargetState = r.targetDeleted
                    ? 'DELETED'
                    : r.targetHidden
                      ? 'HIDDEN'
                      : 'VISIBLE';
                  return (
                    <tr key={r.id}>
                      <td className="col-lo">
                        {t(r.targetType === 'POST' ? 'feed.type.post' : 'feed.type.comment')}
                      </td>
                      <td>
                        <div className="feed-mobile-state">
                          <Chip tone={STATE_TONE[targetState]}>{t(STATE_KEY[targetState])}</Chip>
                        </div>
                        <div
                          className={open ? 'snippet' : 'truncate'}
                          title={open ? undefined : text}
                        >
                          {text}
                        </div>
                        {((open && r.details) || (!body && r.details)) && (
                          <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>
                            {r.details}
                          </div>
                        )}
                        {body && (text.length > 60 || r.details) && (
                          <button
                            className="btn ghost sm"
                            aria-expanded={open}
                            onClick={() => toggle(r.id)}
                          >
                            {t(open ? 'feed.collapse' : 'feed.expand')}
                          </button>
                        )}
                        {/* Low-priority columns are dropped on narrow
                            screens; repeat their data here so phones lose
                            nothing (shown via .feed-mobile-meta). */}
                        <div className="feed-mobile-meta">
                          <div className="muted" style={{ fontSize: 12 }}>
                            {t(r.targetType === 'POST' ? 'feed.type.post' : 'feed.type.comment')} · {r.authorName ?? r.authorId ?? '—'} · {fmtTime(r.createdAt)}
                          </div>
                          <div style={{ marginTop: 6 }}>
                            <Chip>{r.reason}</Chip>
                          </div>
                        </div>
                      </td>
                      <td className="col-lo">
                        <Chip>{r.reason}</Chip>
                      </td>
                      <td className="col-lo">{r.authorName ?? r.authorId ?? '—'}</td>
                      <td className="col-lo">
                        <Chip tone={STATE_TONE[targetState]}>
                          {t(STATE_KEY[targetState])}
                        </Chip>
                      </td>
                      <td className="col-lo">{fmtTime(r.createdAt)}</td>
                      <td className="col-actions">
                        <div className="action-row">
                          {targetState === 'VISIBLE' && (
                            <button
                              className="btn danger sm"
                              disabled={busy === r.id}
                              onClick={() => setHiding(r)}
                            >
                              {t('feed.action.hide')}
                            </button>
                          )}
                          {targetState === 'HIDDEN' && (
                            <button
                              className="btn sm"
                              disabled={busy === r.id}
                              onClick={() => unhide(r)}
                            >
                              {t('feed.action.unhide')}
                            </button>
                          )}
                          {r.status === 'OPEN' && (
                            <>
                              <button
                                className="btn ghost sm"
                                disabled={busy === r.id}
                                onClick={() => setResolving({ report: r, next: 'DISMISSED' })}
                              >
                                {t('feed.action.dismiss')}
                              </button>
                              <button
                                className="btn primary sm"
                                disabled={busy === r.id}
                                onClick={() => setResolving({ report: r, next: 'REVIEWED' })}
                              >
                                {t('feed.action.review')}
                              </button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <Pager page={page} totalPages={totalPages} onPage={setPage} />
        </>
      )}

      {hiding && (
        <ConfirmDialog
          title={t(hiding.targetType === 'POST' ? 'feed.action.hidePost' : 'feed.action.hideComment')}
          requireReason
          confirmDanger
          maxReasonLength={MAX_REASON}
          onConfirm={(reason) => hide(hiding, reason)}
          onCancel={() => setHiding(null)}
        />
      )}

      {resolving && (
        <ConfirmDialog
          title={t(
            resolving.next === 'DISMISSED' ? 'feed.action.dismiss' : 'feed.action.review',
          )}
          requireReason={false}
          confirmDanger={resolving.next === 'DISMISSED'}
          busy={busy === resolving.report.id}
          onConfirm={() => {
            const { report, next } = resolving;
            setResolving(null);
            resolve(report, next);
          }}
          onCancel={() => setResolving(null)}
        />
      )}
    </>
  );
}
