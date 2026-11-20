import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  api,
  type AlertDetail,
  type AlertSummary,
  type Page,
  type RevealedMessage,
} from '../api';
import { useI18n, type StringKey } from '../i18n';
import { useSession } from '../auth';
import { useLatestRequest } from '../lib/async';
import {
  Chip,
  ConfirmDialog,
  Drawer,
  Empty,
  ErrorBlock,
  Loading,
  Pager,
  fmtTime,
} from '../components/ui';

const PAGE_SIZE = 25;
const SEVERITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const CATEGORIES = [
  'SELF_HARM',
  'THREAT_VIOLENCE',
  'SCAM_PHISHING',
  'CREDENTIAL_SHARING',
  'ID_NUMBER',
  'MINOR_SOLICITATION',
  'DOXXING',
  'CUSTOM',
];
const STATUSES = ['OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'DISMISSED'];

type Pending =
  | { kind: 'status'; status: string }
  | { kind: 'action'; action: string }
  | { kind: 'reveal' }
  | { kind: 'bulk' };

function statusKey(status: string): StringKey {
  switch (status) {
    case 'ACKNOWLEDGED':
      return 'alerts.status.ack';
    case 'RESOLVED':
      return 'alerts.status.resolved';
    case 'DISMISSED':
      return 'alerts.status.dismissed';
    default:
      return 'alerts.status.open';
  }
}

export default function AlertsPage() {
  const { t } = useI18n();
  const { me } = useSession();
  const [status, setStatus] = useState('OPEN');
  const [severity, setSeverity] = useState('');
  const [category, setCategory] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<AlertSummary> | null>(null);
  const [failed, setFailed] = useState(false);
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [detail, setDetail] = useState<AlertDetail | null>(null);
  const [pending, setPending] = useState<Pending | null>(null);
  const [revealed, setRevealed] = useState<RevealedMessage[] | null>(null);
  const [noteText, setNoteText] = useState('');
  const [actionError, setActionError] = useState(false);
  const [busy, setBusy] = useState(false);
  const [noteBusy, setNoteBusy] = useState(false);
  const [assignBusy, setAssignBusy] = useState(false);
  const [detailBusy, setDetailBusy] = useState<string | null>(null);
  const [detailFailed, setDetailFailed] = useState<string | null>(null);

  const params = useMemo(() => {
    const q = new URLSearchParams();
    if (status) q.set('status', status);
    if (severity) q.set('severity', severity);
    if (category) q.set('category', category);
    q.set('page', String(page));
    q.set('size', String(PAGE_SIZE));
    return q.toString();
  }, [status, severity, category, page]);

  const latest = useLatestRequest();
  const latestDetail = useLatestRequest();

  const load = useCallback(() => {
    setFailed(false);
    latest(api<Page<AlertSummary>>(`/api/v1/admin/alerts?${params}`), (res) => {
      if (res) {
        setData(res);
        setChecked(new Set());
        // A mutation (e.g. bulk-dismiss) can shrink the list so the requested
        // page no longer exists; clamp and let the params effect refetch once
        // so the pager never reads "page > total".
        const totalPages = Math.max(1, Math.ceil(res.total / PAGE_SIZE));
        if (page > totalPages - 1) setPage(totalPages - 1);
      } else {
        setFailed(true);
      }
    });
  }, [params, latest, page]);

  useEffect(() => {
    load();
  }, [load]);

  // Fetching the detail is guarded per row: the clicked row shows progress and,
  // if the request fails, an inline error right where the click happened —
  // a banner above the (possibly scrolled-away) table would read as a dead
  // click. `latestDetail` drops responses for anything but the newest click.
  const openDetail = (id: string) => {
    setRevealed(null);
    setActionError(false);
    setDetailFailed(null);
    setDetailBusy(id);
    latestDetail(api<AlertDetail>(`/api/v1/admin/alerts/${id}`), (res) => {
      setDetailBusy(null);
      if (res) setDetail(res);
      else setDetailFailed(id);
    });
  };

  const confirm = async (reason: string) => {
    if (!pending) return;
    setBusy(true);
    setActionError(false);
    let revealedMessages: RevealedMessage[] | null = null;
    try {
      if (pending.kind === 'status' && detail) {
        await api(`/api/v1/admin/alerts/${detail.alert.id}`, {
          method: 'PATCH',
          body: { status: pending.status, reason: reason || undefined },
        });
      } else if (pending.kind === 'action' && detail) {
        await api(`/api/v1/admin/alerts/${detail.alert.id}/actions`, {
          method: 'POST',
          body: { action: pending.action, reason },
        });
      } else if (pending.kind === 'reveal' && detail) {
        const res = await api<{ messages: RevealedMessage[] }>(
          `/api/v1/admin/alerts/${detail.alert.id}/reveal`,
          { method: 'POST', body: { reason } },
        );
        revealedMessages = res.messages;
      } else if (pending.kind === 'bulk') {
        await api('/api/v1/admin/alerts/bulk-dismiss', {
          method: 'POST',
          body: { ids: [...checked], reason },
        });
      }
      setPending(null);
      if (detail) openDetail(detail.alert.id);
      // openDetail clears `revealed` to hide stale context from the previous
      // alert; re-apply the fresh reveal afterwards so it survives.
      if (revealedMessages) setRevealed(revealedMessages);
      load();
    } catch {
      // The page-top alert renders behind the open drawer scrim, so the
      // failure is shown inside the drawer instead (see below). Refetch the
      // detail too: the server may have applied part of the action before
      // failing, and the drawer must not stay stale. openDetail() clears
      // actionError synchronously, so the error is re-set after it.
      if (detail) openDetail(detail.alert.id);
      setActionError(true);
      setPending(null);
      load();
    } finally {
      setBusy(false);
    }
  };

  const assignMe = () => {
    if (!detail || !me || assignBusy) return;
    setAssignBusy(true);
    setActionError(false);
    api(`/api/v1/admin/alerts/${detail.alert.id}`, {
      method: 'PATCH',
      body: { assigneeId: me.userId },
    })
      .then(() => openDetail(detail.alert.id))
      .catch(() => setActionError(true))
      .finally(() => setAssignBusy(false));
  };

  const addNote = () => {
    if (!detail || !noteText.trim() || noteBusy) return;
    setNoteBusy(true);
    setActionError(false);
    api(`/api/v1/admin/alerts/${detail.alert.id}/notes`, {
      method: 'POST',
      body: { body: noteText.trim() },
    })
      .then(() => {
        setNoteText('');
        openDetail(detail.alert.id);
      })
      .catch(() => setActionError(true))
      .finally(() => setNoteBusy(false));
  };

  const toggle = (id: string) => {
    const next = new Set(checked);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setChecked(next);
  };

  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  const pendingTitle = pending
    ? pending.kind === 'status'
      ? t(statusKey(pending.status))
      : pending.kind === 'action'
        ? pending.action === 'ESCALATE'
          ? t('alerts.action.escalate')
          : t('alerts.action.warnUser')
        : pending.kind === 'reveal'
          ? t('alerts.action.reveal')
          : t('alerts.action.dismissBulk')
    : '';

  return (
    <>
      <div className="filters">
        <label className="filter-field">
          <span>{t('alerts.col.status')}</span>
          <select
            className="select"
            aria-label={t('alerts.col.status')}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
          >
            <option value="">{t('common.all')}</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>{t(statusKey(s))}</option>
            ))}
          </select>
        </label>
        <label className="filter-field">
          <span>{t('alerts.col.severity')}</span>
          <select
            className="select"
            aria-label={t('alerts.col.severity')}
            value={severity}
            onChange={(e) => {
              setSeverity(e.target.value);
              setPage(0);
            }}
          >
            <option value="">{t('common.all')}</option>
            {SEVERITIES.map((s) => (
              <option key={s} value={s}>{s}</option>
            ))}
          </select>
        </label>
        <label className="filter-field">
          <span>{t('alerts.col.category')}</span>
          <select
            className="select"
            aria-label={t('alerts.col.category')}
            value={category}
            onChange={(e) => {
              setCategory(e.target.value);
              setPage(0);
            }}
          >
            <option value="">{t('common.all')}</option>
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
        </label>
        {checked.size > 0 && (
          <button className="btn danger sm" onClick={() => setPending({ kind: 'bulk' })}>
            {t('alerts.action.dismissBulk')} · {t('common.selected', { n: checked.size })}
          </button>
        )}
      </div>

      {/* With the drawer open, its scrim covers the page, so the page-top
          alert is only for failures that happen without a drawer. */}
      {actionError && !detail && (
        <div className="form-error" role="alert">
          {t('common.error')}
        </div>
      )}

      {failed ? (
        <ErrorBlock onRetry={load} />
      ) : !data ? (
        <Loading />
      ) : data.items.length === 0 ? (
        <Empty
          hint={status !== 'OPEN' || severity || category ? t('common.empty.filtered') : undefined}
          onClearFilters={
            status !== 'OPEN' || severity || category
              ? () => {
                  setStatus('OPEN');
                  setSeverity('');
                  setCategory('');
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
                  <th scope="col" aria-label={t('common.select')} />
                  <th scope="col">{t('alerts.col.severity')}</th>
                  <th scope="col" className="col-lo">{t('alerts.col.category')}</th>
                  <th scope="col">{t('alerts.col.snippet')}</th>
                  <th scope="col">{t('alerts.col.status')}</th>
                  <th scope="col" className="col-lo">{t('alerts.col.created')}</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((a) => (
                  <tr
                    key={a.id}
                    tabIndex={0}
                    aria-busy={detailBusy === a.id || undefined}
                    className={detailBusy === a.id ? 'row-loading' : undefined}
                    onClick={() => openDetail(a.id)}
                    onKeyDown={(e) => {
                      // Inner controls (the select checkbox) keep their own
                      // key semantics; only the row itself activates.
                      if (e.target !== e.currentTarget) return;
                      if (e.key !== 'Enter' && e.key !== ' ') return;
                      e.preventDefault();
                      openDetail(a.id);
                    }}
                  >
                    <td onClick={(e) => e.stopPropagation()}>
                      <label className="check-hit">
                        <input
                          type="checkbox"
                          className="checkbox"
                          aria-label={`${t('common.select')} ${a.id}`}
                          checked={checked.has(a.id)}
                          onChange={() => toggle(a.id)}
                        />
                      </label>
                    </td>
                    <td>
                      <Chip tone={a.severity.toLowerCase()}>{a.severity}</Chip>
                      {detailBusy === a.id && (
                        <div className="muted" style={{ fontSize: 12 }}>
                          {t('common.loading')}
                        </div>
                      )}
                      {detailFailed === a.id && (
                        <div className="form-error" role="alert">
                          {t('common.error')}
                        </div>
                      )}
                    </td>
                    <td className="col-lo">{a.category}</td>
                    <td
                      className="truncate"
                      title={a.snippetTombstoned ? undefined : (a.snippet ?? undefined)}
                    >
                      {a.snippetTombstoned ? (
                        <span className="muted">{t('alerts.tombstoned')}</span>
                      ) : (
                        a.snippet ?? '—'
                      )}
                      {a.escalated && (
                        <Chip tone="high" label={t('alerts.escalated')}>!</Chip>
                      )}
                    </td>
                    <td>
                      <Chip tone={`status-${a.status.toLowerCase()}`}>
                        {t(statusKey(a.status))}
                      </Chip>
                    </td>
                    <td className="col-lo">{fmtTime(a.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager page={page} totalPages={totalPages} onPage={setPage} />
        </>
      )}

      {detail && (
        <Drawer
          title={`${detail.alert.category} · ${detail.alert.severity}`}
          sub={detail.alert.id}
          onClose={() => {
            // Cancel any in-flight detail fetch so a late response cannot
            // reopen the drawer the user just dismissed; the row's busy and
            // error markers belong to the dismissed view too.
            latestDetail.cancel();
            setDetailBusy(null);
            setDetailFailed(null);
            setDetail(null);
          }}
        >
          {actionError && (
            <div className="form-error" role="alert">
              {t('common.error')}
            </div>
          )}
          <div className="action-row">
            {detail.alert.status === 'OPEN' && (
              <>
                <button className="btn ghost sm" onClick={assignMe} disabled={assignBusy}>
                  {t('alerts.action.assign')}
                </button>
                <button
                  className="btn ghost sm"
                  onClick={() => setPending({ kind: 'status', status: 'ACKNOWLEDGED' })}
                >
                  {t('alerts.action.ack')}
                </button>
              </>
            )}
            {(detail.alert.status === 'OPEN' || detail.alert.status === 'ACKNOWLEDGED') && (
              <>
                <button
                  className="btn primary sm"
                  onClick={() => setPending({ kind: 'status', status: 'RESOLVED' })}
                >
                  {t('alerts.action.resolve')}
                </button>
                <button
                  className="btn ghost sm"
                  onClick={() => setPending({ kind: 'status', status: 'DISMISSED' })}
                >
                  {t('alerts.action.dismiss')}
                </button>
                {!detail.alert.escalated && (
                  <button
                    className="btn ghost sm"
                    onClick={() => setPending({ kind: 'action', action: 'ESCALATE' })}
                  >
                    {t('alerts.action.escalate')}
                  </button>
                )}
                <button
                  className="btn ghost sm"
                  onClick={() => setPending({ kind: 'action', action: 'WARN_USER' })}
                >
                  {t('alerts.action.warnUser')}
                </button>
                <button
                  className="btn ghost sm"
                  onClick={() => setPending({ kind: 'reveal' })}
                >
                  {t('alerts.action.reveal')}
                </button>
              </>
            )}
          </div>

          <section>
            <h3>{t('alerts.col.status')}</h3>
            <Chip tone={`status-${detail.alert.status.toLowerCase()}`}>
              {t(statusKey(detail.alert.status))}
            </Chip>{' '}
            {detail.alert.escalated && <Chip tone="high">{t('alerts.escalated')}</Chip>}{' '}
            {detail.alert.dryRun && <Chip tone="medium">{t('alerts.dryRun')}</Chip>}
            <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>
              {t('alerts.col.created')}: {fmtTime(detail.alert.createdAt)}
            </div>
          </section>

          <section>
            <h3>{t('alerts.col.snippet')}</h3>
            {detail.alert.snippetTombstoned ? (
              <div className="muted">{t('alerts.tombstoned')}</div>
            ) : (
              <div className="snippet">{detail.alert.snippet ?? '—'}</div>
            )}
            <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>
              {detail.senderDisplayName ?? String(detail.alert.senderId)}
            </div>
          </section>

          <section>
            <h3>{t('alerts.rules')}</h3>
            <div className="action-row">
              {detail.alert.ruleNames.map((name) => (
                <Chip key={name}>{name}</Chip>
              ))}
            </div>
          </section>

          {revealed && (
            <section>
              <h3>{t('alerts.revealed')}</h3>
              {revealed.map((m) => (
                <div className="snippet" key={m.id}>
                  {m.content ?? '—'}
                  <div className="muted" style={{ fontSize: 11, marginTop: 4 }}>
                    #{m.seq} · {fmtTime(m.createdAt)}
                  </div>
                </div>
              ))}
            </section>
          )}

          <section>
            <h3>{t('alerts.notes')}</h3>
            {detail.notes.map((n) => (
              <div className="note" key={n.id}>
                <div>{n.body}</div>
                <div className="meta">{fmtTime(n.createdAt)}</div>
              </div>
            ))}
            <div className="field" style={{ marginTop: 10 }}>
              <textarea
                className="textarea"
                rows={2}
                placeholder={t('alerts.note.placeholder')}
                aria-label={t('alerts.note.placeholder')}
                value={noteText}
                onChange={(e) => setNoteText(e.target.value)}
              />
            </div>
            <button
              className="btn ghost sm"
              onClick={addNote}
              disabled={!noteText.trim() || noteBusy}
            >
              {t('alerts.note.add')}
            </button>
          </section>
        </Drawer>
      )}

      {pending && (
        <ConfirmDialog
          title={pendingTitle}
          requireReason={pending.kind !== 'status'}
          confirmDanger={pending.kind === 'bulk'}
          busy={busy}
          onConfirm={(reason) => void confirm(reason)}
          onCancel={() => setPending(null)}
        >
          {pending.kind === 'reveal' && (
            <p className="muted">{t('alerts.reveal.reason')}</p>
          )}
        </ConfirmDialog>
      )}
    </>
  );
}
