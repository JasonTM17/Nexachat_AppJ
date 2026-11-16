import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, type AuditEntry, type Page } from '../api';
import { useI18n } from '../i18n';
import { Chip, Empty, ErrorBlock, Loading, Pager, fmtTime } from '../components/ui';

const PAGE_SIZE = 25;

export default function AuditPage() {
  const { t } = useI18n();
  const [actor, setActor] = useState('');
  const [action, setAction] = useState('');
  const [target, setTarget] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<AuditEntry> | null>(null);
  const [failed, setFailed] = useState(false);

  const params = useMemo(() => {
    const q = new URLSearchParams();
    if (actor.trim()) q.set('actorId', actor.trim());
    if (action.trim()) q.set('action', action.trim());
    if (target.trim()) q.set('targetId', target.trim());
    q.set('page', String(page));
    q.set('size', String(PAGE_SIZE));
    return q.toString();
  }, [actor, action, target, page]);

  const load = useCallback(() => {
    setFailed(false);
    api<Page<AuditEntry>>(`/api/v1/admin/audit?${params}`)
      .then(setData)
      .catch(() => setFailed(true));
  }, [params]);

  useEffect(() => {
    load();
  }, [load]);

  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <>
      <div className="filters">
        <input
          className="input mono"
          placeholder={t('audit.filter.actor')}
          aria-label={t('audit.filter.actor')}
          value={actor}
          onChange={(e) => {
            setActor(e.target.value);
            setPage(0);
          }}
        />
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
      </div>

      {failed ? (
        <ErrorBlock onRetry={load} />
      ) : !data ? (
        <Loading />
      ) : data.items.length === 0 ? (
        <Empty />
      ) : (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t('audit.col.time')}</th>
                  <th>{t('audit.col.actor')}</th>
                  <th>{t('audit.col.action')}</th>
                  <th>{t('audit.col.target')}</th>
                  <th>{t('audit.col.reason')}</th>
                  <th>{t('audit.col.detail')}</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((e) => (
                  <tr className="plain" key={e.id}>
                    <td style={{ whiteSpace: 'nowrap' }}>{fmtTime(e.createdAt)}</td>
                    <td>
                      <div className="mono" style={{ fontSize: 11 }}>{e.actorId}</div>
                      <Chip>{e.actorRole}</Chip>
                    </td>
                    <td>
                      <Chip tone={e.action.endsWith('_DENIED') ? 'high' : undefined}>
                        {e.action}
                      </Chip>
                    </td>
                    <td>
                      <div>{e.targetType}</div>
                      {e.targetId && (
                        <div className="muted mono" style={{ fontSize: 11 }}>{e.targetId}</div>
                      )}
                    </td>
                    <td className="truncate">{e.reason ?? '—'}</td>
                    <td className="muted mono" style={{ fontSize: 11, maxWidth: 240 }}>
                      {e.detail ? JSON.stringify(e.detail) : '—'}
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
