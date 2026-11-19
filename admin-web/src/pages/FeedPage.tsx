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

  const load = useCallback(() => {
    setFailed(false);
    // A new list can reuse report ids from a previous view; drop stale
    // per-report expansion so a row never renders open by accident.
    setExpanded(new Set());
    api<Page<FeedReport>>(feedReportsPath(status, page, PAGE_SIZE))
      .then(setData)
      .catch(() => setFailed(true));
  }, [status, page]);

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
              setData(null);
            }}
          >
            {STATUSES.map((s) => (
              <option key={s} value={s}>{t(STATUS_KEY[s])}</option>
            ))}
          </select>
        </label>
      </div>

      {actionFailed && <ErrorBlock />}

      {failed ? (
        <ErrorBlock onRetry={load} />
      ) : !data ? (
        <Loading />
      ) : data.items.length === 0 ? (
        <Empty
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
                        <div className={open ? 'snippet' : 'truncate'}>{text}</div>
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
                                onClick={() => resolve(r, 'DISMISSED')}
                              >
                                {t('feed.action.dismiss')}
                              </button>
                              <button
                                className="btn primary sm"
                                disabled={busy === r.id}
                                onClick={() => resolve(r, 'REVIEWED')}
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
    </>
  );
}
