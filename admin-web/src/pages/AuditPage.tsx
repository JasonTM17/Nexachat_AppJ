import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, type AuditEntry, type Page } from '../api';
import { useI18n } from '../i18n';
import { useDebouncedValue, useLatestRequest } from '../lib/async';
import { Chip, Empty, ErrorBlock, Loading, Pager, fmtTime, truncateJson } from '../components/ui';

const PAGE_SIZE = 25;
const SEARCH_DEBOUNCE_MS = 300;
// The server binds this filter as a UUID; anything else is a 400 that would
// wipe the table. The input stays free-form (a user types the id gradually),
// but only a complete UUID is sent.
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default function AuditPage() {
  const { t } = useI18n();
  const [actor, setActor] = useState('');
  const [action, setAction] = useState('');
  const [target, setTarget] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<AuditEntry> | null>(null);
  const [failed, setFailed] = useState(false);
  const actorInput = useDebouncedValue(actor, SEARCH_DEBOUNCE_MS);
  const actionInput = useDebouncedValue(action, SEARCH_DEBOUNCE_MS);
  const targetInput = useDebouncedValue(target, SEARCH_DEBOUNCE_MS);
  const latest = useLatestRequest();
  const actorApplied = UUID_RE.test(actorInput.trim());

  const params = useMemo(() => {
    const q = new URLSearchParams();
    const actorId = actorInput.trim();
    if (UUID_RE.test(actorId)) q.set('actorId', actorId);
    if (actionInput.trim()) q.set('action', actionInput.trim());
    if (targetInput.trim()) q.set('targetId', targetInput.trim());
    q.set('page', String(page));
    q.set('size', String(PAGE_SIZE));
    return q.toString();
  }, [actorInput, actionInput, targetInput, page]);

  const load = useCallback(() => {
    setFailed(false);
    latest(api<Page<AuditEntry>>(`/api/v1/admin/audit?${params}`), (data) => {
      if (data) setData(data);
      else setFailed(true);
    });
  }, [params, latest]);

  useEffect(() => {
    load();
  }, [load]);

  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <>
      <div className="filters">
        <label className="filter-field">
          <span>{t('audit.filter.actor')}</span>
          <input
            className="input mono"
            placeholder={t('audit.filter.actor')}
            aria-label={t('audit.filter.actor')}
            aria-describedby="audit-actor-hint"
            value={actor}
            onChange={(e) => {
              setActor(e.target.value);
              setPage(0);
            }}
          />
          <span className="muted" id="audit-actor-hint" style={{ fontSize: 11 }}>
            {t('audit.filter.actorHint')}
          </span>
        </label>
        <label className="filter-field">
          <span>{t('audit.filter.action')}</span>
          <input
            className="input"
            placeholder={t('audit.filter.action')}
            aria-label={t('audit.filter.action')}
            value={action}
            onChange={(e) => {
              setAction(e.target.value);
              setPage(0);
            }}
          />
        </label>
        <label className="filter-field">
          <span>{t('audit.filter.target')}</span>
          <input
            className="input"
            placeholder={t('audit.filter.target')}
            aria-label={t('audit.filter.target')}
            value={target}
            onChange={(e) => {
              setTarget(e.target.value);
              setPage(0);
            }}
          />
        </label>
      </div>

      {failed ? (
        <ErrorBlock onRetry={load} />
      ) : !data ? (
        <Loading />
      ) : data.items.length === 0 ? (
        <Empty
          hint={actorApplied || action || target ? t('common.empty.filtered') : undefined}
          onClearFilters={
            actor || action || target
              ? () => {
                  setActor('');
                  setAction('');
                  setTarget('');
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
                  <th scope="col">{t('audit.col.time')}</th>
                  <th scope="col" className="col-lo">{t('audit.col.actor')}</th>
                  <th scope="col">{t('audit.col.action')}</th>
                  <th scope="col" className="col-lo">{t('audit.col.target')}</th>
                  <th scope="col" className="col-lo">{t('audit.col.reason')}</th>
                  <th scope="col" className="col-lo">{t('audit.col.detail')}</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((e) => (
                  <tr className="plain" key={e.id}>
                    <td style={{ whiteSpace: 'nowrap' }}>{fmtTime(e.createdAt)}</td>
                    <td className="col-lo">
                      <div className="mono" style={{ fontSize: 11 }}>{e.actorId}</div>
                      <Chip>{e.actorRole}</Chip>
                    </td>
                    <td>
                      <Chip tone={e.action.endsWith('_DENIED') ? 'high' : undefined}>
                        {e.action}
                      </Chip>
                    </td>
                    <td className="col-lo">
                      <div>{e.targetType}</div>
                      {e.targetId && (
                        <div className="muted mono" style={{ fontSize: 11 }}>{e.targetId}</div>
                      )}
                    </td>
                    <td className="truncate col-lo" title={e.reason ?? undefined}>
                      {e.reason ?? '—'}
                    </td>
                    <td
                      className="muted mono col-lo"
                      style={{ fontSize: 11, maxWidth: 240 }}
                      title={e.detail ? JSON.stringify(e.detail) : undefined}
                    >
                      {e.detail ? truncateJson(JSON.stringify(e.detail), 36) : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager page={page} totalPages={totalPages} onPage={setPage} />
        </>
      )}
    </>
  );
}
